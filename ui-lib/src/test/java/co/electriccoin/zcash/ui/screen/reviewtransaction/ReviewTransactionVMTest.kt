package co.electriccoin.zcash.ui.screen.reviewtransaction

import androidx.compose.ui.graphics.Color
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ConfirmedAccountSelection
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.AccountIconFixtures
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import co.electriccoin.zcash.ui.R as UiR

/**
 * The sending account's row shows the account's icon as the rest of the app does: the Zodl mark on
 * the account's colour once it has one, its own drawable before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewTransactionVMTest {
    private val zodl = EditAccountFixtures.zodl()

    private val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(zodl, EditAccountFixtures.viewOnly()))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aColoredZodlAccountSendsWithItsMarkOnItsColor() =
        runTest {
            val vm = vm(metadata = mapOf(zodl.sdkAccount.accountUuid to AccountIconFixtures.metadata(Color(COLOR))))
            collect(vm)

            assertEquals(
                ImageResource.Layered(
                    background = Color(COLOR),
                    foreground = ImageResource.ByDrawable(R.drawable.ic_item_zodl_glyph)
                ),
                sender(vm).icon
            )
        }

    @Test
    fun aZodlAccountWithoutAColorSendsWithItsOwnIcon() =
        runTest {
            val vm = vm(metadata = emptyMap())
            collect(vm)

            assertEquals(imageRes(zodl.icon), sender(vm).icon)
        }

    @Test
    fun sendReviewObservesRatesThatArriveAfterOpening() = runTest {
        val loading = ExchangeRateState.Data(onRefresh = {})
        val rates = MutableStateFlow<ExchangeRateState>(loading)
        val vm = vm(metadata = emptyMap(), rates = rates)
        collect(vm)
        fun amount() = assertNotNull(vm.state.value).items.filterIsInstance<AmountState>().single()
        val displayedAmount = amount().amount
        assertEquals(loading, amount().exchangeRate)

        val fresh = loading.copy(
            isLoading = false,
            currencyConversion = FiatCurrencyConversion(Instant.parse("2026-11-25T00:00:00Z"), 150.0),
        )
        rates.value = fresh
        runCurrent()
        assertEquals(fresh, amount().exchangeRate)
        assertEquals(displayedAmount, amount().amount)

        rates.value = ExchangeRateState.OptedOut
        runCurrent()
        assertEquals(ExchangeRateState.OptedOut, amount().exchangeRate)
    }

    @Test
    fun sendConfirmsTheDisplayedProposalAcrossAuthentication() = runTest {
        confirmAfterReplacingProposal(proposal(), proposal(amount = 4321L))
    }

    @Test
    fun zip321ConfirmsTheDisplayedProposalAcrossAuthentication() = runTest {
        confirmAfterReplacingProposal(zip321Proposal(), zip321Proposal(amount = 4321L))
    }

    @Test
    fun zip321NonTextMemoIsDisclosedWithoutReplacementText() = runTest {
        val proposals = MutableStateFlow<SendTransactionProposal>(zip321Proposal(binaryMemoByteCount = 4))
        val vm = vm(
            metadata = emptyMap(),
            observeProposal = mockk {
                every { filterSend() } returns proposals
                every { observeSendReview() } returns proposals
            },
        )
        collect(vm)
        val items = assertNotNull(vm.state.value).items
        assertEquals(
            stringRes(UiR.string.zip321_binary_memo, 4),
            items.filterIsInstance<MessageState>().single().message,
        )

        proposals.value = zip321Proposal(memo = "Synthetic text memo")
        runCurrent()
        assertEquals(
            stringRes("Synthetic text memo"),
            assertNotNull(vm.state.value).items.filterIsInstance<MessageState>().single().message,
        )

        proposals.value = zip321Proposal()
        runCurrent()
        assertEquals(emptyList(), assertNotNull(vm.state.value).items.filterIsInstance<MessageState>())
    }

    @Test
    fun zip321NonTextMemoReplacementRequiresNewAuthenticationAtTheSameAmount() = runTest {
        confirmAfterReplacingProposal(zip321Proposal(binaryMemoByteCount = 4), zip321Proposal(binaryMemoByteCount = 4))
    }

    private fun TestScope.confirmAfterReplacingProposal(
        reviewed: SendTransactionProposal,
        replacement: SendTransactionProposal,
    ) {
        val current = MutableStateFlow<TransactionProposal?>(reviewed)
        val zashiRepository = mockk<ZashiProposalRepository>(relaxed = true) {
            every { transactionProposal } returns current
        }
        val keystoneRepository = mockk<KeystoneProposalRepository>()
        val ledgerPipeline = mockk<LedgerProposalPipeline>()
        val accountDataSource = mockk<AccountDataSource> {
            every { selectedAccount } returns MutableStateFlow(zodl)
            coEvery { getSelectedAccount() } returns zodl
            coEvery { confirmSelection(zodl) } returns ConfirmedAccountSelection({ true }, { true })
        }
        val authenticationStarted = CompletableDeferred<Unit>()
        val authentication = CompletableDeferred<Unit>()
        val biometricRepository = mockk<BiometricRepository> {
            coEvery { requestBiometrics(any()) } coAnswers {
                authenticationStarted.complete(Unit)
                authentication.await()
            }
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)
        val submit = SubmitProposalUseCase(
            navigationRouter = router,
            accountDataSource = accountDataSource,
            zashiProposalRepository = zashiRepository,
            keystoneProposalRepository = keystoneRepository,
            ledgerProposalPipeline = ledgerPipeline,
            ledgerNavigator = mockk(),
            biometricRepository = biometricRepository,
            swapRepository = mockk(relaxed = true),
            processSwapTransaction = mockk(),
            prefillSend = mockk(relaxed = true),
            navigateToError = navigateToError,
            appAccess = co.electriccoin.zcash.ui.common.model.AppAccessSession().apply { unlock() },
            wallet = co.electriccoin.zcash.ui.common.provider.WalletWriteAccess(),
        ).also { it.scope = backgroundScope }
        val vm = vm(
            metadata = emptyMap(),
            observeProposal = ObserveProposalUseCase(
                keystoneProposalRepository = keystoneRepository,
                ledgerProposalPipeline = ledgerPipeline,
                zashiProposalRepository = zashiRepository,
                accountDataSource = accountDataSource,
            ),
            submitProposal = submit,
        )
        collect(vm)
        val confirm = assertNotNull(vm.state.value).primaryButton.onClick
        confirm()
        runCurrent()
        check(authenticationStarted.isCompleted)
        current.value = replacement
        runCurrent()
        authentication.complete(Unit)
        runCurrent()

        coVerify(exactly = 0) { zashiRepository.submit(any(), null, any()) }
        verify(exactly = 1) { navigateToError(any()) }
        verify(exactly = 0) { router.replace(TransactionProgressArgs) }

        assertNotNull(vm.state.value).primaryButton.onClick()
        runCurrent()
        coVerify(exactly = 1) { zashiRepository.submit(replacement, null, any()) }
        verify(exactly = 1) { router.replace(TransactionProgressArgs) }
    }

    private fun sender(vm: ReviewTransactionVM) =
        assertNotNull(assertNotNull(vm.state.value).items.filterIsInstance<SenderState>().singleOrNull())

    private fun vm(
        metadata: Map<AccountUuid, MetadataV3>,
        observeProposal: ObserveProposalUseCase,
        submitProposal: SubmitProposalUseCase = mockk(relaxed = true),
        rates: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut),
    ) =
        ReviewTransactionVM(
            getWalletAccounts = EditAccountFixtures.getWalletAccounts(accounts),
            observeContactByAddress =
                mockk<ObserveContactByAddressUseCase> { every { this@mockk(any()) } returns flowOf(null) },
            observeSelectedWalletAccount =
                mockk<ObserveSelectedWalletAccountUseCase> { every { require() } returns flowOf(zodl) },
            observeProposal = observeProposal,
            observeAccountIcon = AccountIconFixtures.useCase(MutableStateFlow(metadata)),
            cancelProposalFlow = mockk(relaxed = true),
            exchangeRateRepository = mockk { every { state } returns rates },
            navigationRouter = mockk(relaxed = true),
            submitProposal = submitProposal,
        )

    private suspend fun vm(
        metadata: Map<AccountUuid, MetadataV3>,
        rates: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut),
    ): ReviewTransactionVM {
        val proposal = proposal()
        return vm(
            metadata,
            mockk<ObserveProposalUseCase> {
                every { filterSend() } returns MutableStateFlow(proposal)
                every { observeSendReview() } returns MutableStateFlow(proposal)
            },
            rates = rates,
        )
    }

    private suspend fun proposal(amount: Long = 1234L): SendTransactionProposal =
        RegularTransactionProposal(
            destination = WalletAddress.Unified.new(RECIPIENT),
            amount = Zatoshi(amount),
            memo = Memo(""),
            proposal =
                mockk<Proposal> {
                    every { totalFeeRequired() } returns Zatoshi(1000L)
                    every { usesOrchardInputs() } returns false
                },
        )

    private suspend fun zip321Proposal(
        amount: Long = 1234L,
        memo: String = "",
        binaryMemoByteCount: Int? = null,
    ): SendTransactionProposal {
        val regular = proposal(amount)
        return Zip321TransactionProposal(
            regular.destination, regular.amount, Memo(memo), regular.proposal, binaryMemoByteCount,
        )
    }

    private fun TestScope.collect(vm: ReviewTransactionVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        runCurrent()
    }

    private companion object {
        const val RECIPIENT = "recipient"
        const val COLOR = 0xFFBE38F3
    }
}
