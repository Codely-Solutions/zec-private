package co.electriccoin.zcash.ui.common.model

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class IndependentPricePolicyTest {
    private val now = 1_800_000_000_000L
    private fun asset(symbol: String, assetDecimals: Int) = mockk<SwapAsset> {
        every { assetId } returns "nep141:$symbol.omft.near"
        every { tokenTicker } returns symbol.uppercase()
        every { chainTicker } returns symbol
        every { decimals } returns assetDecimals
    }
    private val zec = asset("zec", 8)
    private val btc = asset("btc", 8)
    private fun quote() = mockk<SwapQuote> {
        every { originAsset } returns zec
        every { destinationAsset } returns btc
        every { amountInFormatted } returns BigDecimal("100")
        every { amountOutFormatted } returns BigDecimal("0.085")
        every { timestamp } returns Instant.fromEpochMilliseconds(now)
        every { deadline } returns Instant.fromEpochMilliseconds(now + 90_000)
    }
    private fun observations() = mapOf(
        "ZECUSD" to MarketObservation("ZECUSD", BigDecimal("100"), BigDecimal("101"), now, now),
        "XBTUSD" to MarketObservation("XBTUSD", BigDecimal("99000"), BigDecimal("100000"), now, now)
    )

    @Test
    fun exactNativeIdentityRequired() {
        assertEquals("ZECUSD", IndependentPricePolicy.market(zec))
        assertEquals("XBTUSD", IndependentPricePolicy.market(btc))
        assertEquals("ETHUSD", IndependentPricePolicy.market(asset("eth", 18)))
        assertEquals("SOLUSD", IndependentPricePolicy.market(asset("sol", 9)))
        every { zec.assetId } returns "nep141:wrapped-zec.near"
        assertNull(IndependentPricePolicy.market(zec))
        assertNull(IndependentPricePolicy.market(asset("eth", 8)))
        every { btc.chainTicker } returns "eth"
        assertNull(IndependentPricePolicy.market(btc))
        every { zec.assetId } returns "unknown"
        assertEquals(
            IndependentPriceCheck.Unavailable,
            IndependentPricePolicy.compare(quote(), BigDecimal.ZERO, observations(), now)
        )
    }

    @Test
    fun conservativeComparisonIncludesKnownOutgoingFee() {
        val result =
            IndependentPricePolicy.compare(quote(), BigDecimal.ZERO, observations(), now)
                as IndependentPriceCheck.Compared
        assertEquals(0, result.differencePercent.compareTo(BigDecimal("-15")))
        assertTrue(result.poor)
        assertEquals(now + 60_000, result.validUntilMillis)
        val feeResult =
            IndependentPricePolicy.compare(quote(), BigDecimal.ONE, observations(), now)
                as IndependentPriceCheck.Compared
        assertTrue(feeResult.differencePercent < result.differencePercent)
        assertFalse(IndependentPriceCheck.Compared(BigDecimal("-14.999"), now).poor)
    }

    @Test
    fun exactOutputAllowanceIsAlreadyIncludedInFormattedInput() {
        val quote = quote()
        every { quote.inputAllowance } returns BigDecimal("10")
        val result = IndependentPricePolicy.compare(quote, BigDecimal.ZERO, observations(), now)
            as IndependentPriceCheck.Compared
        assertEquals(0, result.differencePercent.compareTo(BigDecimal("-15")))
        verify(exactly = 0) { quote.inputAllowance }
    }

    @Test
    fun incomingZecFeeIsIgnored() {
        val quote = quote()
        every { quote.originAsset } returns btc
        every { quote.destinationAsset } returns zec
        every { quote.amountInFormatted } returns BigDecimal("0.1")
        every { quote.amountOutFormatted } returns BigDecimal("100")
        val withoutFee = IndependentPricePolicy.compare(quote, BigDecimal.ZERO, observations(), now)
        val withFee = IndependentPricePolicy.compare(quote, BigDecimal("999"), observations(), now)
        assertEquals(withoutFee, withFee)
        assertTrue(withFee is IndependentPriceCheck.Compared)
    }

    @Test
    fun staleFutureWideAndUnsynchronizedBooksUnavailable() {
        val books = observations()
        val input = books.getValue("ZECUSD")
        listOf(
            input.copy(observedAtMillis = now - 120_000),
            input.copy(receivedAtMillis = now - 60_000),
            input.copy(observedAtMillis = now + 5_001),
            input.copy(observedAtMillis = now - 30_001),
            input.copy(ask = BigDecimal("103")),
            input.copy(bid = BigDecimal.ZERO)
        ).forEach { invalid ->
            assertEquals(
                IndependentPriceCheck.Unavailable,
                IndependentPricePolicy.compare(quote(), BigDecimal.ZERO, books + ("ZECUSD" to invalid), now)
            )
        }
    }

    @Test
    fun quoteExpiryAndNonpositiveAmountsUnavailable() {
        val quote = quote()
        every { quote.deadline } returns Instant.fromEpochMilliseconds(now)
        assertEquals(
            IndependentPriceCheck.Unavailable,
            IndependentPricePolicy.compare(quote, BigDecimal.ZERO, observations(), now)
        )
        every { quote.deadline } returns Instant.fromEpochMilliseconds(now + 60_000)
        every { quote.amountOutFormatted } returns BigDecimal.ZERO
        assertEquals(
            IndependentPriceCheck.Unavailable,
            IndependentPricePolicy.compare(quote, BigDecimal.ZERO, observations(), now)
        )
    }
}
