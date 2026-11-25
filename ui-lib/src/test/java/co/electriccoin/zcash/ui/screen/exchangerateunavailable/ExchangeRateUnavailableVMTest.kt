package co.electriccoin.zcash.ui.screen.exchangerateunavailable

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateError
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeRateUnavailableVMTest {
    private val dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
    private val pending = PendingNavigation()
    private val router = mockk<NavigationRouter>(relaxed = true) {
        every { pendingNavigation } returns pending
    }
    private val provider = mockk<IsExchangeRateEnabledStorageProvider>(relaxed = true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun copyUsesTheFailedCurrencyOrTheUsdDefault() {
        val failed = vm(dataState(FiatCurrency("JPY")))
        val fallback = vm(ExchangeRateState.OptIn)
        try {
            val state = assertNotNull(failed.state.value.content)
            assertEquals(stringRes(R.string.send_currencyUnavailable_title, "JPY"), state.title)
            assertEquals(
                stringRes(R.string.send_currencyUnavailable_desc, stringResByFiatDisplayName(FiatCurrency("JPY"))),
                state.subtitle,
            )
            assertEquals(
                stringRes(R.string.send_currencyUnavailable_title, "USD"),
                fallback.state.value.content?.title,
            )
        } finally {
            failed.viewModelScope.cancel()
            fallback.viewModelScope.cancel()
        }
    }

    @Test
    fun failedUsdSaveRetainsTheSheetAndSuccessfulRetryQueuesOneOwnedDismissal() = runTest(dispatcher) {
        var fail = true
        coEvery { provider.store(true, FiatCurrency.USD) } coAnswers {
            if (fail) throw IOException("Synthetic currency preference commit failure")
        }
        val vm = vm()
        backgroundScope.launch { vm.state.collect { } }
        try {
            assertNotNull(vm.state.value.content).switchToUsdButton.onClick()
            assertIs<LceError.BottomSheet>(vm.state.value.error)
            assertTrue(pending.commands.value.isEmpty())
            assertNotNull(vm.state.value.content)

            fail = false
            assertNotNull(vm.state.value.content).switchToUsdButton.onClick()

            assertNull(vm.state.value.error)
            coVerify(exactly = 2) { provider.store(true, FiatCurrency.USD) }
            verify(exactly = 0) { router.back() }
            assertEquals(NavigationCommand.Back, pending.commands.value.getValue("conversion-dialog").buildCommand())
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun continueInZecAndBackDismissWithoutChangingConversion() {
        val vm = vm()
        try {
            val view = assertNotNull(vm.state.value.content)
            view.continueInZecButton.onClick()
            view.onBack()
            verify(exactly = 2) { router.back() }
            coVerify(exactly = 0) { provider.store(any(), any()) }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun vm(repositoryState: ExchangeRateState = dataState(FiatCurrency("JPY"))) =
        ExchangeRateUnavailableVM(
            navigationRouter = router,
            optInExchangeRate = OptInExchangeRateUseCase(router, provider),
            exchangeRateRepository = mockk<ExchangeRateRepository> {
                every { state } returns MutableStateFlow(repositoryState)
            },
            errorMapper = ErrorMapperUseCase(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
            sourceEntryId = "conversion-dialog",
        )

    private fun dataState(currency: FiatCurrency) = ExchangeRateState.Data(
        error = ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
        expectedCurrency = currency,
        onRefresh = {},
    )
}
