package co.electriccoin.zcash.ui.screen.exchangerate

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.screen.exchangerate.optin.ExchangeRateOptInVM
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRateSettingsVM
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeRatePreferenceScreensTest {
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val enabled = mockk<IsExchangeRateEnabledStorageProvider>(relaxed = true) {
        coEvery { get() } returns true
    }
    private val optIn = OptInExchangeRateUseCase(navigation, enabled)
    private val errors = ErrorMapperUseCase(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun optInCanRetryAReadBeforeItHasContent() = runTest(dispatcher) {
        val currency = unavailableCurrency()
        val vm = ExchangeRateOptInVM(optIn, navigation, currency, mockk(), errors)
        backgroundScope.launch { vm.state.collect { } }
        try {
            assertNull(vm.state.value.content)
            assertIs<LceError.BottomSheet>(vm.state.value.error).state.primaryAction.onClick()

            assertNotNull(vm.state.value.content)
            assertNull(vm.state.value.error)
            coVerify(exactly = 2) { currency.get() }
            coVerify(exactly = 0) { enabled.store(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun settingsCanRetryItsFirstReadWhileTheTorCopyIsStillPending() = runTest(dispatcher) {
        val currency = unavailableCurrency()
        val tor = mockk<IsTorEnabledStorageProvider> {
            every { observe() } returns flow { awaitCancellation() }
        }
        val vm = ExchangeRateSettingsVM(tor, navigation, optIn, enabled, currency, mockk(), errors)
        backgroundScope.launch { vm.state.collect { } }
        try {
            assertNull(vm.state.value.content)
            val error = assertIs<LceError.BottomSheet>(vm.state.value.error)
            vm.onBack()
            verify(exactly = 1) { navigation.back() }

            error.state.primaryAction.onClick()

            assertNull(assertNotNull(vm.state.value.content).info)
            assertNull(vm.state.value.error)
            coVerify(exactly = 2) { currency.get() }
            coVerify(exactly = 0) { enabled.store(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun unavailableCurrency() = mockk<PreferredFiatProvider> {
        coEvery { get() } throws IOException("Synthetic stored currency read failure") andThen FiatCurrency.USD
    }
}
