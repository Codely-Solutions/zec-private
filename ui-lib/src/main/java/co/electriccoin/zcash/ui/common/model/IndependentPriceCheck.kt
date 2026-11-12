package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.math.MathContext

sealed interface IndependentPriceCheck {
    data object Checking : IndependentPriceCheck
    data object NoMarketReference : IndependentPriceCheck
    data object Unavailable : IndependentPriceCheck
    data class Compared(val differencePercent: BigDecimal, val validUntilMillis: Long) : IndependentPriceCheck {
        val poor: Boolean get() = differencePercent <= BigDecimal("-15")
    }
}

data class MarketObservation(
    val pair: String,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val observedAtMillis: Long,
    val receivedAtMillis: Long
)

object IndependentPricePolicy {
    private const val ZEC_DECIMALS = 8
    private const val BTC_DECIMALS = 8
    private const val ETH_DECIMALS = 18
    private const val SOL_DECIMALS = 9
    private const val MAX_BOOK_SKEW_MILLIS = 30_000L
    private const val FUTURE_TOLERANCE_MILLIS = 5_000L
    private const val QUOTE_LIFETIME_MILLIS = 180_000L
    private const val OBSERVATION_LIFETIME_MILLIS = 120_000L
    private const val RECEIPT_LIFETIME_MILLIS = 60_000L

    private data class Market(val symbol: String, val network: String, val decimals: Int, val pair: String)

    // Resolve exact identities from the swap catalog to this source's markets.
    @Suppress("MagicNumber")
    private val markets = mapOf(
        "nep141:zec.omft.near" to Market("zec", "zec", ZEC_DECIMALS, "ZECUSD"),
        "nep141:btc.omft.near" to Market("btc", "btc", BTC_DECIMALS, "XBTUSD"),
        "nep141:eth.omft.near" to Market("eth", "eth", ETH_DECIMALS, "ETHUSD"),
        "nep141:sol.omft.near" to Market("sol", "sol", SOL_DECIMALS, "SOLUSD"),
        "nep141:eth-0x2260fac5e5542a773aa44fbcfedf7c193bc2c599.omft.near" to
            Market("wbtc", "eth", BTC_DECIMALS, "WBTCUSD"),
        "nep141:ltc.omft.near" to Market("ltc", "ltc", 8, "LTCUSD"),
        "nep141:tron.omft.near" to Market("trx", "tron", 6, "TRXUSD"),
        "nep141:bnb.omdep.near" to Market("bnb", "bsc", 18, "BNBUSD"),
        "nep141:avax.omdep.near" to Market("avax", "avax", 18, "AVAXUSD"),
        "nep141:xrp.omft.near" to Market("xrp", "xrp", 6, "XRPUSD"),
        "nep141:dash.omft.near" to Market("dash", "dash", 8, "DASHUSD"),
        "nep141:bch.omft.near" to Market("bch", "bch", 8, "BCHUSD"),
    )

    fun market(asset: SwapAsset): String? = markets[asset.assetId]?.takeIf {
        asset.tokenTicker.equals(it.symbol, ignoreCase = true) &&
            asset.chainTicker.equals(it.network, ignoreCase = true) && asset.decimals == it.decimals
    }?.pair

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
        if (input.pair != inputPair || output.pair != outputPair || selected.any { !valid(it, nowMillis) }) {
            return IndependentPriceCheck.Unavailable
        }
        if (!comparableQuote(quote, networkFeeZec, nowMillis) ||
            kotlin.math.abs(input.observedAtMillis - output.observedAtMillis) > MAX_BOOK_SKEW_MILLIS
        ) return IndependentPriceCheck.Unavailable
        val validUntil = minOf(
            quote.deadline.toEpochMilliseconds(),
            quote.timestamp.toEpochMilliseconds() + QUOTE_LIFETIME_MILLIS,
            selected.minOf { it.observedAtMillis + OBSERVATION_LIFETIME_MILLIS },
            selected.minOf { it.receivedAtMillis + RECEIPT_LIFETIME_MILLIS }
        )
        if (validUntil <= nowMillis) return IndependentPriceCheck.Unavailable
        val inputAmount = quote.amountInFormatted + if (inputPair == "ZECUSD") networkFeeZec else BigDecimal.ZERO
        val inputValue = inputAmount.multiply(input.bid)
        val outputValue = quote.amountOutFormatted.multiply(output.ask)
        return IndependentPriceCheck.Compared(
            outputValue.divide(inputValue, MathContext.DECIMAL128).subtract(BigDecimal.ONE).multiply(BigDecimal("100")),
            validUntil
        )
    }

    private fun comparableQuote(quote: SwapQuote, networkFeeZec: BigDecimal, nowMillis: Long): Boolean =
        positiveQuoteAmounts(quote) && networkFeeZec.signum() >= 0 &&
            quote.timestamp.toEpochMilliseconds() <= nowMillis + FUTURE_TOLERANCE_MILLIS

    private fun positiveQuoteAmounts(quote: SwapQuote): Boolean =
        quote.amountInFormatted.signum() > 0 && quote.amountOutFormatted.signum() > 0

    private fun valid(observation: MarketObservation, now: Long): Boolean = with(observation) {
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
