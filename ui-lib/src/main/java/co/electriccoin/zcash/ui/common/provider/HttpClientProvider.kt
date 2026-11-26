package co.electriccoin.zcash.ui.common.provider

import android.util.Log
import co.electriccoin.zcash.global.DirectHttpTransportUnavailable
import co.electriccoin.zcash.global.WalletSdkTransportGate
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface HttpClientProvider {
    suspend fun create(): HttpClient

    /**
     * Returns a client that always routes over Tor, regardless of the user's Tor preference. Used for
     * documented feature-specific exceptions, such as the CMC conversion feed (MOB-1378).
     */
    suspend fun createTor(): HttpClient

    suspend fun supportsKtorTimeouts(): Boolean = true
}

/** Carries a Tor-only requirement and optional ownership check to a scoped provider request. */
internal class TorOnlyRequest(val canRequest: () -> Boolean = { true }) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<TorOnlyRequest>
}

class HttpClientProviderImpl(
    private val synchronizerProvider: SynchronizerProvider,
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val transportGate: WalletSdkTransportGate? = null,
) : HttpClientProvider {
    override suspend fun create(): HttpClient =
        if (requiresTor()) createTor() else createDirect(currentCoroutineContext()[SwapStatusRequestAdmission.Key])

    override suspend fun supportsKtorTimeouts(): Boolean = !requiresTor()

    private suspend fun requiresTor() =
        currentCoroutineContext()[TorOnlyRequest.Key] != null || isTorEnabledStorageProvider.get() == true

    override suspend fun createTor(): HttpClient {
        transportGate?.checkTransportAvailable()
        val guardedStatus = currentCoroutineContext()[SwapStatusRequestAdmission.Key] != null
        return synchronizerProvider.getSynchronizer().getTorHttpClient {
            configureHttpClient(installTimeouts = false)
            if (guardedStatus) followRedirects = false
        }
    }

    private fun createDirect(admission: SwapStatusRequestAdmission?) =
        HttpClient(OkHttp) {
            configureHttpClient(installTimeouts = true)
            if (admission != null) followRedirects = false
            engine {
                if (admission != null) {
                    config {
                        retryOnConnectionFailure(false)
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                    addNetworkInterceptor { chain ->
                        if (!admission.beginDirectAttempt()) {
                            throw IOException("Swap status request admission is closed")
                        }
                        chain.proceed(chain.request())
                    }
                }
                // MOB-1378: Currency Conversion exchange rates must only ever be fetched over Tor (the
                // in-app copy promises rates are fetched over Tor to protect the user's IP). CMCApiProvider
                // already routes through createTor(), so this clearnet client should never see a CMC
                // request — this interceptor is a backstop that fails loudly if a future caller regresses
                // to create(), rather than silently leaking the user's IP / request timing.
                addInterceptor { chain ->
                    if (admission?.permits?.invoke() == false) {
                        throw IOException("Swap status request is no longer active")
                    }
                    transportGate?.requireDirectHttpAllowed()
                    if (chain
                            .request()
                            .url.host
                            .isExchangeRateHost()
                    ) {
                        throw ClearnetExchangeRateBlockedError()
                    }
                    chain.proceed(chain.request())
                }
            }
            // Status polling and lookup retries re-enter through the wallet admission check.
            if (admission == null) installStandardRetries()
        }

    @Suppress("MagicNumber")
    private fun HttpClientConfig<*>.installStandardRetries() {
        install(HttpRequestRetry) {
            maxRetries = MAX_RETRIES
            retryIf { request, response ->
                !request.url.toString().isVotingHelperPath() &&
                    !request.url.host.isExchangeRateHost() &&
                    response.status.value in 500..599
            }
            retryOnExceptionIf { request, failure ->
                failure !is DirectHttpTransportUnavailable && !request.url.toString().isVotingHelperPath() &&
                    !request.url.host.isExchangeRateHost()
            }
            exponentialDelay()
        }
    }

    private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureHttpClient(
        installTimeouts: Boolean
    ) {
        install(ContentNegotiation) { json() }
        if (installTimeouts) {
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MILLIS
            }
        }
        install(Logging) {
            logger = KtorLogger()
            // Bodies and URL parameters carry wallet data in debug builds too. Quote lifecycle
            // diagnostics record bounded stage metadata without exposing payloads or URLs.
            level = LogLevel.NONE
            sanitizeHeader { header -> header in SANITIZED_HEADERS }
        }
        expectSuccess = true
    }
}

private class KtorLogger : Logger {
    override fun log(message: String) {
        message.chunked(MAX_LOG_CHUNK).forEach { Log.d("HttpClient", it) }
    }

    private companion object {
        const val MAX_LOG_CHUNK = 3900
    }
}

private fun String.isVotingHelperPath(): Boolean =
    contains("/shielded-vote/v1/shares") ||
        contains("/shielded-vote/v1/share-status/")

// MOB-1378: the CMC quote API is the exchange-rate provider; requests to it must never leave over the
// direct (clearnet) client. Matched on the host (not a substring of the full URL) so a path or query
// that happens to echo the host can't trigger a false match. The host literal lives in CMCApiProvider.
private fun String.isExchangeRateHost(): Boolean = this == CMC_API_HOST

/**
 * MOB-1378: raised by the direct (clearnet) client's backstop interceptor when an exchange-rate request
 * would leave over clearnet. This is an unrecoverable invariant violation — a caller regressing from
 * createTor() to create() for the CMC host — not a network condition to recover from. Extends
 * [AssertionError] (an `Error`, not an `Exception`) so it is NOT swallowed by the `catch (Exception)`
 * fallbacks downstream (e.g. ExchangeRateRepository) and instead crashes the app loudly. OkHttp's async
 * dispatch rethrows non-IOException throwables on its dispatcher thread, so this propagates to the
 * uncaught-exception handler rather than corrupting the call.
 */
class ClearnetExchangeRateBlockedError :
    AssertionError(
        "Exchange rate fetching over clearnet is not allowed while Tor is disabled"
    )

private const val MAX_RETRIES = 4
private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L

// Credential-bearing headers redacted from logs even in debug. The shared client also serves the
// CMC quote API (X-CMC_PRO_API_KEY) and the voting helper (X-Helper-Token).
private val SANITIZED_HEADERS = setOf(HttpHeaders.Authorization, "X-CMC_PRO_API_KEY", "X-Helper-Token")
