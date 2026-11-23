package co.electriccoin.zcash.ui.screen.send

import androidx.lifecycle.SavedStateHandle
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.WALLET_EXCHANGE_RATE_MAX_AGE
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
                            coEvery { this@mockk.invoke(any(), any()) } throws RuntimeException("boom")
                        },
                )

            vm.onCreateZecSendClick(mockk<ZecSend>(relaxed = true), amountState()) { stages += it }
            runCurrent()

            assertEquals(listOf<SendStage>(SendStage.SendFailure("boom")), stages)
        }

    @Test
    fun cancelledProposalKeepsTheFormUsableForAnotherReview() = runTest {
        val createProposal = mockk<CreateProposalUseCase> {
            coEvery { this@mockk.invoke(any(), any()) } throws CancellationException("cancelled")
        }
        val vm = vm(createProposal)
        val stages = mutableListOf<SendStage>()
        val payment = mockk<ZecSend>()

        vm.onCreateZecSendClick(payment, amountState()) { stages += it }
        runCurrent()
        assertEquals(emptyList(), stages)

        coEvery { createProposal(any(), any()) } returns Unit
        vm.onCreateZecSendClick(payment, amountState()) { stages += it }
        runCurrent()
        coVerify(exactly = 2) { createProposal(payment, any()) }
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
        val payment = mockk<ZecSend> { every { amount } returns Zatoshi(25_000_000) }
        val entered = AmountState.newFromFiat("", "25", false, rates.value, requireFreshRate = true)
        fun review() {
            vm.onCreateZecSendClick(payment, entered) {}
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
        coVerify(exactly = 0) { createProposal(any(), any()) }

        rates.value = rate(100.0)
        review()
        coVerify(exactly = 1) { createProposal(payment, true) }
    }

    private fun amountState() = mockk<AmountState>(relaxed = true)

    private fun vm(
        createProposal: CreateProposalUseCase,
        rates: ExchangeRateRepository = mockk(relaxed = true),
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
        )
}
