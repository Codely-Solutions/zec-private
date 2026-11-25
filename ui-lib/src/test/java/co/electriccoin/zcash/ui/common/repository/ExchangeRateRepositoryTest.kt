package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.ObserveFiatCurrencyResult
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateUnavailable
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.CurrencyConversionPreference
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
            every { observeSelection() } returns flow {
                if (++reads > 1) readAgain.await()
                emit(CurrencyConversionPreference(optedIn, FiatCurrency.USD))
                awaitCancellation()
            }
        }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, FiatCurrency.USD)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(FiatCurrency.USD) } returns quote
        }
        val repository = ExchangeRateRepositoryImpl(enabled, rates, mockk(), backgroundScope)

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
        var attempts = 0
        val euro = FiatCurrency("EUR")
        val settings = observedSelection(flowOf("true"), flowOf("EUR")) {
            if (++attempts == 1) throw IOException("Synthetic stored currency read failure")
            "true" to "EUR"
        }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, euro)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(euro) } returns quote
        }
        val repository = ExchangeRateRepositoryImpl(settings, rates, mockk(), backgroundScope, isCmcAvailable = true)

        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        assertEquals(ExchangeRateState.OptedOut, repository.state.value)
        coVerify(exactly = 0) { rates.getExchangeRate(any()) }

        advanceTimeBy(5_001L)

        assertTrue(attempts >= 2)
        val recovered = assertIs<ExchangeRateState.Data>(repository.state.value)
        assertEquals(euro, recovered.expectedCurrency)
        assertEquals(quote, recovered.currencyConversion)
        coVerify(exactly = 1) { rates.getExchangeRate(euro) }
        coVerify(exactly = 0) { rates.getExchangeRate(FiatCurrency.USD) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCurrencyNotificationCannotReuseOldConsentAfterThePairedOffCommit() = runTest {
        val enabledNotice = MutableStateFlow<String?>("true")
        val currencyNotice = MutableStateFlow<String?>("USD")
        var storedPair = "true" to "USD"
        val settings = observedSelection(enabledNotice, currencyNotice) { storedPair }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, FiatCurrency.USD)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(FiatCurrency.USD) } returns quote
        }
        val repository = ExchangeRateRepositoryImpl(settings, rates, mockk(), backgroundScope, isCmcAvailable = true)
        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        assertEquals(quote, assertIs<ExchangeRateState.Data>(repository.state.value).currencyConversion)

        storedPair = "false" to "EUR"
        currencyNotice.value = "EUR"
        runCurrent()

        assertEquals(ExchangeRateState.OptedOut, repository.state.value)
        coVerify(exactly = 1) { rates.getExchangeRate(FiatCurrency.USD) }
        coVerify(exactly = 0) { rates.getExchangeRate(FiatCurrency("EUR")) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCurrencySwitchNeverLabelsThePreviousPriceWithTheNewCurrency() = runTest {
        val euro = FiatCurrency("EUR")
        val usdQuote = FiatCurrencyConversion(Clock.System.now(), 100.0, FiatCurrency.USD)
        val eurQuote = FiatCurrencyConversion(Clock.System.now(), 90.0, euro)
        val nextQuote = CompletableDeferred<FiatCurrencyConversion>()
        val preference = MutableStateFlow(CurrencyConversionPreference(true, FiatCurrency.USD))
        val settings = mockk<IsExchangeRateEnabledStorageProvider> {
            every { observeSelection() } returns preference
        }
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(FiatCurrency.USD) } returns usdQuote
            coEvery { getExchangeRate(euro) } coAnswers { nextQuote.await() }
        }
        val repository = ExchangeRateRepositoryImpl(settings, rates, mockk(), backgroundScope, isCmcAvailable = true)
        val states = mutableListOf<ExchangeRateState>()
        backgroundScope.launch { repository.state.collect { states += it } }
        runCurrent()
        assertEquals(usdQuote, assertIs<ExchangeRateState.Data>(repository.state.value).currencyConversion)

        preference.value = CurrencyConversionPreference(true, euro)
        runCurrent()
        val waiting = assertIs<ExchangeRateState.Data>(repository.state.value)
        assertEquals(euro, waiting.expectedCurrency)
        assertTrue(waiting.isLoading)
        assertNull(waiting.currencyConversion)
        nextQuote.complete(eurQuote)
        runCurrent()
        assertEquals(eurQuote, assertIs<ExchangeRateState.Data>(repository.state.value).currencyConversion)
        assertTrue(states.filterIsInstance<ExchangeRateState.Data>().filter { it.expectedCurrency == euro }
            .all { it.currencyConversion == null || it.currencyConversion == eurQuote })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theSdkOnlyRouteUsesUsdAndRechecksConsentBeforeSdkAdmission() = runTest {
        val choice = CurrencyConversionPreference(true, FiatCurrency("EUR"))
        var committed = choice
        val settings = mockk<IsExchangeRateEnabledStorageProvider> {
            every { observeSelection() } returns flowOf(choice)
            coEvery { readSelection() } answers { committed }
        }
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, FiatCurrency.USD)
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(FiatCurrency.USD) } throws ExchangeRateUnavailable()
            every { observeSynchronizerRoute() } returns flowOf(ObserveFiatCurrencyResult(
                isLoading = false, currencyConversion = quote,
            ))
        }
        val sdk = mockk<Synchronizer>(relaxed = true)
        val sdkReady = CompletableDeferred<Unit>()
        val provider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } coAnswers { sdkReady.await(); sdk }
        }
        val repository = ExchangeRateRepositoryImpl(settings, rates, provider, backgroundScope, isCmcAvailable = false)
        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        committed = choice.copy(enabled = false)
        sdkReady.complete(Unit)
        runCurrent()
        coVerify(exactly = 0) { sdk.refreshExchangeRateUsd() }

        committed = choice
        assertIs<ExchangeRateState.Data>(repository.state.value).onRefresh()
        runCurrent()
        val recovered = assertIs<ExchangeRateState.Data>(repository.state.value)
        assertEquals(FiatCurrency.USD, recovered.expectedCurrency)
        assertEquals(quote, recovered.currencyConversion)
        assertEquals(FiatCurrency("EUR"), committed.fiat)
        coVerify(exactly = 1) { sdk.refreshExchangeRateUsd() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedFirstRateCanBeRetriedWithTheSameCollector() = runTest {
        val euro = FiatCurrency("EUR")
        val quote = FiatCurrencyConversion(Clock.System.now(), 100.0, euro)
        val settings = mockk<IsExchangeRateEnabledStorageProvider> {
            every { observeSelection() } returns flowOf(CurrencyConversionPreference(true, euro))
        }
        var attempts = 0
        val rates = mockk<ExchangeRateDataSource> {
            coEvery { getExchangeRate(euro) } coAnswers {
                if (++attempts == 1) throw SocketTimeoutException("Synthetic timeout")
                quote
            }
        }
        val repository = ExchangeRateRepositoryImpl(settings, rates, mockk(), backgroundScope, isCmcAvailable = true)
        backgroundScope.launch { repository.state.collect { } }
        runCurrent()
        val failed = assertIs<ExchangeRateState.Data>(repository.state.value)
        assertFalse(failed.isLoading)
        assertNull(failed.currencyConversion)
        assertTrue(failed.isRefreshEnabled)

        failed.onRefresh()
        runCurrent()
        assertEquals(quote, assertIs<ExchangeRateState.Data>(repository.state.value).currencyConversion)
        coVerify(exactly = 2) { rates.getExchangeRate(euro) }
    }

    private fun observedSelection(
        enabledNotice: Flow<String?>,
        currencyNotice: Flow<String?>,
        readPair: () -> Pair<String, String>,
    ): IsExchangeRateEnabledStorageProvider {
        val enabled = PreferenceKey("EXCHANGE_RATE_OPTED_IN_2")
        val currency = PreferenceKey("preferred_fiat_currency")
        val store = mockk<PreferenceProvider> {
            every { observe(enabled) } returns enabledNotice
            every { observe(currency) } returns currencyNotice
            coEvery { getStringSnapshot(setOf(enabled, currency)) } coAnswers {
                val (flag, fiat) = readPair()
                mapOf(enabled to flag, currency to fiat)
            }
        }
        val holder = mockk<StandardPreferenceProvider> {
            coEvery { this@mockk() } returns store
        }
        return IsExchangeRateEnabledStorageProviderImpl(holder)
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
