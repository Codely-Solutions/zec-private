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
    fun tetherSpreadUsesTheKrakenResponseKey() {
        val body = """{"error":[],"result":{"USDTZUSD":[[100,"1.0000","1.0001"]],"last":100}}"""
        val result = parseKrakenObservation("USDTUSD", body, 101_000)
        assertEquals("USDTUSD", result.pair)
        assertEquals(BigDecimal("1.0001"), result.ask)
        assertFails { parseKrakenObservation("DAIUSD", body, 101_000) }
    }

    @Test
    fun missingMarketDoesNotOpenAClient() = runTest {
        val clients = mockk<HttpClientProvider>()
        val quote = supportedQuote()
        every { quote.destinationAsset.assetId } returns "unmapped"

        assertEquals(
            IndependentPriceCheck.NoMarketReference,
            KrakenPriceProvider(clients).check(quote, BigDecimal.ZERO)
        )
        coVerify(exactly = 0) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun wallClockJumpsDoNotChangeFailureBackoffExpiry() = runTest {
        var now = 10_000_000L
        var elapsed = 0L
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } throws IllegalStateException("unavailable")
        val provider = KrakenPriceProvider(clients, wallTimeMillis = { now }, elapsedTimeMillis = { elapsed })

        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        now += 3_600_000L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))

        now -= 7_200_000L
        elapsed = 59_999L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        coVerify(exactly = 1) { clients.create() }

        elapsed = 60_000L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))

        coVerify(exactly = 2) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun receiptCacheUsesElapsedAgeAcrossBackwardWallClockJump() = runTest {
        var wall = 10_000_000L
        var elapsed = 0L
        var calls = 0
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } coAnswers {
            observationClient {
                calls++
                wall
            }
        }
        val provider = KrakenPriceProvider(clients, { wall }, { elapsed })
        val quote = supportedQuote(wall)
        assertTrue(provider.check(quote, BigDecimal.ZERO) is IndependentPriceCheck.Compared)

        wall -= 1_000L
        elapsed = 1_000L
        assertTrue(provider.check(quote, BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        assertEquals(1, calls)

        elapsed = 60_000L
        assertTrue(provider.check(quote, BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        assertEquals(2, calls)
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun requestIntervalUsesElapsedTimeAcrossBothWallClockDirections() = runTest {
        var wall = 10_000_000L
        var elapsed = 0L
        val waits = mutableListOf<Long>()
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } coAnswers { observationClient { wall } }
        val provider = KrakenPriceProvider(clients, { wall }, { elapsed }, { wait ->
            waits += wait
            elapsed += wait
        })

        assertTrue(provider.check(supportedQuote(wall), BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        elapsed += 100L
        wall += 3_600_000L
        assertTrue(provider.check(supportedQuote(wall), BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        elapsed += 100L
        wall -= 7_200_000L
        assertTrue(provider.check(supportedQuote(wall), BigDecimal.ZERO) is IndependentPriceCheck.Compared)

        assertEquals(listOf(0L, 900L, 900L), waits)
        coVerify(exactly = 3) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun wallClockJumpsKeepAbsoluteObservationAndQuoteChecks() = runTest {
        var wall = 10_000_000L
        var elapsed = 0L
        val observedAt = wall
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } coAnswers { observationClient { observedAt } }
        val provider = KrakenPriceProvider(clients, { wall }, { elapsed }, { elapsed += it })
        val quote = supportedQuote(wall)
        assertTrue(provider.check(quote, BigDecimal.ZERO) is IndependentPriceCheck.Compared)

        wall += 180_001L
        elapsed += 1_000L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(quote, BigDecimal.ZERO))

        wall = observedAt - 5_001L
        elapsed += 1_000L
        assertEquals(IndependentPriceCheck.Unavailable, provider.check(quote, BigDecimal.ZERO))
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun cancelledRequestDoesNotStartFailureBackoff() = runTest {
        val wall = 10_000_000L
        var requests = 0
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } coAnswers {
            requests++
            if (requests == 1) throw CancellationException("cancelled")
            observationClient { wall }
        }
        val provider = KrakenPriceProvider(clients, { wall }, { 0L })
        try {
            provider.check(supportedQuote(wall), BigDecimal.ZERO)
            error("Expected cancellation")
        } catch (_: CancellationException) {
            // The next request remains eligible immediately.
        }
        assertTrue(provider.check(supportedQuote(wall), BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        assertEquals(2, requests)
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun freshObservationDoesNotMakeAnExpiredQuoteComparable() = runTest {
        val wall = 10_000_000L
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } coAnswers { observationClient { wall } }
        val provider = KrakenPriceProvider(clients, { wall }, { 0L })

        assertEquals(
            IndependentPriceCheck.Unavailable,
            provider.check(supportedQuote(wall - 180_001L), BigDecimal.ZERO)
        )
        coVerify(exactly = 0) { clients.createTor() }
    }

    private fun observationClient(observedAt: () -> Long) = HttpClient(MockEngine {
        val seconds = observedAt() / 1000L
        respond(
            """{"error":[],"result":{"XZECZUSD":[[$seconds,"100","101"]],"last":$seconds}}""",
            HttpStatusCode.OK
        )
    })

    private fun supportedQuote(now: Long = System.currentTimeMillis()): SwapQuote {
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
            every { timestamp } returns Instant.fromEpochMilliseconds(now)
            every { deadline } returns Instant.fromEpochMilliseconds(now + 180_000)
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
        coEvery { clients.create() } returns client
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertTrue(provider.check(supportedQuote(), BigDecimal.ZERO) is IndependentPriceCheck.Compared)
        }
        assertEquals(1, calls)
        coVerify(exactly = 1) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun malformedResponseTriggersBackoffWithoutSecondHttpRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"error":[],"result":{"unexpected":[]}}""", HttpStatusCode.OK)
        })
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } returns client
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        }
        assertEquals(1, calls)
        coVerify(exactly = 1) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun transportFailureBacksOffAndNeverCreatesDirectClient() = runTest {
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } throws IllegalStateException("unavailable")
        val provider = KrakenPriceProvider(clients)
        repeat(2) {
            assertEquals(IndependentPriceCheck.Unavailable, provider.check(supportedQuote(), BigDecimal.ZERO))
        }
        coVerify(exactly = 1) { clients.create() }
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun httpFailureIsUnavailableEvenWithFactorySuccessValidationEnabled() = runTest {
        val client = HttpClient(MockEngine { respond("error", HttpStatusCode.ServiceUnavailable) }) {
            expectSuccess = true
        }
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } returns client
        assertEquals(
            IndependentPriceCheck.Unavailable,
            KrakenPriceProvider(clients).check(supportedQuote(), BigDecimal.ZERO),
        )
        coVerify(exactly = 0) { clients.createTor() }
    }

    @Test
    fun parentCancellationPropagates() = runTest {
        val clients = mockk<HttpClientProvider>()
        coEvery { clients.create() } throws CancellationException("cancelled")
        var cancelled = false
        try {
            KrakenPriceProvider(clients).check(supportedQuote(), BigDecimal.ZERO)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        coVerify(exactly = 0) { clients.createTor() }
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
