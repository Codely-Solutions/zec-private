package co.electriccoin.zcash.ui.screen.balances.breakdown

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.BalancePools
import co.electriccoin.zcash.ui.common.usecase.GetBalancePoolsUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BalanceBreakdownVMTest {
    @Test
    fun expiredRefreshKeepsZecBalancesWithoutStaleFiat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        try {
            every { FiatCurrency.USD.symbol } returns "$"
            val now = Instant.parse("2026-11-25T00:00:00Z")
            val clock = object : Clock { override fun now() = now }
            val quote = FiatCurrencyConversion(now, 100.0, FiatCurrency.USD)
            val fresh = ExchangeRateState.Data(isLoading = false, currencyConversion = quote, clock = clock) {}
            val rate = MutableStateFlow<ExchangeRateState>(fresh)
            val rates = mockk<ExchangeRateRepository> { every { state } returns rate }
            val balances = BalancePools(Zatoshi(100), Zatoshi(10), Zatoshi(20), Zatoshi(30), Zatoshi(40))
            val pools = mockk<GetBalancePoolsUseCase> { every { observe() } returns flowOf(balances) }
            val vm = BalanceBreakdownVM(pools, rates, mockk())
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
            try {
                val published = vm.state.filterNotNull()
                val initial = withTimeout(1_000) { published.first() }
                assertNotNull(initial.total.fiat)
                initial.pools.forEach { assertNotNull(it.fiat) }

                rate.value = fresh.copy(
                    currencyConversion = FiatCurrencyConversion(now - 16.minutes, 100.0, FiatCurrency.USD),
                )
                val expired = withTimeout(1_000) { published.first { it.total.fiat == null } }
                assertEquals(initial.total.amount, expired.total.amount)
                assertEquals(initial.pools.map { it.amount }, expired.pools.map { it.amount })
                assertNull(expired.total.fiat)
                expired.pools.forEach { assertNull(it.fiat) }
            } finally {
                collector.cancelAndJoin()
                vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            }
        } finally {
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }
}
