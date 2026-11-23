package co.electriccoin.zcash.ui.screen.pay

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapAssetPickerUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PayCatalogRecoveryTest {
    @Test
    fun nativeEvmPrefillSurvivesMissingAndZeroZecPriceAndWrappedAssetIsRejected() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        val fixture = SwapCatalogTestFixture
        val symbols = setOf("BNB", "AVAX", "USDT")
        val offers = fixture.externalAssets.filter {
            it.tokenTicker in symbols && it.chainTicker in setOf("bsc", "avax")
        }
        val bnb = offers.single { it.tokenTicker == "BNB" }
        val avax = offers.single { it.tokenTicker == "AVAX" }
        val bscToken = offers.single { it.tokenTicker == "USDT" }
        val zec = fixture.nativeZec.copy(usdPrice = BigDecimal("30"))
        val source = mockk<SwapDataSource> {
            coEvery { getSupportedTokens() } returnsMany listOf(
                offers, offers + zec.copy(usdPrice = BigDecimal.ZERO), offers + zec
            )
        }
        val repository = SwapRepositoryImpl(source, mockk(relaxed = true)).also { it.scope = backgroundScope }
        val simple = mockk<SimpleSwapAssetProvider> {
            every { getCuratedSwapAssets() } returns offers.map(fixture::simple)
        }
        var pickerResult: SwapAsset = bnb
        val account = mockk<WalletAccount> { every { canSpend(any()) } returns true }
        val vm = PayVM(
            getCuratedSwapAssetsUseCase = GetCuratedSwapAssetsUseCase(repository, simple),
            getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                every { observe() } returns flowOf(account)
            },
            swapRepository = repository,
            cancelSwap = mockk(),
            navigationRouter = mockk(),
            requestSwapQuote = mockk(),
            navigateToSwapQuoteIfAvailable = mockk(),
            exactOutputVMMapper = ExactOutputVMMapper(),
            navigateToScanAddress = mockk(),
            navigateToSelectSwapRecipient = mockk(),
            isABContactHintVisible = mockk<IsABContactHintVisibleUseCase> {
                every { observe(any(), any()) } returns flowOf(false)
            },
            getPreselectedSwapAsset = mockk<GetPreselectedSwapAssetUseCase> {
                coEvery { this@mockk.invoke() } returns bnb
            },
            navigateToSlippage = mockk(),
            navigateToSwapAssetPicker = mockk<NavigateToSwapAssetPickerUseCase> {
                coEvery { this@mockk.invoke(null) } answers { pickerResult }
            },
            navigateToSwapRefundWarning = mockk(),
        )
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            repository.requestRefreshAssetsOnce()
            val address = "0xde709f2102306220921060314715629080e2fb77"
            val uri = "ethereum:$address?value=2.014e18"
            fun form() = assertNotNull(vm.state.value)
            fun button(): ButtonState = assertNotNull(form().primaryButton)
            fun enter(recipient: String) {
                form().address.onValueChange(recipient)
                runCurrent()
            }
            fun choose(asset: SwapAsset) {
                pickerResult = asset
                assertNotNull(form().asset.onClick).invoke()
                runCurrent()
            }
            val retry = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain)
            enter(uri)
            assertEquals(0, form().amount.innerState.amount?.compareTo(BigDecimal("2.014")))
            assertNull(form().address.error)
            assertNull(repository.assets.value.zecAsset)
            assertEquals(retry, button().text)

            repository.requestRefreshAssetsOnce()
            runCurrent()
            assertEquals(zec.assetId, repository.assets.value.zecAsset?.assetId)
            assertEquals(BigDecimal.ZERO, repository.assets.value.zecAsset?.usdPrice)
            assertFalse(repository.assets.value.hasUsableLivePrices)
            assertEquals(retry, button().text)
            assertTrue(button().isEnabled)

            button().onClick()
            runCurrent()
            assertEquals(0, form().amount.innerState.amount?.compareTo(BigDecimal("2.014")))
            assertEquals(stringRes(co.electriccoin.zcash.ui.design.R.string.send_review), button().text)
            assertTrue(button().isEnabled)

            choose(bscToken)
            assertNotNull(form().address.error)
            assertFalse(button().isEnabled)
            enter(address)
            assertTrue(button().isEnabled)
            enter("ethereum:$address@56?value=2.014e18")
            choose(avax)
            assertNotNull(form().address.error)
            assertFalse(button().isEnabled)
            enter(uri)
            assertNull(form().address.error)
            assertEquals(0, form().amount.innerState.amount?.compareTo(BigDecimal("2.014")))
            assertTrue(button().isEnabled)
        } finally {
            vm.viewModelScope.cancel()
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }
}
