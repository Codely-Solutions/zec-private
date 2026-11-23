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
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.model.OnAmount
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestState
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestUnitPreservationTest {
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
                every { priceOfZec } returns price
                every { fiatCurrency } returns currency
            }
        },
        expectedCurrency = currency,
        onRefresh = {},
    )

    private fun TestScope.fixture(
        type: ReceiveAddressType = ReceiveAddressType.Unified,
        block: (RequestVM, MutableStateFlow<ExchangeRateState>, Zip321BuildUriUseCase) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val rate = MutableStateFlow<ExchangeRateState>(data(100.0))
            val builder = mockk<Zip321BuildUriUseCase>()
            every { builder.invoke(any(), any(), any()) } returns "synthetic-request-uri"
            val vm = RequestVM(
                addressTypeOrdinal = type.ordinal,
                application = RuntimeEnvironment.getApplication(),
                exchangeRateRepository = mockk<ExchangeRateRepository> { every { state } returns rate },
                getZcashCurrency = mockk(relaxed = true),
                zip321BuildUriUseCase = builder,
                observeSelectedWalletAccount = mockk<ObserveSelectedWalletAccountUseCase> {
                    every { require() } returns flowOf(EditAccountFixtures.zodl())
                },
                navigationRouter = mockk(relaxed = true),
                shareQR = mockk(relaxed = true),
                observeAccountIcon = AccountIconFixtures.useCase(),
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
