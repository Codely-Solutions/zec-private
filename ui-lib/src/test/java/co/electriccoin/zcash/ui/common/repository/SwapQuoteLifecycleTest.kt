package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwapQuoteLifecycleTest {
    private val testScope = CoroutineScope(UnconfinedTestDispatcher())
    private val zec = SwapAssetTestFixture.zecAsset()
    private val btc = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")

    @AfterTest
    fun tearDown() = testScope.cancel()

    @Test
    fun dismissedIncomingQuoteCanBeRequestedAgainWithoutRestart() =
        runTest {
            var calls = 0
            val source = dataSourceReturning(flexQuote())
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                flexQuote(destinationAddress = if (calls++ == 0) "first-recipient" else "second-recipient")
            }
            val repository = loadedRepository(source)
            requestIncoming(repository, "first-recipient")
            assertIs<SwapQuoteData.Success>(repository.quote.value)
            repository.clearQuote()
            assertNull(repository.quote.value)
            requestIncoming(repository, "second-recipient")
            val retried = assertIs<SwapQuoteData.Success>(repository.quote.value)
            assertEquals("second-recipient", retried.quote.destinationAddress.address)
            assertEquals(2, calls)
            coVerify(exactly = 0) { source.submitDepositTransaction(any(), any()) }
        }

    @Test
    fun transientIncomingFailureCanBeRetriedWithoutRestart() =
        runTest {
            var calls = 0
            val source = dataSourceReturning(flexQuote())
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (calls++ == 0) throw IOException("Simulated temporary connection failure")
                flexQuote()
            }
            val repository = loadedRepository(source)
            requestIncoming(repository)
            assertIs<SwapQuoteData.Error>(repository.quote.value)
            repository.clearQuote()
            requestIncoming(repository)
            assertIs<SwapQuoteData.Success>(repository.quote.value)
            assertEquals(2, calls)
        }

    @Test
    fun cancellingPendingIncomingRequestDoesNotPoisonTheNextQuote() =
        runTest {
            var calls = 0
            var firstCancelled = false
            val source = dataSourceReturning(flexQuote())
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (calls++ == 0) {
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled = true
                    }
                } else {
                    flexQuote()
                }
            }
            val repository = loadedRepository(source)
            requestIncoming(repository)
            assertIs<SwapQuoteData.Loading>(repository.quote.value)
            repository.clearQuote()
            assertTrue(firstCancelled)
            assertNull(repository.quote.value)
            requestIncoming(repository)
            assertIs<SwapQuoteData.Success>(repository.quote.value)
            assertEquals(2, calls)
        }

    @Test
    fun newRequestClearsPreviousErrorBeforeBackgroundDispatch() =
        runTest {
            val repository = loadedRepository(dataSourceReturning(flexQuote()))
            repository.quote.value = SwapQuoteData.Error(SwapMode.FLEX_INPUT, IOException("old failure"))
            repository.scope = backgroundScope

            requestIncoming(repository)

            assertIs<SwapQuoteData.Loading>(repository.quote.value)
        }

    @Test
    fun missingZecAssetEndsLoadingWithoutRequestingProvider() =
        runTest {
            val source = dataSourceReturning(flexQuote())
            val repository = SwapRepositoryImpl(source, mockk(relaxed = true)).apply { scope = testScope }

            requestIncoming(repository)
            val incomingError = assertIs<SwapQuoteData.Error>(repository.quote.value)
            assertEquals(SwapMode.FLEX_INPUT, incomingError.mode)
            assertIs<SwapAssetsUnavailableException>(incomingError.exception)

            requestOutgoing(repository, btc)
            val outgoingError = assertIs<SwapQuoteData.Error>(repository.quote.value)
            assertEquals(SwapMode.EXACT_INPUT, outgoingError.mode)
            assertIs<SwapAssetsUnavailableException>(outgoingError.exception)
            coVerify(exactly = 0) { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) }

            repository.requestRefreshAssetsOnce()
            requestIncoming(repository)
            assertIs<SwapQuoteData.Success>(repository.quote.value)
        }

    @Test
    fun failedOutgoingRequestUsesCorrectedAssetWithoutRestart() =
        runTest {
            val wbtc = SwapAssetTestFixture.asset(tokenTicker = "wbtc", chainTicker = "eth")
            val source = dataSourceReturning(flexQuote())
            coEvery { source.getSupportedTokens() } returns listOf(zec, btc, wbtc)
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val selectedAsset = arg<SwapAsset>(5)
                if (selectedAsset == btc) throw IOException("Simulated timeout")
                FakeSwapQuote(
                    originAsset = zec,
                    destinationAsset = selectedAsset,
                    mode = SwapMode.EXACT_INPUT,
                    amountIn = BigDecimal("2"),
                    amountInFormatted = BigDecimal("2"),
                    amountOutFormatted = BigDecimal.ONE,
                    depositAddress = "deposit",
                    destinationAddress = arg(4),
                    refundAddress = arg(2)
                )
            }
            val repository = loadedRepository(source)

            requestOutgoing(repository, btc)
            assertIs<SwapQuoteData.Error>(repository.quote.value)
            repository.clearQuote()
            requestOutgoing(repository, wbtc)

            val result = assertIs<SwapQuoteData.Success>(repository.quote.value)
            assertEquals(wbtc, result.quote.destinationAsset)
            coVerify(exactly = 1) {
                source.requestQuote(
                    SwapMode.EXACT_INPUT, BigDecimal("2"), "refund", zec, "recipient", btc, BigDecimal("2")
                )
                source.requestQuote(
                    SwapMode.EXACT_INPUT, BigDecimal("2"), "refund", zec, "recipient", wbtc, BigDecimal("2")
                )
            }
        }

    @Test
    fun dismissedRequestCannotPublishLateNetworkErrorOverNextQuote() =
        runTest {
            val finishOldRequest = CompletableDeferred<Unit>()
            var calls = 0
            val source = dataSourceReturning(flexQuote())
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (calls++ == 0) {
                    withContext(NonCancellable) {
                        finishOldRequest.await()
                        throw IOException("Old native request failed during cancellation")
                    }
                } else {
                    flexQuote(destinationAddress = "second-recipient")
                }
            }
            val repository = loadedRepository(source)
            requestIncoming(repository, "first-recipient")
            repository.clearQuote()
            requestIncoming(repository, "second-recipient")
            val current = assertIs<SwapQuoteData.Success>(repository.quote.value)

            finishOldRequest.complete(Unit)

            assertEquals(current, repository.quote.value)
        }

    @Test
    fun dismissedRequestCannotPublishLateSuccessOverNextQuote() =
        runTest {
            val finishOldRequest = CompletableDeferred<Unit>()
            var calls = 0
            val source = dataSourceReturning(flexQuote())
            coEvery { source.requestQuote(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (calls++ == 0) {
                    withContext(NonCancellable) {
                        finishOldRequest.await()
                        flexQuote(destinationAddress = "first-recipient")
                    }
                } else {
                    flexQuote(destinationAddress = "second-recipient")
                }
            }
            val repository = loadedRepository(source)
            requestIncoming(repository, "first-recipient")
            repository.clearQuote()
            requestIncoming(repository, "second-recipient")
            val current = assertIs<SwapQuoteData.Success>(repository.quote.value)

            finishOldRequest.complete(Unit)

            assertEquals(current, repository.quote.value)
        }

    private fun requestIncoming(repository: SwapRepositoryImpl, recipient: String = "destination") =
        repository.requestFlexInputIntoZec(
            amount = BigDecimal("2"),
            refundAddress = "refund",
            destinationAddress = recipient,
            originAsset = btc,
            slippage = BigDecimal("2")
        )

    private fun requestOutgoing(repository: SwapRepositoryImpl, destinationAsset: SwapAsset) =
        repository.requestExactInputQuote(
            amount = BigDecimal("2"),
            address = "recipient",
            refundAddress = "refund",
            destinationAsset = destinationAsset,
            slippage = BigDecimal("2")
        )

    private fun flexQuote(destinationAddress: String = "destination"): SwapQuote =
        FakeSwapQuote(
            originAsset = btc,
            destinationAsset = zec,
            mode = SwapMode.FLEX_INPUT,
            amountIn = BigDecimal("100"),
            amountInFormatted = BigDecimal("2"),
            amountOutFormatted = BigDecimal.ONE,
            depositAddress = "deposit",
            destinationAddress = destinationAddress,
            refundAddress = "refund"
        )

    private fun dataSourceReturning(quote: SwapQuote) =
        mockk<SwapDataSource> {
            coEvery { getSupportedTokens() } returns listOf(zec, btc)
            coEvery { requestQuote(any(), any(), any(), any(), any(), any(), any()) } returns quote
        }

    private suspend fun loadedRepository(dataSource: SwapDataSource): SwapRepositoryImpl =
        SwapRepositoryImpl(dataSource, mockk(relaxed = true))
            .apply { scope = testScope }
            .also { it.requestRefreshAssetsOnce() }
}
