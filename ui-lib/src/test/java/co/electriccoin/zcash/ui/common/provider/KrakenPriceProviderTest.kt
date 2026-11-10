package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Instant

class KrakenPriceProviderTest {
    @Test
    fun forwardWallClockJumpBypassesFailureBackoff() = runTest {
        var now = 1_000_000L
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } throws IllegalStateException("unavailable")
        val provider = KrakenPriceProvider(clients, wallTimeMillis = { now })

        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        now += 3_600_000L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))

        coVerify(exactly = 2) { clients.createTor() }
        coVerify(exactly = 0) { clients.create() }
    }

    private fun supportedQuote(): SwapQuote {
        val asset = mockk<SwapAsset> {
            every { assetId } returns "nep141:zec.omft.near"
            every { tokenTicker } returns "ZEC"
            every { chainTicker } returns "zec"
            every { decimals } returns 8
        }
        return mockk {
            every { originAsset } returns asset
            every { destinationAsset } returns asset
            every { amountInFormatted } returns BigDecimal.ONE
            every { amountOutFormatted } returns BigDecimal.ONE
            every { timestamp } returns Instant.fromEpochMilliseconds(System.currentTimeMillis())
            every { deadline } returns Instant.fromEpochMilliseconds(System.currentTimeMillis() + 180_000)
        }
    }

    @Test
    fun successfulObservationIsReusedWithoutSecondHttpRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("api.kraken.com", request.url.host)
            assertEquals("ZECUSD", request.url.parameters["pair"])
            val seconds = System.currentTimeMillis() / 1000
            respond(
                """{"error":[],"result":{"XZECZUSD":[[$seconds,"100","101"]],"last":$seconds}}""",
                HttpStatusCode.OK
            )
        })
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } returns client
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertTrue(provider.check(supportedQuote(), BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        }
        assertEquals(1, calls)
        coVerify(exactly = 1) { clients.createTor() }
        coVerify(exactly = 0) { clients.create() }
    }

    @Test
    fun malformedResponseTriggersBackoffWithoutSecondHttpRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"error":[],"result":{"unexpected":[]}}""", HttpStatusCode.OK)
        })
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } returns client
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        }
        assertEquals(1, calls)
        coVerify(exactly = 1) { clients.createTor() }
        coVerify(exactly = 0) { clients.create() }
    }

    @Test
    fun transportFailureBacksOffAndNeverCreatesDirectClient() = runTest {
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } throws IllegalStateException("unavailable")
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        }
        coVerify(exactly = 1) { clients.createTor() }
        coVerify(exactly = 0) { clients.create() }
    }

    @Test
    fun httpFailureIsUnavailableEvenWithFactorySuccessValidationEnabled() = runTest {
        val client = HttpClient(MockEngine { respond("error", HttpStatusCode.ServiceUnavailable) }) {
            expectSuccess = true
        }
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } returns client
        assertEquals(
            IndependentPriceCheck.Unavailable,
            KrakenPriceProvider(clients).check(supportedQuote(), BigDecimal.ZERO),
        )
        coVerify(exactly = 0) { clients.create() }
    }

    @Test
    fun parentCancellationPropagates() = runTest {
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.createTor() } throws CancellationException("cancelled")
        var cancelled = false
        try {
            KrakenPriceProvider(clients).check(supportedQuote(), BigDecimal.ZERO)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        coVerify(exactly = 0) { clients.create() }
    }

    @Test
    fun timestampComesFromNewestObservationAndNotCursor() {
        val body = """{"error":[],"result":{"XZECZUSD": [
            [100,"20","20.1"],[101,"21","21.1"],[101,"22","22.1"]],"last":999}}
        """.trimIndent()
        val result = parseKrakenObservation("ZECUSD", body, 102_000)
        assertEquals(101_000L, result.observedAtMillis)
        assertEquals(102_000L, result.receivedAtMillis)
        assertEquals(BigDecimal("22"), result.bid)
    }

    @Test
    fun rejectsErrorsUnexpectedMarketsEmptyAndInvalidPrices() {
        listOf(
            """{"error":["rate limited"],"result":{}}""",
            """{"error":[],"result":{"XXBTZUSD":[[100,"20","21"]],"last":100}}""",
            """{"error":[],"result":{"XZECZUSD":[],"last":100}}""",
            """{"error":[],"result":{"XZECZUSD":[[100,"0","21"]],"last":100}}""",
            """{"error":[],"result":{"XZECZUSD":[[100,"22","21"]],"last":100}}""",
            """{"error":[],"result":{"XZECZUSD":[[100,"1","1e2147483647"]],"last":100}}""",
            " ".repeat(65_537)
        ).forEach { body -> assertFails { parseKrakenObservation("ZECUSD", body, 100_000) } }
    }
}
