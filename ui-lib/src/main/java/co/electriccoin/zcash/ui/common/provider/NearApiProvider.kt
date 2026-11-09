package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias GetNearSupportedTokensResponse = List<NearTokenDto>

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
            diagnostics.trace(QuoteRequestStage.RESPONSE_DECODE) { response.body() }
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
            get("https://1click.chaindefuser.com/v0/status") {
                contentType(ContentType.Application.Json)
                parameter("depositAddress", depositAddress)
            }.body()
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
