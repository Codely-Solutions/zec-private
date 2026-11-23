package co.electriccoin.zcash.ui.common.usecase

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.Zip321MemoFixture
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorVM
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.MessageState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionVM
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.send.Send
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class Zip321MemoScanTest {
    private val account = EditAccountFixtures.zodl()
    private val sdkProposal = mockk<Proposal> {
        every { totalFeeRequired() } returns Zatoshi(1_000)
        every { usesOrchardInputs() } returns false
    }
    private val fixture = Zip321MemoFixture
    private val expectedTextDraft = PrefillSendData.All(Zatoshi(100_000), fixture.RECIPIENT, null, listOf(fixture.TEXT))
    private val synchronizer = mockk<Synchronizer> {
        coEvery { validateAddress(fixture.RECIPIENT) } returns AddressType.Shielded
        coEvery { proposeFulfillingPaymentUri(account.sdkAccount, any()) } returns sdkProposal
    }
    private val provider = mockk<SynchronizerProvider> {
        coEvery { getSynchronizer() } returns this@Zip321MemoScanTest.synchronizer
    }
    private val accounts = mockk<AccountDataSource> {
        coEvery { getSelectedAccount() } returns account
        every { selectedAccount } returns flowOf(account)
        every { allAccounts } returns MutableStateFlow(listOf(account))
    }
    private val proposalDataSource = ProposalDataSourceImpl(provider, mockk(), mockk())
    private val repository = ZashiProposalRepositoryImpl(
        accountDataSource = accounts,
        proposalDataSource = proposalDataSource,
        zashiSpendingKeyDataSource = mockk(),
        processSwapTransaction = mockk(),
    )
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val prefill = PrefillSendUseCase()
    private val error = NavigateToErrorUseCase(router)
    private val scan = OnZip321ScannedUseCase(
        keystoneProposalRepository = mockk(relaxed = true),
        ledgerProposalPipeline = mockk(relaxed = true),
        zashiProposalRepository = repository,
        accountDataSource = accounts,
        navigationRouter = router,
        prefillSend = prefill,
        navigateToErrorUseCase = error,
    )

    @Test
    fun homeScanShowsTheOriginalNonTextOrTextMemoInReview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val vm = reviewVM
            store.put("review", vm)
            val sdkAccount = account.sdkAccount
            val binaryRequest = parse(fixture.binary)
            val originalBinaryUri = fixture.uri(fixture.binary)
            scan(binaryRequest, ScanArgs(ScanFlow.HOMEPAGE))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()

            val byteCount = assertNotNull(binaryRequest.payment.payments.single().memo).data.size
            val binaryMessage = assertNotNull(vm.state.value).items.filterIsInstance<MessageState>().single()
            assertEquals(stringRes(R.string.zip321_binary_memo, byteCount), binaryMessage.message)
            assertSame(sdkProposal, assertNotNull(repository.transactionProposal.value).proposal)
            coVerify(exactly = 1) { synchronizer.proposeFulfillingPaymentUri(sdkAccount, originalBinaryUri) }
            verify(exactly = 1) { router.replace(ReviewTransactionArgs) }
            verify(exactly = 0) { router.replace(Send(), ReviewTransactionArgs) }
            withContext(Dispatchers.Default) { assertNoDraft() }

            val textBytes = fixture.TEXT.encodeToByteArray().copyOf(512)
            val originalTextUri = fixture.uri(textBytes)
            scan(parse(textBytes), ScanArgs(ScanFlow.HOMEPAGE))
            runCurrent()

            val textMessage = assertNotNull(vm.state.value).items.filterIsInstance<MessageState>().single()
            assertEquals(stringRes(fixture.TEXT), textMessage.message)
            coVerify(exactly = 1) { synchronizer.proposeFulfillingPaymentUri(sdkAccount, originalTextUri) }
            verify(exactly = 1) { router.replace(Send(), ReviewTransactionArgs) }
            val draft = withContext(Dispatchers.Default) { withTimeout(5_000) { prefill().first() } }
            assertEquals(expectedTextDraft, draft)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelingDirectReviewGoesBackAndKeepsTheExistingSendDraft() = runBlocking {
        val clearSend = mockk<ObserveClearSendUseCase>()
        val cancel = CancelProposalFlowUseCase(
            zashiProposalRepository = repository,
            keystoneProposalRepository = mockk(relaxed = true),
            ledgerProposalPipeline = mockk(relaxed = true),
            navigationRouter = router,
            observeClearSend = clearSend,
            accountDataSource = accounts,
            swapRepository = mockk(),
            migrationNavigator = mockk(),
        )
        scan(parse(fixture.binary), ScanArgs(ScanFlow.HOMEPAGE))

        cancel()

        assertNull(repository.transactionProposal.value)
        verify(exactly = 1) { router.back() }
        verify(exactly = 0) { router.backTo(Send::class) }
        verify(exactly = 0) { clearSend.requestClear() }
    }

    @Test
    fun failedBinaryAndInvalidUtf8ProposalsNeverBecomeEditableDrafts() = runBlocking {
        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, any()) } throws IllegalStateException()
        for ((flow, bytes) in listOf(ScanFlow.HOMEPAGE to fixture.binary, ScanFlow.SEND to fixture.invalidUtf8)) {
            scan(parse(bytes), ScanArgs(flow))
            assertUneditableError()
            assertNull(repository.transactionProposal.value)
        }

        verify(exactly = 2) { router.forward(ErrorDialog) }
        verify(exactly = 0) { router.replace(Send()) }
        verify(exactly = 0) { router.back() }
        assertNoDraft()
    }

    @Test
    fun zip321SdkFailureLogsContainOnlyCategoriesForSoftwareAndKeystone() = runBlocking {
        val request = parse(fixture.binary)
        val sentinel = "synthetic SDK error echoing ${request.zip321Uri}"
        val sdkFailure = IllegalStateException(sentinel)
        ShadowLog.clear()

        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, request.zip321Uri) } throws sdkFailure
        scan(request, ScanArgs(ScanFlow.HOMEPAGE))
        assertUneditableError()
        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, request.zip321Uri) } throws
            TransactionEncoderException.InsufficientFundsException(sdkFailure)
        scan(request, ScanArgs(ScanFlow.HOMEPAGE))
        verify(exactly = 1) { router.replace(InsufficientFundsArgs) }

        val keystone = EditAccountFixtures.keystone()
        coEvery { accounts.getSelectedAccount() } returns keystone
        coEvery { synchronizer.proposeFulfillingPaymentUri(keystone.sdkAccount, request.zip321Uri) } throws sdkFailure
        val keystoneScan = OnZip321ScannedUseCase(
            keystoneProposalRepository = KeystoneProposalRepositoryImpl(accounts, proposalDataSource, mockk(), mockk()),
            ledgerProposalPipeline = mockk(relaxed = true),
            zashiProposalRepository = repository,
            accountDataSource = accounts,
            navigationRouter = router,
            prefillSend = prefill,
            navigateToErrorUseCase = error,
        )
        keystoneScan(request, ScanArgs(ScanFlow.SEND))
        assertUneditableError()

        val logs = ShadowLog.getLogs()
        assertEquals(2, logs.count { "Unable to create ZIP321 proposal" in it.msg })
        assertEquals(1, logs.count { "Insufficient funds for ZIP321 proposal" in it.msg })
        for (entry in logs) {
            assertFalse(request.zip321Uri in entry.msg)
            assertFalse(sentinel in entry.throwable?.stackTraceToString().orEmpty())
        }
    }

    @Test
    fun scanWithReviewDisabledPreservesNonTextMemoByRefusingAddressOnlyFallback() = runBlocking {
        scan(parse(fixture.binary), ScanArgs(ScanFlow.SEND, isScanZip321Enabled = false))
        coVerify(exactly = 0) { synchronizer.proposeFulfillingPaymentUri(any(), any()) }
        assertUneditableError()
        verify(exactly = 0) { router.back() }
        assertNoDraft()
    }

    @Test
    fun directPrefillRefusesNonTextAndKeepsStrictPaddedText() = runBlocking {
        for (bytes in listOf(fixture.binary, fixture.invalidUtf8)) {
            val request = parse(bytes)
            assertFailsWith<UneditableZip321MemoException> { prefill.requestFromZip321(request.payment) }
        }
        assertNoDraft()

        val text = parse(fixture.TEXT.encodeToByteArray().copyOf(512))
        val job = prefill.requestFromZip321(text.payment)
        assertEquals(expectedTextDraft, withTimeout(5_000) { prefill().first() })
        job.join()
    }

    @Test
    fun textProposalFailureStillPrefillsAnEditableDraft() = runBlocking {
        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, any()) } throws IllegalStateException()
        scan(parse(fixture.TEXT.encodeToByteArray()), ScanArgs(ScanFlow.HOMEPAGE))
        assertEquals(expectedTextDraft, withTimeout(5_000) { prefill().first() })
        verify(exactly = 1) { router.replace(Send()) }
        verify(exactly = 0) { router.forward(ErrorDialog) }
    }

    private suspend fun parse(bytes: ByteArray) = assertIs<Zip321ParseUriValidation.Valid>(
        Zip321ParseUriValidationUseCase(provider)(fixture.uri(bytes))
    )

    private suspend fun assertNoDraft() {
        assertNull(withTimeoutOrNull(250) { prefill().first() })
    }

    private val reviewVM: ReviewTransactionVM by lazy {
        val keystone = mockk<KeystoneProposalRepository>()
        val ledger = mockk<LedgerProposalPipeline>()
        val observeProposal = ObserveProposalUseCase(keystone, ledger, repository, accounts)
        ReviewTransactionVM(
            getWalletAccounts = GetWalletAccountsUseCase(accounts),
            observeContactByAddress = ObserveContactByAddressUseCase(mockk {
                every { observeContactByAddress(any()) } returns flowOf(null)
            }),
            observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accounts),
            observeProposal = observeProposal,
            observeAccountIcon = AccountIconFixtures.useCase(),
            cancelProposalFlow = CancelProposalFlowUseCase(
                repository, keystone, ledger, router, ObserveClearSendUseCase(), accounts, mockk(), mockk(),
            ),
            getExchangeRate = GetExchangeRateUseCase(mockk {
                every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
            }),
            navigationRouter = router,
            submitProposal = SubmitProposalUseCase(
                router, accounts, repository, keystone, ledger, mockk(), mockk(), mockk(), mockk(), prefill, error,
            ),
        )
    }

    private fun assertUneditableError() {
        val args = assertIs<ErrorArgs.General>(error.requireCurrentArgs())
        assertIs<UneditableZip321MemoException>(args.exception)
        val state = ErrorVM(args, error, router, mockk(), mockk()).state.value
        assertEquals(stringRes(R.string.zip321_non_text_memo_edit_error), state.message)
        assertNull(state.negative)
    }
}
