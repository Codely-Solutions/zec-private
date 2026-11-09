package co.electriccoin.zcash.ui.screen.swap.quote

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class QuoteValueComparisonTest {
    private val quoted = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun includesKnownNetworkFeeOnce() {
        assertEquals("-3.43", compare("10.20", "9.85")?.setScale(2, RoundingMode.HALF_UP)?.toPlainString())
        assertEquals("-1.50", compare("10", "9.85")?.setScale(2, RoundingMode.HALF_UP)?.toPlainString())
    }

    @Test
    fun supportsPositiveAndEqualValuations() {
        assertEquals(BigDecimal("10.0"), compare("10", "11"))
        assertEquals(BigDecimal("0"), compare("10", "10"))
    }

    @Test
    fun staleFutureExpiredAndInvalidValuesAreUnavailable() {
        assertNull(compare("0", "1"))
        assertNull(compare("-1", "1"))
        assertNull(compare("1", "0"))
        assertNull(compare("1", "-1"))
        assertNull(compare("1", "1", quoted + 3.minutes))
        assertNull(compare("1", "1", quoted - 1.seconds))
        assertNull(quoteValueDifference(BigDecimal.ONE, BigDecimal.ONE, quoted, quoted, quoted))
    }

    @Test
    fun calculationKeepsUnroundedAmounts() {
        assertEquals("-0.33333333", compare("3", "2.99")?.setScale(8, RoundingMode.HALF_UP)?.toPlainString())
    }

    private fun compare(input: String, output: String, now: Instant = quoted) =
        quoteValueDifference(BigDecimal(input), BigDecimal(output), quoted, quoted + 5.minutes, now)
}
