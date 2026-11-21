package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.provider.SwapAssetCacheProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SwapReviewReadinessTest {
    private val zec = SwapAssetTestFixture.zecAsset()
    private val btc = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")

    private fun quote() = FakeSwapQuote(originAsset = zec, destinationAsset = btc,
        amountInFormatted = BigDecimal.ONE, destinationAddress = "destination", refundAddress = "refund")

    private suspend fun preparedRepository(scope: TestScope): SwapRepositoryImpl {
        val source = mockk<SwapDataSource> {
            coEvery { getSupportedTokens() } returns listOf(zec, btc)
            coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } returns quote()
        }
        val cache = mockk<SwapAssetCacheProvider>(relaxed = true) { coEvery { get() } returns emptyList() }
        return SwapRepositoryImpl(source, cache).also {
            it.scope = scope.backgroundScope
            it.requestRefreshAssetsOnce()
        }
    }

    @Test
    fun providerSuccessNeedsPreparationAndItsNavigationCanOnlyBeConsumedOnce() = runTest {
        val repository = preparedRepository(this)
        repository.requestExactInputQuote(BigDecimal.ONE, "destination", "refund", btc, BigDecimal("2"))
        runCurrent()
        val current = assertIs<SwapQuoteData.Success>(repository.quote.value)
        assertNull(repository.consumeReview())
        kotlin.test.assertTrue(repository.markReviewReady(current))
        assertEquals(current, repository.consumeReview(current))
        assertFalse(repository.markReviewReady(current))
        assertNull(repository.consumeReview())
    }

    @Test
    fun clearedOrSupersededRequestsCannotPublishOrConsumeOldReviewReadiness() = runTest {
        val repository = SwapRepositoryImpl(mockk(), mockk())
        val old = SwapQuoteData.Success(quote())
        val replacement = SwapQuoteData.Success(quote())
        repository.quote.value = old
        kotlin.test.assertTrue(repository.markReviewReady(old))
        repository.quote.value = replacement
        assertNull(repository.consumeReview(old))
        assertFalse(repository.markReviewReady(old))
        kotlin.test.assertTrue(repository.markReviewReady(replacement))
        assertNull(repository.consumeReview(old))
        assertEquals(replacement, repository.consumeReview(replacement))
        repository.clearQuote()
        assertFalse(repository.markReviewReady(replacement))
        assertNull(repository.consumeReview())
    }

}
