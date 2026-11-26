package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.model.near.boundedNearQuoteWire
import co.electriccoin.zcash.ui.common.model.near.syntheticSignedQuote
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [KtorNearApiProvider] drives the 1Click HTTP API. The contract these tests pin down: the three
 * swap endpoints and the public token list must omit Authorization, and a NEAR error body must be lifted into a typed
 * [ResponseWithNearErrorException] (falling back to the raw [ResponseException] otherwise).
 */
class KtorNearApiProviderTest {
    private val requests = mutableListOf<HttpRequestData>()

    private var responseStatus = HttpStatusCode.OK
    private var responseBody = "[]"
    private var responseContentType = "application/json"
    private var responseDate: String? = null
    private var responseAge: String? = null

    private var beforeClientCreated: suspend () -> Unit = {}
    private var createCalls = 0
    private var createTorCalls = 0

    private val diagnosticEvents = mutableListOf<String>()
    private val provider =
        KtorNearApiProvider(RecordingHttpClientProvider(), QuoteRequestDiagnostics(diagnosticEvents::add))

    @Test
    fun getSupportedTokensDoesNotSendAuthorizationHeader() =
        runBlocking {
            responseBody = "[]"

            provider.getSupportedTokens()

            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v0/tokens", request.url.encodedPath)
            // The token list is public — no credential must leak onto it.
            assertNull(request.headers[HttpHeaders.Authorization])
            assertRespectsTorPreference()
        }

    @Test
    fun requestQuoteDoesNotSendAuthorizationHeader() =
        runBlocking {
            // The QuoteResponseDto body intentionally fails to deserialize; we only assert the request.
            responseBody = "{}"
            runCatching { provider.requestQuote(quoteRequest()) }

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v0/quote", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("[]", body["appFees"].toString())
            assertNull(body["referral"])
            assertNoAuthorization(request)
            assertRespectsTorPreference()
        }

    @Test
    fun submitDepositTransactionDoesNotSendAuthorizationHeader() =
        runBlocking {
            provider.submitDepositTransaction(
                SubmitDepositTransactionRequest(txHash = "hash", depositAddress = "deposit")
            )

            val request = requests.single()
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v0/deposit/submit", request.url.encodedPath)
            assertNoAuthorization(request)
            assertRespectsTorPreference()
        }

    @Test
    fun checkSwapStatusDoesNotSendAuthorizationHeaderAndDepositAddressParameter() =
        runBlocking {
            responseBody = "{}"
            runCatching { provider.checkSwapStatus(depositAddress = "deposit-address") }

            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v0/status", request.url.encodedPath)
            assertEquals("deposit-address", request.url.parameters["depositAddress"])
            assertNoAuthorization(request)
            assertRespectsTorPreference()
        }

    @Test
    fun statusAdmissionIsRecheckedAfterClientAcquisitionWithoutChangingItsRoute() = runBlocking {
        val wallet = WalletWriteAccess()
        val token = wallet.token()
        beforeClientCreated = { wallet.beginReset() }

        val refused = assertFailsWith<IllegalStateException> {
            withContext(SwapStatusRequestAdmission { wallet.permits(token) }) {
                provider.checkSwapStatus("synthetic-deposit")
            }
        }
        assertEquals("Swap status request is no longer active", refused.message)
        assertTrue(requests.isEmpty())
        assertRespectsTorPreference()
        assertTrue(diagnosticEvents.any { it.startsWith("stage=CLIENT_CLOSE ") })
    }

    @Test
    fun quoteDecodeRejectsTheCompactOversizedOutputFromTheAdmissionProbe() =
        runBlocking {
            responseBody = boundedNearQuoteWire
            assertEquals(BigDecimal("100000000"), provider.requestQuote(quoteRequest()).quote.amountOut)

            responseBody = boundedNearQuoteWire
                .replace("\"amountOut\":\"100000000\"", "\"amountOut\":\"1E+10000\"")
                .replace("\"amountOutFormatted\":\"1\"", "\"amountOutFormatted\":\"1E+9992\"")
                .replace("\"minAmountOut\":\"99000000\"", "\"minAmountOut\":\"1E+10000\"")
            assertTrue(responseBody.length < 2_000)
            assertFailsWith<JsonConvertException> { provider.requestQuote(quoteRequest()) }
            assertEquals(2, diagnosticEvents.count { it.startsWith("stage=CLIENT_CLOSE ") })
        }

    @Test
    fun malformedOrUntrustedEvidenceDoesNotChangeAnOtherwiseValidQuote() = runBlocking {
        responseBody = syntheticSignedQuote
        val untrusted = provider.requestQuote(quoteRequest())
        assertNull(untrusted.signedEvidence)
        responseBody = syntheticSignedQuote.replace("\"signature\":\"ed25519:", "\"signature\":\"untrusted:")
        assertEquals(untrusted, provider.requestQuote(quoteRequest()))
    }

    @Test
    fun freshStatusCarriesProviderTimeAndCachedOrMissingDatesDoNot() = runBlocking {
        responseBody = """{
            "status":"INCOMPLETE_DEPOSIT","updatedAt":"2026-11-22T00:00:00Z","quoteResponse":$boundedNearQuoteWire
        }"""
        responseDate = "Tue, 24 Nov 2026 19:00:00 GMT"
        val fresh = provider.checkSwapStatus("synthetic-deposit")
        assertEquals(Instant.parse("2026-11-24T19:00:00Z"), fresh.observedAtProvider)
        assertEquals("no-cache", requests.last().headers[HttpHeaders.CacheControl])

        responseAge = "30"
        assertNull(provider.checkSwapStatus("synthetic-deposit").observedAtProvider)
        responseAge = null
        responseDate = null
        assertNull(provider.checkSwapStatus("synthetic-deposit").observedAtProvider)
    }

    @Test
    fun statusDecodeRejectsOversizedUsdAndKeepsNullableStatusAmounts() {
        runBlocking {
            responseBody = """{
                "status":"REFUNDED", "updatedAt":"2026-11-20T00:00:00Z", "quoteResponse":$boundedNearQuoteWire,
                "swapDetails":{"refundedAmount":"100000000", "refundedAmountUsd":"3.5", "depositedAmountUsd":null}
            }"""
            val decoded = provider.checkSwapStatus("synthetic-deposit")
            assertEquals(BigDecimal("3.5"), decoded.swapDetails?.refundedAmountUsd)
            assertNull(decoded.swapDetails?.depositedAmountUsd)

            responseBody = responseBody.replace("\"refundedAmountUsd\":\"3.5\"", "\"refundedAmountUsd\":\"1E-10000\"")
            assertFailsWith<JsonConvertException> { provider.checkSwapStatus("synthetic-deposit") }
        }
    }

    @Test
    fun tokenListDecodeRejectsOversizedUsdPrice() {
        runBlocking {
            responseBody = """[{"assetId":"asset","decimals":24,"blockchain":"near","symbol":"TOKEN","price":"2.79"}]"""
            assertEquals(BigDecimal("2.79"), provider.getSupportedTokens().single().price)

            responseBody = responseBody.replace("\"2.79\"", "\"1E+10000\"")
            assertFailsWith<JsonConvertException> { provider.getSupportedTokens() }
        }
    }

    @Test
    fun errorResponseWithNearErrorBodyMapsToTypedException() {
        runBlocking {
            responseStatus = HttpStatusCode.BadRequest
            responseBody = """{"message":"boom","timestamp":"t","path":"/v0/quote"}"""

            val exception =
                assertFailsWith<ResponseWithNearErrorException> {
                    provider.requestQuote(quoteRequest())
                }

            assertEquals("boom", exception.error.message)
            assertRespectsTorPreference()
            assertEquals(3, diagnosticEvents.size)
            assertTrue(diagnosticEvents[0].startsWith("stage=CLIENT_CREATE "))
            assertTrue(diagnosticEvents[1].startsWith("stage=HTTP_REQUEST "))
            assertTrue(diagnosticEvents[1].endsWith("status=400"))
            assertTrue(diagnosticEvents[2].startsWith("stage=CLIENT_CLOSE "))
            assertFalse(diagnosticEvents.any { "boom" in it || "recipient" in it || "refund" in it })
        }
    }

    @Test
    fun errorResponseWithoutNearErrorBodyRethrowsRawResponseException() {
        runBlocking {
            responseStatus = HttpStatusCode.BadRequest
            responseBody = "plain text, not a NEAR error"
            responseContentType = "text/plain"

            val exception =
                assertFailsWith<ResponseException> {
                    provider.requestQuote(quoteRequest())
                }

            // Unparsable body -> the raw transport exception is rethrown, not the typed one.
            assertFalse(exception is ResponseWithNearErrorException)
            assertRespectsTorPreference()
        }
    }

    private fun assertNoAuthorization(request: HttpRequestData) {
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    private fun quoteRequest() =
        QuoteRequest(
            dry = false,
            swapType = SwapType.EXACT_INPUT,
            slippageTolerance = 100,
            originAsset = "origin",
            destinationAsset = "destination",
            amount = BigDecimal.ONE,
            refundTo = "refund",
            recipient = "recipient",
            deadline = Instant.fromEpochMilliseconds(0),
            appFees = emptyList()
        )

    private fun assertRespectsTorPreference() {
        // create() routes over Tor or clearnet per the user's Tor setting; createTor() would force Tor
        // regardless. The swap API must defer to the preference, so it must use create() and never createTor().
        assertEquals(1, createCalls, "create() (honors the user's Tor preference) must be called exactly once")
        assertEquals(0, createTorCalls, "createTor() (forces Tor) must never be called by KtorNearApiProvider")
    }

    private inner class RecordingHttpClientProvider : HttpClientProvider {
        override suspend fun createTor(): HttpClient {
            createTorCalls++
            error("KtorNearApiProvider must use create() (honors the Tor preference), never createTor()")
        }

        override suspend fun create(): HttpClient {
            createCalls++
            beforeClientCreated()
            return HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        content = responseBody,
                        status = responseStatus,
                        headers = headersOf(*listOfNotNull(
                            HttpHeaders.ContentType to listOf(responseContentType),
                            responseDate?.let { HttpHeaders.Date to listOf(it) },
                            responseAge?.let { HttpHeaders.Age to listOf(it) },
                        ).toTypedArray())
                    )
                }
            ) {
                install(ContentNegotiation) { json() }
                // Mirrors the production client so non-2xx responses surface as ResponseExceptions.
                expectSuccess = true
            }
        }
    }
}
