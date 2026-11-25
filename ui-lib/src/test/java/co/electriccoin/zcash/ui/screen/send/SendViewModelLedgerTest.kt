package co.electriccoin.zcash.ui.screen.send

import androidx.lifecycle.SavedStateHandle
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.WALLET_EXCHANGE_RATE_MAX_AGE
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A Ledger account now builds its proposal like any other, so a failure to create one lands on
 * the generic send-failure stage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelLedgerTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anyOtherFailureStillLandsOnTheFailureStage() =
        runTest {
            val stages = mutableListOf<SendStage>()
            val vm =
                vm(
                    createProposal =
                        mockk<CreateProposalUseCase> {
                            coEvery { this@mockk.invoke(any(), any(), any()) } throws RuntimeException("boom")
                        },
                )

            vm.onCreateZecSendClick(payment(), amountState(), vm.recipientAddressState.value) { stages += it }
            runCurrent()

            assertEquals(listOf<SendStage>(SendStage.SendFailure("boom")), stages)
        }

    @Test
    fun cancelledProposalKeepsTheFormUsableForAnotherReview() = runTest {
        val createProposal = mockk<CreateProposalUseCase> {
            coEvery { this@mockk.invoke(any(), any(), any()) } throws CancellationException("cancelled")
        }
        val vm = vm(createProposal)
        val stages = mutableListOf<SendStage>()
        val payment = payment()

        vm.onCreateZecSendClick(payment, amountState(), vm.recipientAddressState.value) { stages += it }
        runCurrent()
        assertEquals(emptyList(), stages)

        coEvery { createProposal(any(), any(), any()) } returns Unit
        vm.onCreateZecSendClick(payment, amountState(), vm.recipientAddressState.value) { stages += it }
        runCurrent()
        coVerify(exactly = 2) { createProposal(payment, any(), any()) }
        assertEquals(emptyList(), stages)
    }

    @Test
    fun retainedFiatReviewNeedsACurrentMatchingRateAndTheDisplayedNormalizedAmount() = runTest {
        val quotedAt = Clock.System.now()
        var now = quotedAt
        val clock = object : Clock {
            override fun now(): Instant = now
        }
        val usd = FiatCurrency.USD
        fun rate(price: Double) = ExchangeRateState.Data(
            currencyConversion = FiatCurrencyConversion(now, price, usd),
            clock = clock,
            onRefresh = {},
        )
        val rates = MutableStateFlow<ExchangeRateState>(rate(100.0))
        val createProposal = mockk<CreateProposalUseCase>(relaxed = true)
        val vm = vm(createProposal, mockk<ExchangeRateRepository>(relaxed = true) {
            every { state } returns rates
        })
        val payment = payment(amount = 25_000_000L)
        val entered = AmountState.newFromFiat("", "25", false, rates.value, requireFreshRate = true)
        fun review() {
            vm.onCreateZecSendClick(payment, entered, vm.recipientAddressState.value) {}
            runCurrent()
        }

        now += WALLET_EXCHANGE_RATE_MAX_AGE
        review()
        rates.value = ExchangeRateState.OptedOut
        review()
        rates.value = rate(100.0).copy(expectedCurrency = FiatCurrency("EUR"))
        review()
        rates.value = rate(200.0)
        review()
        coVerify(exactly = 0) { createProposal(any(), any(), any()) }

        rates.value = rate(100.0)
        review()
        coVerify(exactly = 1) { createProposal(payment, true, any()) }
    }

    @Test
    fun aPendingRecipientReplacementInvalidatesTheRetainedReviewAction() = runTest {
        val validate = mockk<ValidateAddressUseCase>()
        val validation = CompletableDeferred<AddressType>()
        val replacement = "synthetic-replacement"
        coEvery { validate(replacement) } coAnswers { validation.await() }
        val createProposal = mockk<CreateProposalUseCase>(relaxed = true)
        val vm = vm(createProposal, validateAddress = validate)
        val previous = vm.recipientAddressState.value

        vm.validateRecipient(replacement)
        assertEquals(replacement, vm.recipientAddressState.value.address)
        assertNull(vm.recipientAddressState.value.type)
        vm.onCreateZecSendClick(payment(), amountState(), previous) {}
        vm.onCreateZecSendClick(payment(replacement), amountState(), vm.recipientAddressState.value) {}
        runCurrent()
        coVerify(exactly = 0) { createProposal(any(), any(), any()) }

        validation.complete(AddressType.Unified)
        runCurrent()
        vm.onCreateZecSendClick(payment(), amountState(), previous) {}
        val accepted = payment(replacement)
        vm.onCreateZecSendClick(accepted, amountState(), vm.recipientAddressState.value) {}
        runCurrent()
        coVerify(exactly = 1) { createProposal(accepted, false, any()) }
        coVerify(exactly = 0) { createProposal(match { it.destination.address != replacement }, any(), any()) }
    }

    @Test
    fun proposalFinishingAfterAnotherRecipientIsChosenCannotOpenReview() = runTest {
        val proposalReady = CompletableDeferred<Unit>()
        val proposals = mockk<ZashiProposalRepository> {
            coEvery { createProposal(any()) } coAnswers {
                proposalReady.await()
                mockk()
            }
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val create = CreateProposalUseCase(
            keystoneProposalRepository = mockk(),
            ledgerProposalPipeline = mockk(),
            zashiProposalRepository = proposals,
            accountDataSource = mockk<AccountDataSource> {
                coEvery { getSelectedAccount() } returns EditAccountFixtures.zodl()
            },
            navigationRouter = router,
        )
        val vm = vm(create)
        vm.onCreateZecSendClick(payment(), amountState(), vm.recipientAddressState.value) {}
        runCurrent()
        coVerify(exactly = 1) { proposals.createProposal(any()) }
        vm.onRecipientAddressChanged(RecipientAddressState.new("synthetic-replacement", AddressType.Unified))
        proposalReady.complete(Unit)
        runCurrent()

        verify(exactly = 0) { router.forward(ReviewTransactionArgs) }
    }

    private fun payment(address: String = ORIGINAL_ADDRESS, amount: Long = 10_000L) =
        ZecSend(WalletAddress.Unified.new(address), Zatoshi(amount), Memo(""), null)

    private fun amountState() = mockk<AmountState>(relaxed = true)

    private fun vm(
        createProposal: CreateProposalUseCase,
        rates: ExchangeRateRepository = mockk(relaxed = true),
        validateAddress: ValidateAddressUseCase = mockk(),
    ) =
        SendViewModel(
            exchangeRateRepository = rates,
            observeContactByAddress = mockk(relaxed = true),
            observeContactPicked = mockk(relaxed = true),
            createProposal = createProposal,
            observeWalletAccounts = mockk(relaxed = true),
            navigateToSelectRecipient = mockk(relaxed = true),
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            savedStateHandle = SavedStateHandle(),
            validateAddress = validateAddress,
        ).apply { onRecipientAddressChanged(RecipientAddressState.new(ORIGINAL_ADDRESS, AddressType.Unified)) }

    private companion object {
        const val ORIGINAL_ADDRESS = "synthetic-original"
    }
}
