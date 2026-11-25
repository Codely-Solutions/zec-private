package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateUnavailable
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateError
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the synchronizer-route fallback decision and the failure classification used by
 * [ExchangeRateRepositoryImpl] when the CMC exchange-rate lookup fails or is unavailable (MOB-1124).
 */
class ExchangeRateRepositoryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun returningAfterAnInactiveOptOutWaitsForTheFreshConsentRead() = runTest {
        var optedIn = true
        var reads = 0
        val readAgain = CompletableDeferred<Unit>()
        val enabled = mockk<IsExchangeRateEnabledStorageProvider> {
            every { observe() } returns flow {
                if (++reads > 1) readAgain.await()
                emit(optedIn)
                awaitCancellation()
            }
        }
        val preferred = mockk<PreferredFiatProvider> {
            every { observe() } returns flowOf(FiatCurrency.USD)
        }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, FiatCurrency.USD)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(FiatCurrency.USD) } returns quote
        }
        val repository = ExchangeRateRepositoryImpl(enabled, preferred, rates, mockk(), backgroundScope)

        val firstCollector = backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        assertEquals(quote, assertIs<ExchangeRateState.Data>(repository.state.value).currencyConversion)
        firstCollector.cancel()
        advanceTimeBy(3.minutes.inWholeMilliseconds)
        optedIn = false

        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        assertEquals(2, reads)
        coVerify(exactly = 1) { rates.getExchangeRate(FiatCurrency.USD) }

        readAgain.complete(Unit)
        runCurrent()
        assertEquals(ExchangeRateState.OptedOut, repository.state.value)
        coVerify(exactly = 1) { rates.getExchangeRate(FiatCurrency.USD) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unreadablePreferredFiatLeavesHomeUsableAndRecoversWithTheSameCollector() = runTest {
        val enabled = mockk<IsExchangeRateEnabledStorageProvider> {
            every { observe() } returns flowOf(true)
        }
        var attempts = 0
        val euro = FiatCurrency("EUR")
        val preferred = mockk<PreferredFiatProvider> {
            every { observe() } returns flow {
                if (++attempts == 1) throw IOException("Synthetic stored currency read failure")
                emit(euro)
                awaitCancellation()
            }
        }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, euro)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(euro) } returns quote
        }
        val repository = ExchangeRateRepositoryImpl(enabled, preferred, rates, mockk(), backgroundScope)

        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        val unloaded = assertIs<ExchangeRateState.Data>(repository.state.value)

        assertNull(unloaded.currencyConversion)
        assertFalse(unloaded.isRefreshEnabled)
        coVerify(exactly = 0) { rates.getExchangeRate(any()) }

        advanceTimeBy(5_001L)

        assertEquals(2, attempts)
        val recovered = assertIs<ExchangeRateState.Data>(repository.state.value)
        assertEquals(euro, recovered.expectedCurrency)
        assertEquals(quote, recovered.currencyConversion)
        coVerify(exactly = 1) { rates.getExchangeRate(euro) }
        coVerify(exactly = 0) { rates.getExchangeRate(FiatCurrency.USD) }
    }

    @Test
    fun fallsBackWhenCmcUnavailableAndFiatIsUsd() {
        assertTrue(
            shouldFallBackToSynchronizerRoute(isCmcAvailable = false, fiat = FiatCurrency.USD)
        )
    }

    @Test
    fun fallsBackWhenCmcUnavailableAndFiatIsNonUsd() {
        // CMC is the only rate source when unavailable, so the USD-only synchronizer route is used
        // regardless of the preferred fiat (e.g. a stale non-USD preference from a prior build).
        assertTrue(
            shouldFallBackToSynchronizerRoute(isCmcAvailable = false, fiat = FiatCurrency("EUR"))
        )
    }

    @Test
    fun fallsBackWhenCmcAvailableAndFiatIsUsd() {
        assertTrue(
            shouldFallBackToSynchronizerRoute(isCmcAvailable = true, fiat = FiatCurrency.USD)
        )
    }

    @Test
    fun doesNotFallBackWhenCmcAvailableAndFiatIsNonUsd() {
        // The synchronizer route only provides a USD rate, so it cannot serve a non-USD fiat.
        assertFalse(
            shouldFallBackToSynchronizerRoute(isCmcAvailable = true, fiat = FiatCurrency("EUR"))
        )
    }

    // region classifyExchangeRateError

    @Test
    fun classifiesMissingQuoteAsRateUnavailable() {
        assertEquals(
            ExchangeRateError.CMC_EXCHANGE_RATE_UNAVAILABLE,
            classifyExchangeRateError(ExchangeRateUnavailable(message = "Exchange rate not found in response"))
        )
    }

    @Test
    fun classifiesServerErrorAsServerConnectionError() =
        runTest {
            assertEquals(
                ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
                classifyExchangeRateError(httpFailure(HttpStatusCode.InternalServerError))
            )
        }

    @Test
    fun classifiesUnauthorizedAsServerConnectionError() =
        runTest {
            assertEquals(
                ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
                classifyExchangeRateError(httpFailure(HttpStatusCode.Unauthorized))
            )
        }

    @Test
    fun classifiesNotFoundAsServerConnectionError() =
        runTest {
            assertEquals(
                ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
                classifyExchangeRateError(httpFailure(HttpStatusCode.NotFound))
            )
        }

    @Test
    fun classifiesDnsFailureAsServerConnectionError() {
        assertEquals(
            ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
            classifyExchangeRateError(UnknownHostException("pro-api.coinmarketcap.com"))
        )
    }

    @Test
    fun classifiesMalformedResponseAsServerConnectionError() {
        // ktor's JsonConvertException is a ContentConvertException subtype, so this branch covers it.
        assertEquals(
            ExchangeRateError.CMC_SERVER_CONNECTION_ERROR,
            classifyExchangeRateError(ContentConvertException("Unexpected JSON payload"))
        )
    }

    @Test
    fun classifiesTimeoutAsTransient() {
        assertNull(classifyExchangeRateError(SocketTimeoutException("read timed out")))
    }

    @Test
    fun classifiesGenericConnectivityFailureAsTransient() {
        assertNull(classifyExchangeRateError(IOException("Connection reset")))
    }

    @Test
    fun classifiesUnknownFailureAsTransient() {
        assertNull(classifyExchangeRateError(RuntimeException("unexpected")))
    }

    /**
     * Drives a real [HttpClient] with `expectSuccess = true` against a [MockEngine] returning
     * [status], so the resulting exception is a genuine ktor `ResponseException` subtype
     * (ServerResponseException for 5xx, ClientRequestException for 4xx) rather than a hand-rolled
     * stand-in.
     */
    private suspend fun httpFailure(status: HttpStatusCode): Throwable =
        HttpClient(MockEngine { respond(content = "error", status = status) }) {
            expectSuccess = true
        }.use { client ->
            runCatching { client.get("https://example.test/") }.exceptionOrNull()
        } ?: error("Expected an HTTP failure for $status")

    // endregion
}
