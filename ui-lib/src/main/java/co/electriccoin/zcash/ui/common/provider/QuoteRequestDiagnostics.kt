package co.electriccoin.zcash.ui.common.provider

import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import io.ktor.client.plugins.ResponseException

enum class QuoteRequestStage {
    FRESH_ADDRESS,
    CLIENT_CREATE,
    HTTP_REQUEST,
    RESPONSE_DECODE,
    CLIENT_CLOSE,
    PROPOSAL
}

/** Records bounded lifecycle metadata only. Never pass request values or exception messages to the sink. */
class QuoteRequestDiagnostics(
    private val sink: (String) -> Unit = {
        if (BuildConfig.DEBUG) Log.i("SwapQuoteStage", it)
        Unit
    },
    private val nanoTime: () -> Long = System::nanoTime
) {
    suspend fun <T> trace(stage: QuoteRequestStage, block: suspend () -> T): T {
        val started = nanoTime()
        var failure: Throwable? = null
        try {
            return block()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            val elapsedMillis = ((nanoTime() - started) / NANOS_PER_MILLISECOND).coerceAtLeast(0)
            val status = (failure as? ResponseException)?.response?.status?.value
            // A diagnostic backend must never change quote success, failure or cancellation.
            runCatching {
                sink(
                    "stage=$stage elapsedMs=$elapsedMillis " +
                        "exception=${failure?.javaClass?.simpleName ?: "none"} status=${status ?: "none"}"
                )
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
