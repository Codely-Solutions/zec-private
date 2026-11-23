package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.isZCashAsset
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProviderImpl
import co.electriccoin.zcash.ui.common.usecase.GetCuratedSwapAssetsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetPreselectedSwapAssetUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwapAssetAdmissionTest {
    private val fixture = SwapCatalogTestFixture
    private val simple = SimpleSwapAssetProviderImpl(fixture.tokenIcons, fixture.tokenNames, fixture.blockchains)

    @Test
    fun everyPinnedAssetRejectsIdentityDriftAndRecoversAfterRefresh() = runTest {
        var catalog = fixture.assets
        var quoteCalls = 0
        val source = mockk<SwapDataSource> {
            coEvery { getSupportedTokens() } answers { catalog }
            coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                quoteCalls++
                throw IOException("Synthetic provider refusal")
            }
        }
        val repository = SwapRepositoryImpl(source, mockk(relaxed = true)).also { it.scope = backgroundScope }
        val curated = GetCuratedSwapAssetsUseCase(repository, simple)
        val counterAsset = fixture.externalAssets.first()

        for (asset in fixture.assets) {
            val history = mockk<MetadataRepository> {
                every { observeLastUsedAssetHistory() } returns flowOf(setOf(fixture.simple(asset)))
            }
            val preselect = GetPreselectedSwapAssetUseCase(repository, history, simple)
            val canonicalCatalog = if (asset.isZCashAsset) {
                listOf(asset, counterAsset)
            } else {
                listOf(fixture.nativeZec, asset)
            }
            for (mutation in mutations(asset)) {
                val callsBefore = quoteCalls
                catalog = canonicalCatalog.map { if (it == asset) mutation else it }
                repository.requestRefreshAssetsOnce()
                assertTrue(mutation in repository.assets.value.recoveryAssets.orEmpty(), mutation.assetId)
                assertTrue(curated().data.orEmpty().none { it == mutation }, mutation.assetId)
                if (asset.isZCashAsset) {
                    assertNull(repository.assets.value.zecAsset, mutation.toString())
                }
                val candidate = if (asset.isZCashAsset) counterAsset else asset
                repository.requestExactOutputQuote(BigDecimal.ONE, "recipient", "refund", candidate, BigDecimal.ONE)
                runCurrent()
                assertIs<SwapQuoteData.Error>(repository.quote.value)
                assertEquals(callsBefore, quoteCalls, mutation.toString())

                val restored = if (asset.isZCashAsset) null else async { preselect() }
                runCurrent()
                if (restored != null) assertFalse(restored.isCompleted, mutation.toString())
                catalog = canonicalCatalog
                repository.requestRefreshAssetsOnce()
                if (restored != null) assertEquals(asset, restored.await())
                assertEquals(fixture.nativeZec, repository.assets.value.zecAsset)
                assertTrue(candidate in curated().data.orEmpty())

                if (!asset.isZCashAsset) {
                    repository.requestExactOutputQuote(BigDecimal.ONE, "recipient", "refund", mutation, BigDecimal.ONE)
                    runCurrent()
                    assertIs<SwapQuoteData.Error>(repository.quote.value)
                    assertEquals(callsBefore, quoteCalls, mutation.toString())
                }
                repository.requestExactOutputQuote(BigDecimal.ONE, "recipient", "refund", candidate, BigDecimal.ONE)
                runCurrent()
                assertIs<IOException>(assertIs<SwapQuoteData.Error>(repository.quote.value).exception)
                assertEquals(callsBefore + 1, quoteCalls)
            }
        }
    }

    @Test
    fun incomingQuoteRacingCatalogDriftReturnsAnErrorAndCanRetry() = runTest {
        val asset = fixture.externalAssets.first()
        val destination = fixture.nativeZec
        val firstResponse = CompletableDeferred<Unit>()
        var catalog = listOf(asset, destination)
        var requests = 0
        val source = mockk<SwapDataSource> {
            coEvery { getSupportedTokens() } answers { catalog }
            coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (requests++ == 0) firstResponse.await()
                FakeSwapQuote(
                    originAsset = asset,
                    destinationAsset = destination,
                    mode = SwapMode.FLEX_INPUT,
                    amountIn = BigDecimal.ONE.movePointRight(asset.decimals),
                    amountInFormatted = BigDecimal.ONE,
                    amountOutFormatted = BigDecimal.ONE,
                    depositAddress = "deposit",
                    destinationAddress = "destination",
                    refundAddress = "refund",
                )
            }
        }
        val repository = SwapRepositoryImpl(source, mockk(relaxed = true)).also { it.scope = backgroundScope }
        fun quote() = repository.requestFlexInputIntoZec(
            BigDecimal.ONE, "refund", "destination", asset, BigDecimal.ONE
        )
        repository.requestRefreshAssetsOnce()
        quote()
        runCurrent()
        assertEquals(SwapQuoteData.Loading, repository.quote.value)
        catalog = listOf(asset.copy(decimals = asset.decimals + 1), destination)
        repository.requestRefreshAssetsOnce()
        firstResponse.complete(Unit)
        runCurrent()
        assertIs<SwapQuoteData.Error>(repository.consumeReview())

        quote()
        runCurrent()
        assertIs<SwapQuoteData.Error>(repository.quote.value)
        assertEquals(1, requests)

        catalog = listOf(asset, destination)
        repository.requestRefreshAssetsOnce()
        quote()
        runCurrent()
        assertIs<SwapQuoteData.Success>(repository.quote.value)
        assertEquals(2, requests)
    }

    private fun mutations(asset: NearSwapAsset): List<NearSwapAsset> = listOf(
        asset.copy(assetId = "test:${asset.assetId}"),
        asset.copy(tokenTicker = "OTHER"),
        asset.copy(blockchain = fixture.blockchains.getBlockchain("other")),
        asset.copy(decimals = asset.decimals + 1),
        asset.copy(contractAddress = "test:other-contract"),
    )
}
