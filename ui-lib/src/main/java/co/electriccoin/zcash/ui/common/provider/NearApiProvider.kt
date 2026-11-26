package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.verifiedNearQuoteEvidence
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.toKotlinInstant
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

typealias GetNearSupportedTokensResponse = List<NearTokenDto>

internal class SwapStatusRequestAdmission(val permits: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<SwapStatusRequestAdmission>
}

interface NearApiProvider {
    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun getSupportedTokens(): GetNearSupportedTokensResponse

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun submitDepositTransaction(request: SubmitDepositTransactionRequest)

    @Throws(ResponseException::class, ResponseWithNearErrorException::class)
    suspend fun checkSwapStatus(depositAddress: String): SwapStatusResponseDto
}
class ResponseWithNearErrorException(
    val error: ErrorDto,
    override val cause: ResponseException
) : ResponseException(
        response = cause.response,
        cachedResponseText = "Code: ${cause.response.status}, message: ${error.message}"
    )

class KtorNearApiProvider(
    private val httpClientProvider: HttpClientProvider,
    private val diagnostics: QuoteRequestDiagnostics = QuoteRequestDiagnostics()
) : NearApiProvider {
    override suspend fun getSupportedTokens(): GetNearSupportedTokensResponse =
        execute {
            get("https://1click.chaindefuser.com/v0/tokens").body()
        }

    override suspend fun requestQuote(request: QuoteRequest): QuoteResponseDto =
        execute {
            val response = diagnostics.trace(QuoteRequestStage.HTTP_REQUEST) {
                post("https://1click.chaindefuser.com/v0/quote") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            }
            diagnostics.trace(QuoteRequestStage.RESPONSE_DECODE) {
                val quote = response.body<QuoteResponseDto>()
                quote.copy(
                    signedEvidence = verifiedNearQuoteEvidence(response.bodyAsText()),
                    observedAtProvider = response.freshProviderTime(),
                )
            }
        }

    override suspend fun submitDepositTransaction(request: SubmitDepositTransactionRequest) {
        execute {
            post("https://1click.chaindefuser.com/v0/deposit/submit") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }
    }

    override suspend fun checkSwapStatus(depositAddress: String): SwapStatusResponseDto =
        execute {
            val context = currentCoroutineContext()
            check(context[SwapStatusRequestAdmission.Key]?.permits?.invoke() != false &&
                context[TorOnlyRequest.Key]?.canRequest?.invoke() != false) {
                "Swap status request is no longer active"
            }
            val response = get("https://1click.chaindefuser.com/v0/status") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.CacheControl, "no-cache")
                parameter("depositAddress", depositAddress)
            }
            response.body<SwapStatusResponseDto>().copy(observedAtProvider = response.freshProviderTime())
        }

    // Cleanup preserves the primary failure; a close-only failure must still reach the caller.
    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally")
    @Throws(ResponseException::class)
    private suspend inline fun <T> execute(
        crossinline block: suspend HttpClient.() -> T
    ): T =
        withContext(Dispatchers.IO) {
            val client = diagnostics.trace(QuoteRequestStage.CLIENT_CREATE) { httpClientProvider.create() }
            var failure: Throwable? = null
            try {
                try {
                    block(client)
                } catch (e: ResponseException) {
                    val response = e.response
                    val error: ErrorDto? = runCatching { response.body<ErrorDto?>() }.getOrNull()
                    if (error != null) {
                        throw ResponseWithNearErrorException(error = error, cause = e)
                    } else {
                        throw e
                    }
                }
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                try {
                    diagnostics.trace(QuoteRequestStage.CLIENT_CLOSE) { client.close() }
                } catch (closeFailure: Throwable) {
                    val primaryFailure = failure ?: throw closeFailure
                    primaryFailure.addSuppressed(closeFailure)
                }
            }
        }
}

private fun HttpResponse.freshProviderTime(): kotlin.time.Instant? {
    val age = headers[HttpHeaders.Age]
    val date = headers[HttpHeaders.Date]
    if ((age != null && age.toLongOrNull() != 0L) || date == null) return null
    return runCatching {
        ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toKotlinInstant()
    }.getOrNull()
}
