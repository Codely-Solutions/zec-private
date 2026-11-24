package co.electriccoin.zcash.ui.screen.request.viewmodel

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.AccountIconFixtures
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321BuildUriUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.WALLET_EXCHANGE_RATE_MAX_AGE
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.OnAmount
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestUnitPreservationTest {
    @Test
    fun subatomicFiatBlocksAllAddressModesAndOldCallbacksUntilRepresentable() = runTest {
        ReceiveAddressType.entries.forEach { type ->
            fixture(type) { vm, rate, builder ->
                amount(vm).onSwitch(RequestCurrency.FIAT)
                val oldAction = amount(vm).onDone
                amount(vm).onAmount(OnAmount.Separator("."))
                repeat(7) { amount(vm).onAmount(OnAmount.Number(0)) }
                amount(vm).onAmount(OnAmount.Number(1))
                assertEquals("0.00000001", amount(vm).request.amountState.amount)
                assertFalse(amount(vm).canContinue)
                assertTrue(amount(vm).isBelowMinimum)
                oldAction()
                amount(vm).onDone()
                assertIs<RequestState.Amount>(vm.state.value)
                verify(exactly = 0) { builder.invoke(any(), any(), any()) }
                rate.value = data(0.0002)
                assertTrue(amount(vm).canContinue)
                assertFalse(amount(vm).isBelowMinimum)
                oldAction()
                assertIs<RequestState.Amount>(vm.state.value)
                amount(vm).onDone()
                (vm.state.value as? RequestState.Memo)?.onDone?.invoke()
                assertIs<RequestState.QrCode>(vm.state.value)
                verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.00005")) == 0 }, "") }
            }
        }
    }

    @Test
    fun fiatPreviewMemoAndRealQrUseTheSameFlooredAmountInEveryMode() = runTest {
        ReceiveAddressType.entries.forEach { type ->
            fixture(type, Zip321BuildUriUseCase()) { vm, rate, _ ->
                rate.value = data(123.0)
                amount(vm).onSwitch(RequestCurrency.FIAT)
                amount(vm).onAmount(OnAmount.Number(1))
                assertZec("0.0081", amount(vm).zecAmount)
                assertTrue(amount(vm).canContinue)
                amount(vm).onDone()
                (vm.state.value as? RequestState.Memo)?.let { memo ->
                    assertZec("0.0081", memo.request.memoState.zecAmount.toBigDecimal())
                    rate.value = data(234.0).copy(isStale = true)
                    memo.onMemo(MemoState.new("Payment", "1.0"))
                    memo.onDone()
                }
                assertZec("0.0081", qrAmount(vm))
            }
        }
    }

    @Test
    fun fiatGridBoundariesAndExactOneZatoshiUseTheRealEncoder() = runTest {
        fixture(ReceiveAddressType.Transparent, Zip321BuildUriUseCase()) { vm, rate, _ ->
            rate.value = data(20_001.0)
            amount(vm).onSwitch(RequestCurrency.FIAT)
            amount(vm).onAmount(OnAmount.Number(1))
            assertTrue(amount(vm).isBelowMinimum)
            assertFalse(amount(vm).canContinue)
            amount(vm).onDone()
            assertIs<RequestState.Amount>(vm.state.value)

            rate.value = data(20_000.0)
            assertZec("0.00005", amount(vm).zecAmount)
            amount(vm).onDone()
            assertZec("0.00005", qrAmount(vm))
            assertIs<RequestState.QrCode>(vm.state.value).onBack()

            rate.value = data(19_999.0)
            assertZec("0.00005", amount(vm).zecAmount)
            amount(vm).onDone()
            assertZec("0.00005", qrAmount(vm))
            assertIs<RequestState.QrCode>(vm.state.value).onBack()
            amount(vm).onAmount(OnAmount.Delete)
            amount(vm).onSwitch(RequestCurrency.ZEC)
            amount(vm).onAmount(OnAmount.Separator("."))
            repeat(7) { amount(vm).onAmount(OnAmount.Number(0)) }
            amount(vm).onAmount(OnAmount.Number(1))
            amount(vm).onDone()
            assertZec("0.00000001", qrAmount(vm))
        }
    }

    @Test
    fun staleCachedRateHoldsNextThroughRefreshUntilFreshConversionArrives() = runTest {
        fixture(ReceiveAddressType.Transparent) { vm, rate, builder ->
            enterFiat(vm)
            val beforeRefresh = amount(vm)
            var refreshes = 0
            rate.value = data(100.0).copy(isLoading = false, isStale = true, onRefresh = { refreshes++ })
            val blocked = amount(vm)
            assertFalse(blocked.canContinue)
            assertFalse(blocked.isBelowMinimum)
            assertEquals("25", blocked.request.amountState.amount)
            assertIs<ExchangeRateState.Data>(blocked.exchangeRateState).onRefresh()
            assertEquals(1, refreshes)

            rate.value = data(100.0).copy(isLoading = true, isStale = true)
            beforeRefresh.onDone()
            blocked.onDone()
            assertFalse(amount(vm).canContinue)
            verify(exactly = 0) { builder.invoke(any(), any(), any()) }

            rate.value = data(200.0)
            beforeRefresh.onDone()
            blocked.onDone()
            assertEquals("25", amount(vm).request.amountState.amount)
            amount(vm).onDone()
            verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.125")) == 0 }, "") }
        }
    }

    @Test
    fun retainedNextRejectsAnExpiredRateBeforeTheRepositoryPublishesStaleness() = runTest {
        fixture(ReceiveAddressType.Transparent) { vm, rate, builder ->
            val quotedAt = Clock.System.now()
            var now = quotedAt
            val clock = object : Clock {
                override fun now(): Instant = now
            }
            rate.value = data(100.0).copy(
                currencyConversion = FiatCurrencyConversion(quotedAt, 100.0, FiatCurrency.USD),
                clock = clock,
            )
            enterFiat(vm)
            val retained = amount(vm)
            now += WALLET_EXCHANGE_RATE_MAX_AGE

            assertFalse(retained.canContinue)
            retained.onDone()
            assertEquals("25", amount(vm).request.amountState.amount)
            verify(exactly = 0) { builder.invoke(any(), any(), any()) }

            rate.value = data(200.0).copy(
                currencyConversion = FiatCurrencyConversion(now, 200.0, FiatCurrency.USD),
                clock = clock,
            )
            retained.onDone()
            amount(vm).onDone()
            verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.125")) == 0 }, "") }
        }
    }

    @Test
    fun callbacksFromCancelledMemoDoNotChangeOrCompleteAReplacement() = runTest {
        fixture { vm, rate, builder ->
            enterFiat(vm)
            val oldAmount = amount(vm)
            oldAmount.onDone()
            val oldMemo = assertIs<RequestState.Memo>(vm.state.value)
            oldMemo.onBack()
            rate.value = data(200.0)
            oldAmount.onDone()
            assertIs<RequestState.Amount>(vm.state.value)
            amount(vm).onDone()
            oldMemo.onMemo(MemoState.new("Old memo", "999"))
            oldMemo.onDone()
            oldMemo.onBack()
            verify(exactly = 0) { builder.invoke(any(), any(), any()) }
            assertIs<RequestState.Memo>(vm.state.value).onDone()
            verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.125")) == 0 }, "") }
        }
    }

    private fun qrAmount(vm: RequestVM): BigDecimal =
        assertIs<RequestState.QrCode>(vm.state.value).request.qrCodeState.requestUri
            .split('?', '&').single { it.startsWith("amount=") }.substringAfter('=').toBigDecimal()

    private fun assertZec(expected: String, amount: BigDecimal?) {
        if (amount == null) error("Expected a represented ZEC amount")
        assertEquals(0, BigDecimal(expected).compareTo(amount))
    }

    @Test
    fun fiatRateLossBlocksBothAddressTypesAndStaleCallbacksUntilRecovery() = runTest {
        ReceiveAddressType.entries.forEach { type ->
            fixture(type) { vm, rate, builder ->
                enterFiat(vm)
                val originalAction = amount(vm).onDone
                rate.value = ExchangeRateState.Data(currencyConversion = null, onRefresh = {})
                assertFalse(amount(vm).canContinue)
                assertEquals("25", amount(vm).request.amountState.amount)
                originalAction()
                amount(vm).onDone()
                assertIs<RequestState.Amount>(vm.state.value)
                verify(exactly = 0) { builder.invoke(any(), any(), any()) }
                rate.value = data(200.0)
                assertTrue(amount(vm).canContinue)
                originalAction()
                assertIs<RequestState.Amount>(vm.state.value)
                amount(vm).onDone()
                (vm.state.value as? RequestState.Memo)?.onDone?.invoke()
                assertIs<RequestState.QrCode>(vm.state.value)
                verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.125")) == 0 }, "") }
            }
        }
    }

    @Test
    fun currencyChangesNeverRelabelOrConvertAnExistingFiatInput() = runTest {
        fixture { vm, rate, builder ->
            enterFiat(vm)
            rate.value = data(100.0, FiatCurrency("EUR"))
            val state = amount(vm)
            assertEquals(FiatCurrency.USD, state.request.amountState.fiatCurrency)
            assertEquals(FiatCurrency.USD, assertIs<ExchangeRateState.Data>(state.exchangeRateState).expectedCurrency)
            assertFalse(state.canContinue)
            state.onDone()
            state.onSwitch(RequestCurrency.ZEC)
            assertEquals(RequestCurrency.FIAT, amount(vm).request.amountState.currency)
            verify(exactly = 0) { builder.invoke(any(), any(), any()) }
            rate.value = data(100.0)
            amount(vm).onSwitch(RequestCurrency.ZEC)
            assertEquals(RequestCurrency.ZEC, amount(vm).request.amountState.currency)
            amount(vm).onSwitch(RequestCurrency.FIAT)
            assertEquals(FiatCurrency.USD, amount(vm).request.amountState.fiatCurrency)
            assertTrue(amount(vm).canContinue)
        }
    }

    @Test
    fun nonpositiveNonfiniteMismatchedAndOptedOutRatesAreUnavailable() = runTest {
        fixture { vm, rate, _ ->
            enterFiat(vm)
            listOf(
                data(0.0), data(-1.0), data(Double.NaN), data(Double.POSITIVE_INFINITY),
                data(100.0).copy(expectedCurrency = FiatCurrency("EUR")),
                ExchangeRateState.OptedOut, ExchangeRateState.OptIn,
            ).forEach { unavailable ->
                rate.value = unavailable
                assertFalse(amount(vm).canContinue)
                amount(vm).onDone()
                assertEquals("25", amount(vm).request.amountState.amount)
            }
            rate.value = data(100.0)
            assertTrue(amount(vm).canContinue)
        }
    }

    @Test
    fun zecInputRemainsUsableWithoutRatesAndDuplicateAmountCompletionDoesNotBuildTwice() = runTest {
        fixture(ReceiveAddressType.Transparent) { vm, rate, builder ->
            amount(vm).onAmount(OnAmount.Number(2))
            rate.value = ExchangeRateState.OptedOut
            val action = amount(vm).onDone
            assertTrue(amount(vm).canContinue)
            action()
            action()
            verify(exactly = 1) { builder.invoke(any(), BigDecimal("2"), "") }
        }
    }

    @Test
    fun memoRetainsItsZecAmountAcrossRateLossAndBackRevalidatesFiat() = runTest {
        fixture { vm, rate, builder ->
            enterFiat(vm)
            amount(vm).onDone()
            assertEquals(
                0, BigDecimal("0.25").compareTo(
                    assertIs<RequestState.Memo>(vm.state.value).request.memoState.zecAmount.toBigDecimal()
                )
            )
            rate.value = data(0.0)
            assertIs<RequestState.Memo>(vm.state.value).onBack()
            assertFalse(amount(vm).canContinue)
            rate.value = data(100.0)
            amount(vm).onDone()
            rate.value = ExchangeRateState.OptedOut
            assertIs<RequestState.Memo>(vm.state.value).onDone()
            verify(exactly = 1) { builder.invoke(any(), match { it.compareTo(BigDecimal("0.25")) == 0 }, "") }
        }
    }

    @Test
    fun rateRecoveryRevalidatesSupplyLimitAndMismatchedRatesDoNotValidateEdits() = runTest {
        fixture { vm, rate, builder ->
            enterFiat(vm)
            val completion = amount(vm).onDone
            rate.value = ExchangeRateState.Data(currencyConversion = null, onRefresh = {})
            repeat(8) { amount(vm).onAmount(OnAmount.Number(0)) }
            assertEquals("2500000000", amount(vm).request.amountState.amount)
            rate.value = data(100.0)
            assertFalse(amount(vm).canContinue)
            completion()
            assertIs<RequestState.Amount>(vm.state.value)
            verify(exactly = 0) { builder.invoke(any(), any(), any()) }
            rate.value = data(0.00001, FiatCurrency("EUR"))
            amount(vm).onAmount(OnAmount.Number(1))
            assertEquals("25000000001", amount(vm).request.amountState.amount)
            assertFalse(amount(vm).canContinue)
            rate.value = data(10000.0)
            assertTrue(amount(vm).canContinue)
        }
    }

    private fun enterFiat(vm: RequestVM) {
        amount(vm).onSwitch(RequestCurrency.FIAT)
        amount(vm).onAmount(OnAmount.Number(2))
        amount(vm).onAmount(OnAmount.Number(5))
        assertTrue(amount(vm).canContinue)
    }

    private fun amount(vm: RequestVM) = assertIs<RequestState.Amount>(vm.state.value)

    private fun data(price: Double, currency: FiatCurrency = FiatCurrency.USD) = ExchangeRateState.Data(
        currencyConversion = if (price > 0 && price.isFinite()) {
            FiatCurrencyConversion(Clock.System.now(), price, currency)
        } else {
            mockk {
                every { timestamp } returns Clock.System.now()
                every { priceOfZec } returns price
                every { fiatCurrency } returns currency
            }
        },
        expectedCurrency = currency,
        onRefresh = {},
    )

    private fun TestScope.fixture(
        type: ReceiveAddressType = ReceiveAddressType.Unified,
        uriBuilder: Zip321BuildUriUseCase? = null,
        block: (RequestVM, MutableStateFlow<ExchangeRateState>, Zip321BuildUriUseCase) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val rate = MutableStateFlow<ExchangeRateState>(data(100.0))
            val builder = uriBuilder ?: mockk<Zip321BuildUriUseCase> {
                every { this@mockk.invoke(any<String>(), any<BigDecimal>(), any<String>()) } returns
                    "synthetic-request-uri"
            }
            val vm = RequestVM(
                addressTypeOrdinal = type.ordinal,
                application = RuntimeEnvironment.getApplication(),
                exchangeRateRepository = mockk<ExchangeRateRepository> { every { state } returns rate },
                getZcashCurrency = mockk(relaxed = true),
                zip321BuildUriUseCase = builder,
                observeSelectedWalletAccount = mockk<ObserveSelectedWalletAccountUseCase> {
                    every { this@mockk() } returns flowOf(
                        EditAccountFixtures.zodl().copy(
                            // Public mainnet fixtures. The shielded addresses use unified test vector row 0.
                            transparentAddress = "t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU",
                            unifiedAddress = "u1l8xunezsvhq8fgzfl7404m450nwnd76zshscn6nfys7vyz2ywyh4cc5daaq0c" +
                                "7q2su5lqfh23sp7fkf3kt27ve5948mzpfdvckzaect2jtte308mkwlycj2u0eac077wu70vqcetkxf",
                            saplingAddress =
                                "zs1mrhc9y7jdh5r9ece8u5khgvj9kg0zgkxzdduyv0whkg7lkcrkx5xqem3e48avjq9wn2rukydkwn",
                        )
                    )
                },
                navigationRouter = mockk(relaxed = true),
                shareQR = mockk(relaxed = true),
                observeAccountIcon = AccountIconFixtures.useCase(),
                appAccessSession = AppAccessSession().apply { unlock() },
                walletWriteAccess = WalletWriteAccess(),
            )
            store.put("request", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            block(vm, rate, builder)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
