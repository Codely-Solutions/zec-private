package co.electriccoin.zcash.ui.screen.signkeystonetransaction

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CancelProposalFlowUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneProposalPCZTEncoderUseCase
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real signing VM/use case owns encoder readiness; device encoding is the external port. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignKeystoneTransactionVMTest {
    private val models = mutableListOf<SignKeystoneTransactionVM>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun encoderFailureDisablesNavigationAndRetryPreparesTheSameProposal() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } throws IllegalStateException("Synthetic failure")
        start(h.vm)
        val failed = assertNotNull(h.vm.state.value)
        assertEquals(KeystoneQrPreparation.ERROR, failed.preparation)
        assertNull(failed.qrData)
        assertFalse(failed.positiveButton.isEnabled)
        failed.positiveButton.onClick()
        verify(exactly = 0) { h.router.forward(any()) }
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        assertNotNull(failed.retryButton).onClick()
        assertNotNull(failed.retryButton).onClick()
        runCurrent()
        val ready = assertNotNull(h.vm.state.value)
        assertEquals(R.drawable.ic_item_keystone, ready.accountInfo.icon)
        assertEquals(KeystoneQrPreparation.READY, ready.preparation)
        assertNotNull(ready.qrData)
        assertTrue(ready.positiveButton.isEnabled)
        failed.positiveButton.onClick()
        verify(exactly = 0) { h.router.forward(any()) }
        ready.positiveButton.onClick()
        verify(exactly = 1) { h.router.forward(ScanKeystonePCZTRequest("prepared")) }
        coVerify(exactly = 2) { h.repository.createPCZTEncoder() }
    }

    @Test
    fun replacingProposalInvalidatesOldQrNavigationAndLateEncoder() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        val old = assertNotNull(h.vm.state.value)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<UREncoder>()
        coEvery { h.repository.createPCZTEncoder() } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { finish.await() }
        }
        h.proposals.value = approvedShield()
        runCurrent()
        entered.await()
        old.positiveButton.onClick()
        old.generateNextQrCode()
        assertEquals(KeystoneQrPreparation.LOADING, h.vm.state.value?.preparation)
        assertNull(h.vm.state.value?.qrData)
        h.proposals.value = approvedShield()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        finish.complete(encoder())
        runCurrent()
        assertEquals(KeystoneQrPreparation.READY, h.vm.state.value?.preparation)
        verify(exactly = 0) { h.router.forward(any()) }
        coVerify(exactly = 3) { h.repository.createPCZTEncoder() }
    }

    @Test
    fun independentEncoderCancellationCanRetryButDisposalPublishesNoError() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } throws CancellationException("Encoder interrupted")
        start(h.vm)
        val failed = assertNotNull(h.vm.state.value)
        assertEquals(KeystoneQrPreparation.ERROR, failed.preparation)
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { h.repository.createPCZTEncoder() } coAnswers {
            entered.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled = true }
        }
        assertNotNull(failed.retryButton).onClick()
        runCurrent()
        entered.await()
        val pending = assertNotNull(h.vm.state.value)
        assertFalse(pending.positiveButton.isEnabled)
        h.vm.viewModelScope.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(KeystoneQrPreparation.LOADING, h.vm.state.value?.preparation)
        pending.positiveButton.onClick()
        verify(exactly = 0) { h.router.forward(any()) }
    }

    @Test
    fun removedProposalDisablesAReadyCallbackAndDoesNotEncodeAgain() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        val ready = assertNotNull(h.vm.state.value)
        h.proposals.value = null
        ready.positiveButton.onClick()
        ready.generateNextQrCode()
        runCurrent()
        assertNull(h.vm.state.value)
        h.vm.leaveSigning()
        verify(exactly = 1) { h.router.back() }
        coVerify(exactly = 1) { h.repository.createPCZTEncoder() }
        verify(exactly = 0) { h.router.forward(any()) }
    }

    @Test
    fun accountReplacementInvalidatesRetainedQrWithoutReencodingTheOldProposal() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        val ready = assertNotNull(h.vm.state.value)
        val original = h.wallets.value
        val replacement = EditAccountFixtures.keystone(isSelected = true).copy(
            sdkAccount = EditAccountFixtures.sdkAccount(7)
        )
        h.wallets.value = listOf(replacement)
        ready.positiveButton.onClick()
        ready.generateNextQrCode()
        runCurrent()
        assertNull(h.vm.state.value)
        coVerify(exactly = 1) { h.repository.createPCZTEncoder() }
        h.wallets.value = original
        runCurrent()
        assertTrue(assertNotNull(h.vm.state.value).positiveButton.isEnabled)
        ready.positiveButton.onClick()
        h.wallets.value = null
        assertNotNull(h.vm.state.value).positiveButton.onClick()
        verify(exactly = 0) { h.router.forward(any()) }
    }

    @Test
    fun disposalInvalidatesAReadyCallback() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        val ready = assertNotNull(h.vm.state.value)
        h.vm.viewModelScope.cancel()
        ready.positiveButton.onClick()
        ready.generateNextQrCode()
        verify(exactly = 0) { h.router.forward(any()) }
    }

    @Test
    fun aDelayedRejectCannotCancelAReplacementAndCurrentRejectStillWorks() = runTest {
        val h = harness()
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        assertNotNull(h.vm.state.value).negativeButton.onClick()
        runCurrent()
        val old = assertNotNull(h.vm.bottomSheetState.value)
        old.negativeButton.onClick()
        h.proposals.value = approvedShield()
        advanceTimeBy(400)
        runCurrent()
        verify(exactly = 0) { h.cancel.rejectKeystone(any(), any(), null) }

        assertNotNull(h.vm.state.value).negativeButton.onClick()
        runCurrent()
        val current = assertNotNull(h.vm.bottomSheetState.value)
        old.negativeButton.onClick()
        current.negativeButton.onClick()
        current.negativeButton.onClick()
        advanceTimeBy(400)
        runCurrent()
        verify(exactly = 1) { h.cancel.rejectKeystone(checkNotNull(h.proposals.value), any(), null) }
    }

    @Test
    fun aRevokedShieldGrantRemovesTheQrAndOffersReturn() = runTest {
        val h = harness()
        var approved = true
        h.proposals.value = approvedShield { approved }
        coEvery { h.repository.createPCZTEncoder() } returns encoder()
        start(h.vm)
        val stale = assertNotNull(h.vm.state.value)
        assertNotNull(stale.qrData)

        approved = false
        h.preparationIds.value = null
        runCurrent()
        assertNull(h.vm.state.value)
        stale.positiveButton.onClick()
        verify(exactly = 0) { h.router.forward(any()) }
        h.vm.leaveSigning()
        verify(exactly = 1) { h.router.back() }
    }

    private fun TestScope.start(vm: SignKeystoneTransactionVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.bottomSheetState.collect() }
        runCurrent()
    }

    private fun harness(): Harness {
        val proposals = MutableStateFlow<TransactionProposal?>(approvedShield())
        val preparationIds = MutableStateFlow<String?>("prepared")
        val wallets = MutableStateFlow<List<WalletAccount>?>(listOf(EditAccountFixtures.keystone(isSelected = true)))
        val repository = mockk<KeystoneProposalRepository> {
            every { transactionProposal } returns proposals
            every { signingPreparationId() } returns "prepared"
            every { signingPreparationIds } returns preparationIds
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val cancel = mockk<CancelProposalFlowUseCase>(relaxed = true)
        val vm = SignKeystoneTransactionVM(
            getWalletAccounts = GetWalletAccountsUseCase(
                mockk<AccountDataSource> { every { allAccounts } returns wallets }
            ),
            keystoneProposalRepository = repository,
            navigationRouter = router,
            createKeystoneProposalPCZTEncoder = CreateKeystoneProposalPCZTEncoderUseCase(repository),
            cancelKeystoneProposalFlow = cancel,
            sharePCZT = mockk(),
        )
        models += vm
        return Harness(vm, proposals, wallets, repository, router, cancel, preparationIds)
    }

    private fun approvedShield(isCurrent: () -> Boolean = { true }) =
        ShieldTransactionProposal(mockk(), ShieldingApproval(mockk(), isCurrent))

    private fun encoder() = UREncoder(UR.fromBytes("zcash-pczt", ByteArray(30) { it.toByte() }), 20, 10, 0L)

    private data class Harness(
        val vm: SignKeystoneTransactionVM,
        val proposals: MutableStateFlow<TransactionProposal?>,
        val wallets: MutableStateFlow<List<WalletAccount>?>,
        val repository: KeystoneProposalRepository,
        val router: NavigationRouter,
        val cancel: CancelProposalFlowUseCase,
        val preparationIds: MutableStateFlow<String?>,
    )
}
