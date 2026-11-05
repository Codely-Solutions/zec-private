package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
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
