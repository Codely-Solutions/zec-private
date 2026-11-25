package co.electriccoin.zcash.ui.common.usecase

import android.os.Parcel
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.GiftCardLinkStoreImpl
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
import co.electriccoin.zcash.ui.screen.error.noReportAccess
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.redeemgift.navigateToRedeemGiftCard
import co.electriccoin.zcash.ui.screen.reviewtransaction.MessageState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionVM
import co.electriccoin.zcash.ui.screen.scan.ImageToQrCodeResult
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressVM
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs
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
import kotlinx.coroutines.test.TestScope
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
import org.robolectric.RuntimeEnvironment
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
    private val selectedAccounts = MutableStateFlow<List<WalletAccount>?>(listOf(account))
    private val accounts = mockk<AccountDataSource> {
        coEvery { getSelectedAccount() } returns account
        every { selectedAccount } returns flowOf(account)
        every { allAccounts } returns selectedAccounts
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
        review = paymentReviewFixture(accounts, router, repository),
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
            verify(exactly = 1) { router.replace(ofType<ReviewTransactionArgs>()) }
            verify(exactly = 0) { router.replace(Send(), ofType<ReviewTransactionArgs>()) }
            withContext(Dispatchers.Default) { assertNoDraft() }

            val textBytes = fixture.TEXT.encodeToByteArray().copyOf(512)
            val originalTextUri = fixture.uri(textBytes)
            scan(parse(textBytes), ScanArgs(ScanFlow.HOMEPAGE))
            runCurrent()

            val textMessage = assertNotNull(vm.state.value).items.filterIsInstance<MessageState>().single()
            assertEquals(stringRes(fixture.TEXT), textMessage.message)
            coVerify(exactly = 1) { synchronizer.proposeFulfillingPaymentUri(sdkAccount, originalTextUri) }
            verify(exactly = 1) { router.replace(Send(), ofType<ReviewTransactionArgs>()) }
            val draft = withContext(Dispatchers.Default) { withTimeout(5_000) { prefill().first() } }
            assertEquals(expectedTextDraft, draft)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restoredReviewOffersBackForRegularSendAndSendOriginZip321() = runTest {
        for (fromScan in listOf(false, true)) assertRestoredSendReview(fromScan)
    }

    private suspend fun TestScope.assertRestoredSendReview(fromScan: Boolean) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val original = sendNavigationController().also { it.graph = sendReviewGraph(it) }
            if (fromScan) {
                scan(parse(fixture.TEXT.encodeToByteArray()), ScanArgs(ScanFlow.SEND))
            } else {
                coEvery { synchronizer.proposeTransfer(account.sdkAccount, any(), any(), any()) } returns sdkProposal
                repository.createProposal(
                    ZecSend(WalletAddress.Unified.new(fixture.RECIPIENT), Zatoshi(100_000), Memo(""), null)
                )
            }
            assertNotNull(repository.transactionProposal.value)
            original.navigate(ReviewTransactionArgs())

            val restored = sendNavigationController()
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(assertNotNull(original.saveState()))
                parcel.setDataPosition(0)
                restored.restoreState(parcel.readBundle(javaClass.classLoader))
            } finally {
                parcel.recycle()
            }
            restored.graph = sendReviewGraph(restored)
            assertEquals(ReviewTransactionArgs(), restored.currentBackStackEntry?.toRoute<ReviewTransactionArgs>())

            val freshRepository = ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = proposalDataSource,
                zashiSpendingKeyDataSource = mockk(),
                processSwapTransaction = mockk(),
            )
            val recoveryRouter = mockk<NavigationRouter> {
                every { back() } answers { check(restored.popBackStack()) }
            }
            val vm = newReviewVM(freshRepository, recoveryRouter)
            store.put("restored-review", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()

            val state = assertNotNull(vm.state.value)
            assertEquals(
                stringRes(R.string.private_review_unavailable_title),
                state.items.filterIsInstance<MessageState>().single().title,
            )
            assertEquals(stringRes(R.string.general_back), state.primaryButton.text)
            assertEquals(ReviewTransactionArgs(), restored.currentBackStackEntry?.toRoute<ReviewTransactionArgs>())
            state.primaryButton.onClick()
            assertEquals(Send(), restored.currentBackStackEntry?.toRoute<Send>())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restoredHomeScanReturnsToHomeForMissingHardwareProposals() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            for (hardware in listOf(EditAccountFixtures.keystone(), EditAccountFixtures.ledger())) {
                every { accounts.selectedAccount } returns flowOf(hardware)
                val navigation = sendNavigationController().apply {
                    graph = createGraph(startDestination = HomeArgs) {
                        composable<HomeArgs> { }
                        composable<ReviewTransactionArgs> { }
                    }
                    navigate(ReviewTransactionArgs())
                }
                val recoveryRouter = mockk<NavigationRouter> {
                    every { back() } answers { check(navigation.popBackStack()) }
                }
                val vm = newReviewVM(
                    repository,
                    recoveryRouter,
                    KeystoneProposalRepositoryImpl(accounts, proposalDataSource, mockk(), mockk()),
                    mockk { every { transactionProposal } returns MutableStateFlow(null) },
                )
                store.put("hardware-review", vm)
                val watcher = backgroundScope.launch { vm.state.collect {} }
                runCurrent()
                val state = assertNotNull(vm.state.value)
                assertIs<MessageState>(state.items.single())
                state.onBack()
                assertEquals(HomeArgs, navigation.currentBackStackEntry?.toRoute<HomeArgs>())
                watcher.cancel()
            }
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
        )
        scan(parse(fixture.binary), ScanArgs(ScanFlow.HOMEPAGE))

        val proposal = assertIs<SendTransactionProposal>(repository.transactionProposal.value)
        cancel.rejectReviewed(proposal, account.sdkAccount.accountUuid, null)

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

        verify(exactly = 2) { router.forward(ofType<ErrorDialog>()) }
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

        val keystone = EditAccountFixtures.keystone(isSelected = true)
        selectedAccounts.value = listOf(keystone)
        coEvery { accounts.getSelectedAccount() } returns keystone
        coEvery { synchronizer.proposeFulfillingPaymentUri(keystone.sdkAccount, request.zip321Uri) } throws sdkFailure
        val keystoneScan = OnZip321ScannedUseCase(
            review = paymentReviewFixture(
                accounts, router, repository,
                keystone = KeystoneProposalRepositoryImpl(accounts, proposalDataSource, mockk(), mockk()),
            ),
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
    fun scannerResumesAfterTheNonTextMemoOverlayAndProcessesGalleryOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val uri = fixture.uri(fixture.binary)
            coEvery { synchronizer.validateAddress(uri) } returns AddressType.Invalid("Not a raw address")
            val scanner = ScanZashiAddressVM(
                args = ScanArgs(ScanFlow.SEND, isScanZip321Enabled = false),
                synchronizerProvider = provider,
                zip321ParseUriValidationUseCase = Zip321ParseUriValidationUseCase(provider),
                onAddressScanned = OnAddressScannedUseCase(router, prefill, mockk()),
                zip321Scanned = scan,
                navigateToRedeemGiftCard = navigateToRedeemGiftCard(GiftCardLinkStoreImpl(), router),
                navigationRouter = router,
            )
            store.put("scanner", scanner)
            scanner.onScanned(uri).join()
            assertUneditableError()
            verify(exactly = 1) { router.forward(ofType<ErrorDialog>()) }

            scanner.onScanned(uri).join()
            scanner.onImageScanned(ImageToQrCodeResult.SingleCode(fixture.RECIPIENT)).join()
            verify(exactly = 1) { router.forward(ofType<ErrorDialog>()) }
            verify(exactly = 0) { router.back() }

            val dialog = ErrorVM(errorArgs(), error, router, mockk(), mockk(), noReportAccess())
            dialog.state.value.onBack()
            verify(exactly = 1) { router.back() }

            scanner.onResumed()
            assertEquals(ScanValidationState.NONE, scanner.state.value)
            scanner.onImageScanned(ImageToQrCodeResult.SingleCode(fixture.RECIPIENT)).join()
            runCurrent()
            scanner.onScanned(fixture.RECIPIENT).join()

            verify(exactly = 2) { router.back() }
            val draft = withContext(Dispatchers.Default) { withTimeout(5_000) { prefill().first() } }
            assertEquals(PrefillSendData.FromAddressScan(fixture.RECIPIENT), draft)
            assertEquals(ScanValidationState.VALID, scanner.state.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retryAfterHardwareTexOverlayCanEnterReviewWithTheSameScanner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val uri = fixture.uri(fixture.binary)
            coEvery { synchronizer.validateAddress(uri) } returns AddressType.Invalid("A payment URI")
            val keystone = EditAccountFixtures.keystone(isSelected = true)
            selectedAccounts.value = listOf(keystone)
            val hardwareScan = OnZip321ScannedUseCase(
                review = paymentReviewFixture(
                    accounts, router, repository,
                    keystone = mockk<KeystoneProposalRepository> {
                        coEvery { preparePayment(any(), any()) } throws TexUnsupportedOnKSException()
                    },
                ),
                navigationRouter = router,
                prefillSend = prefill,
                navigateToErrorUseCase = error,
            )
            val scanner = ScanZashiAddressVM(
                ScanArgs(ScanFlow.SEND), provider, Zip321ParseUriValidationUseCase(provider),
                OnAddressScannedUseCase(router, prefill, mockk()), hardwareScan,
                navigateToRedeemGiftCard(GiftCardLinkStoreImpl(), router),
                navigationRouter = router,
            )
            store.put("scanner", scanner)
            scanner.onImageScanned(ImageToQrCodeResult.SingleCode(uri)).join()
            runCurrent()
            scanner.onScanned(uri).join()
            verify(exactly = 1) { router.forward(TEXUnsupportedArgs(isLedger = false)) }
            verify(exactly = 0) { router.replace(ofType<ReviewTransactionArgs>()) }

            selectedAccounts.value = listOf(account)
            scanner.onResumed()
            scanner.onScanned(uri).join()
            scanner.onScanned(uri).join()
            verify(exactly = 1) { router.replace(ofType<ReviewTransactionArgs>()) }
            assertNotNull(repository.transactionProposal.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
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
        verify(exactly = 0) { router.forward(ofType<ErrorDialog>()) }
    }

    private suspend fun parse(bytes: ByteArray) = assertIs<Zip321ParseUriValidation.Valid>(
        Zip321ParseUriValidationUseCase(provider)(fixture.uri(bytes))
    )

    private suspend fun assertNoDraft() {
        assertNull(withTimeoutOrNull(250) { prefill().first() })
    }

    private fun sendNavigationController() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
    }

    private fun sendReviewGraph(controller: NavHostController) = controller.createGraph(startDestination = Send()) {
        composable<Send> { }
        composable<ReviewTransactionArgs> { }
    }

    private val reviewVM: ReviewTransactionVM by lazy { newReviewVM(repository) }

    private fun newReviewVM(
        repository: ZashiProposalRepositoryImpl,
        reviewRouter: NavigationRouter = router,
        keystone: KeystoneProposalRepository = mockk(),
        ledger: LedgerProposalPipeline = mockk(),
    ): ReviewTransactionVM {
        val observeProposal = ObserveProposalUseCase(keystone, ledger, repository, accounts)
        return ReviewTransactionVM(
            getWalletAccounts = GetWalletAccountsUseCase(accounts),
            observeContactByAddress = ObserveContactByAddressUseCase(mockk {
                every { observeContactByAddress(any()) } returns flowOf(null)
            }),
            observeSelectedWalletAccount = ObserveSelectedWalletAccountUseCase(accounts),
            observeProposal = observeProposal,
            observeAccountIcon = AccountIconFixtures.useCase(),
            cancelProposalFlow = CancelProposalFlowUseCase(
                repository, keystone, ledger, router, ObserveClearSendUseCase(), accounts, mockk(),
            ),
            getExchangeRate = GetExchangeRateUseCase(mockk {
                every { state } returns MutableStateFlow(ExchangeRateState.OptedOut)
            }),
            navigationRouter = reviewRouter,
            submitProposal = SubmitProposalUseCase(
                router, accounts, repository, keystone, ledger, mockk(), mockk(), mockk(), mockk(), prefill, error,
                co.electriccoin.zcash.ui.common.model.AppAccessSession().apply { unlock() },
                co.electriccoin.zcash.ui.common.provider.WalletWriteAccess(),
            ),
        )
    }

    private fun errorArgs(): ErrorArgs {
        val routes = mutableListOf<ErrorDialog>()
        verify(atLeast = 1) { router.forward(capture(routes)) }
        return requireNotNull(error.argsFor(routes.last()))
    }

    private fun assertUneditableError() {
        val args = assertIs<ErrorArgs.General>(errorArgs())
        assertIs<UneditableZip321MemoException>(args.exception)
        val state = ErrorVM(args, error, router, mockk(), mockk(), noReportAccess()).state.value
        assertEquals(stringRes(R.string.zip321_non_text_memo_edit_error), state.message)
        assertNull(state.negative)
    }
}
