package co.electriccoin.zcash.ui.screen.swap.quote

import java.math.BigDecimal
import java.math.MathContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Quote-derived valuation, not an independent market-price assessment. */
internal fun quoteValueDifference(
    totalSpendUsd: BigDecimal,
    receivedUsd: BigDecimal,
    quotedAt: Instant,
    deadline: Instant,
    now: Instant,
): BigDecimal? {
    val positiveValues = totalSpendUsd.signum() > 0 && receivedUsd.signum() > 0
    val currentQuote = quotedAt <= now && now < deadline && now - quotedAt < 3.minutes
    if (!positiveValues || !currentQuote
    ) {
        return null
    }
    return receivedUsd.divide(totalSpendUsd, MathContext.DECIMAL128)
        .subtract(BigDecimal.ONE)
        .multiply(BigDecimal("100"))
}
