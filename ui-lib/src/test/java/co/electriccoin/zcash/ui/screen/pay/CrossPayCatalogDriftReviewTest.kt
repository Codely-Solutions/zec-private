package co.electriccoin.zcash.ui.screen.pay

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapAssetsData
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.IsABContactHintVisibleUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSwapRefundWarningUseCase
import co.electriccoin.zcash.ui.common.usecase.RequestSwapQuoteUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CrossPayCatalogDriftReviewTest {
    @Test
    fun defaultBtcAndRefreshedSelectionRequireThePinnedPrecision() = runTest(timeout = 15.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FiatCurrency.USD)
        every { FiatCurrency.USD.symbol } returns "$"
        try {
            val fixture = SwapCatalogTestFixture
            val zec = fixture.nativeZec.copy(usdPrice = BigDecimal("30"))
            val btc = fixture.externalAssets.single { it.assetId == "nep141:btc.omft.near" }
                .copy(usdPrice = BigDecimal("100000"))
            val eth = fixture.externalAssets.single { it.assetId == "nep141:eth.omft.near" }
                .copy(usdPrice = BigDecimal("3000"))
            val driftedBtc = btc.copy(decimals = 9)
            val wireRequest = CompletableDeferred<QuoteRequest>()
            val near = mockk<NearApiProvider> {
                coEvery { requestQuote(any()) } coAnswers {
                    wireRequest.complete(firstArg())
                    throw IllegalArgumentException("Synthetic quote provider refused request")
                }
            }
            val source = NearSwapDataSource(near, mockk(), mockk(), mockk(), mockk())
            val repository = SwapRepositoryImpl(source, mockk()).also {
                it.scope = backgroundScope
                it.assets.value = SwapAssetsData(data = listOf(eth, btc), zecAsset = zec)
            }
            val simple = mockk<SimpleSwapAssetProvider> {
                every { getCuratedSwapAssets() } returns listOf(fixture.simple(btc), fixture.simple(eth))
                every { get("btc", "btc") } returns fixture.simple(btc)
            }
            val metadata = mockk<MetadataRepository> {
                every { observeLastUsedAssetHistory() } returns flowOf(emptySet())
            }
            val curated = GetCuratedSwapAssetsUseCase(repository, simple)
            val preselected = GetPreselectedSwapAssetUseCase(repository, metadata, simple)
            assertEquals(listOf(eth, btc), curated().data)
            assertEquals(btc, preselected())

            repository.assets.value = SwapAssetsData(data = listOf(eth, driftedBtc), zecAsset = zec)
            assertEquals(listOf(eth), curated().data)
            val automaticSelection = async { preselected() }
            runCurrent()
            assertFalse(automaticSelection.isCompleted)

            val router = mockk<NavigationRouter>(relaxed = true)
            val account = mockk<WalletAccount> { every { canSpend(any()) } returns true }
            val request = RequestSwapQuoteUseCase(
                navigationRouter = router,
                navigateToErrorUseCase = mockk(relaxed = true),
                swapRepository = repository,
                zashiProposalRepository = mockk(),
                keystoneProposalRepository = mockk(),
                ledgerProposalPipeline = mockk(),
                accountDataSource = mockk<AccountDataSource> {
                    coEvery { getSelectedAccount() } returns account
                    coEvery { requestNextShieldedAddress(requireFresh = true, account = account) } returns
                        "synthetic-refund-address"
                },
                synchronizerProvider = mockk(),
            )
            val vm = PayVM(
                getCuratedSwapAssetsUseCase = curated,
                getSelectedWalletAccount = mockk<GetSelectedWalletAccountUseCase> {
                    every { observe() } returns flowOf(account)
                },
                swapRepository = repository,
                cancelSwap = mockk(),
                navigationRouter = router,
                requestSwapQuote = request,
                navigateToSwapQuoteIfAvailable = mockk(),
                exactOutputVMMapper = ExactOutputVMMapper(),
                navigateToScanAddress = mockk(),
                navigateToSelectSwapRecipient = mockk(),
                isABContactHintVisible = mockk<IsABContactHintVisibleUseCase> {
                    every { observe(any(), any()) } returns flowOf(false)
                },
                getPreselectedSwapAsset = preselected,
                navigateToSlippage = mockk(),
                navigateToSwapAssetPicker = mockk(),
                navigateToSwapRefundWarning = mockk<NavigateToSwapRefundWarningUseCase> {
                    coEvery { this@mockk.invoke(any(), any()) } returns true
                },
            )
            try {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                runCurrent()
                fun form() = assertNotNull(vm.state.value)
                val recipient = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"
                form().address.onValueChange(recipient)
                form().amount.onValueChange(NumberTextFieldInnerState.fromAmount(BigDecimal("0.1")))
                runCurrent()
                assertFalse(assertNotNull(form().primaryButton).isEnabled)

                repository.assets.value = SwapAssetsData(data = listOf(eth, btc), zecAsset = zec)
                runCurrent()
                assertEquals(btc, automaticSelection.await())
                assertNull(form().address.error)
                val staleReview = assertNotNull(form().primaryButton)
                assertTrue(staleReview.isEnabled)

                repository.assets.value = SwapAssetsData(data = listOf(eth, driftedBtc), zecAsset = zec)
                runCurrent()
                assertFalse(assertNotNull(form().primaryButton).isEnabled)
                staleReview.onClick()
                runCurrent()
                assertFalse(wireRequest.isCompleted)

                repository.assets.value = SwapAssetsData(data = listOf(eth, btc), zecAsset = zec)
                runCurrent()
                val review = assertNotNull(form().primaryButton)
                assertTrue(review.isEnabled)
                review.onClick()
                val quoted = assertNotNull(withContext(Dispatchers.Default) {
                    withTimeoutOrNull(5.seconds) { wireRequest.await() }
                })
                assertEquals(btc.assetId, quoted.destinationAsset)
                assertEquals(recipient, quoted.recipient)
                val serialized = Json.encodeToString(QuoteRequest.serializer(), quoted)
                val amount = Json.parseToJsonElement(serialized).jsonObject.getValue("amount").jsonPrimitive.content
                assertEquals("10000000", amount)
            } finally {
                vm.viewModelScope.cancel()
            }
        } finally {
            unmockkObject(FiatCurrency.USD)
            Dispatchers.resetMain()
        }
    }
}
