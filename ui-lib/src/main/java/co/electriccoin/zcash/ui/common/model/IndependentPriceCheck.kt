package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.math.MathContext

sealed interface IndependentPriceCheck {
    data object Checking : IndependentPriceCheck
    data object NoMarketReference : IndependentPriceCheck
    data object Unavailable : IndependentPriceCheck
    data class Compared(
        val differencePercent: BigDecimal,
        val validUntilMillis: Long,
        val sources: List<String> = listOf("Kraken"),
    ) : IndependentPriceCheck {
        val poor: Boolean get() = differencePercent <= BigDecimal("-15")
    }
}

data class MarketObservation(
    val pair: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val observedAtMillis: Long,
    val receivedAtMillis: Long,
    val sources: List<String> = listOf("Kraken"),
)

object IndependentPricePolicy {
    private const val MAX_BOOK_SKEW_MILLIS = 30_000L
    private const val MAX_POOL_SKEW_MILLIS = 120_000L
    private const val FUTURE_TOLERANCE_MILLIS = 5_000L
    private const val QUOTE_LIFETIME_MILLIS = 180_000L
    private const val OBSERVATION_LIFETIME_MILLIS = 120_000L
    private const val RECEIPT_LIFETIME_MILLIS = 60_000L

    fun market(asset: SwapAsset): String? = SwapPriceMarkets.reference(asset)

    // Each unavailable prerequisite ends the comparison before price arithmetic.
    @Suppress("ReturnCount")
    fun compare(
        quote: SwapQuote,
        networkFeeZec: BigDecimal,
        observations: Map<String, MarketObservation>,
        nowMillis: Long
    ): IndependentPriceCheck {
        val inputPair = market(quote.originAsset) ?: return IndependentPriceCheck.Unavailable
        val outputPair = market(quote.destinationAsset) ?: return IndependentPriceCheck.Unavailable
        val input = observations[inputPair] ?: return IndependentPriceCheck.Unavailable
        val output = observations[outputPair] ?: return IndependentPriceCheck.Unavailable
        val selected = listOf(input, output)
        if (input.pair != inputPair || output.pair != outputPair || selected.any { !isUsable(it, nowMillis) }) {
            return IndependentPriceCheck.Unavailable
        }
        val maxSkew = if (selected.any { it.sources.size > 1 }) MAX_POOL_SKEW_MILLIS else MAX_BOOK_SKEW_MILLIS
        if (!comparableQuote(quote, networkFeeZec, nowMillis) ||
            kotlin.math.abs(input.observedAtMillis - output.observedAtMillis) > maxSkew
        ) return IndependentPriceCheck.Unavailable
        val validUntil = minOf(
            quote.deadline.toEpochMilliseconds(),
            quote.timestamp.toEpochMilliseconds() + QUOTE_LIFETIME_MILLIS,
            selected.minOf { it.observedAtMillis + OBSERVATION_LIFETIME_MILLIS },
            selected.minOf { it.receivedAtMillis + RECEIPT_LIFETIME_MILLIS }
        )
        if (validUntil <= nowMillis) return IndependentPriceCheck.Unavailable
        val inputFee = if (quote.originAsset.isZCashAsset) networkFeeZec else BigDecimal.ZERO
        val inputAmount = quote.amountInFormatted + inputFee
        val inputValue = inputAmount.multiply(input.bid)
        val outputValue = quote.amountOutFormatted.multiply(output.ask)
        return IndependentPriceCheck.Compared(
            outputValue.divide(inputValue, MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal("100")),
            validUntil,
            selected.flatMap { it.sources }.distinct()
        )
    }

    private fun comparableQuote(quote: SwapQuote, networkFeeZec: BigDecimal, nowMillis: Long): Boolean =
        positiveQuoteAmounts(quote) && networkFeeZec.signum() >= 0 &&
            quote.timestamp.toEpochMilliseconds() <= nowMillis + FUTURE_TOLERANCE_MILLIS

    private fun positiveQuoteAmounts(quote: SwapQuote): Boolean =
        quote.amountInFormatted.signum() > 0 && quote.amountOutFormatted.signum() > 0

    internal fun isUsable(observation: MarketObservation, now: Long): Boolean = with(observation) {
        boundedMarketNumber(bid) && boundedMarketNumber(ask) && bid.signum() > 0 && ask >= bid &&
            ask.subtract(bid).divide(bid, MathContext.DECIMAL128) <= BigDecimal("0.02") &&
            observedAtMillis <= now + FUTURE_TOLERANCE_MILLIS &&
            observedAtMillis > now - OBSERVATION_LIFETIME_MILLIS &&
            receivedAtMillis <= now + FUTURE_TOLERANCE_MILLIS && receivedAtMillis > now - RECEIPT_LIFETIME_MILLIS
    }
}

/** Bound untrusted decimal exponents before arithmetic can expand them. Values remain exact. */
internal fun boundedMarketNumber(value: BigDecimal): Boolean =
    value.precision() <= MAX_MARKET_PRECISION && value.scale() in MIN_MARKET_SCALE..MAX_MARKET_SCALE

private const val MAX_MARKET_PRECISION = 32
private const val MIN_MARKET_SCALE = -12
private const val MAX_MARKET_SCALE = 18
