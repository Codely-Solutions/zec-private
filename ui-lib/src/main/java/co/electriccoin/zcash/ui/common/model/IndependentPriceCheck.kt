package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.math.MathContext

sealed interface IndependentPriceCheck {
    data object Checking : IndependentPriceCheck
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
    private data class NativeMarket(val symbol: String, val decimals: Int, val pair: String)
    private val markets = mapOf(
        "nep141:zec.omft.near" to NativeMarket("zec", 8, "ZECUSD"),
        "nep141:btc.omft.near" to NativeMarket("btc", 8, "XBTUSD"),
        "nep141:eth.omft.near" to NativeMarket("eth", 18, "ETHUSD"),
        "nep141:sol.omft.near" to NativeMarket("sol", 9, "SOLUSD")
    )

    fun market(asset: SwapAsset): String? = markets[asset.assetId]?.takeIf {
        asset.tokenTicker.equals(it.symbol, ignoreCase = true) &&
            asset.chainTicker.equals(it.symbol, ignoreCase = true) && asset.decimals == it.decimals
    }?.pair

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
        if (kotlin.math.abs(input.observedAtMillis - output.observedAtMillis) > 30_000L ||
            quote.amountInFormatted.signum() <= 0 || quote.amountOutFormatted.signum() <= 0 ||
            networkFeeZec.signum() < 0 || quote.timestamp.toEpochMilliseconds() > nowMillis + 5_000L
        ) return IndependentPriceCheck.Unavailable
        val validUntil = minOf(
            quote.deadline.toEpochMilliseconds(),
            quote.timestamp.toEpochMilliseconds() + 180_000L,
            selected.minOf { it.observedAtMillis + 120_000L },
            selected.minOf { it.receivedAtMillis + 60_000L }
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

    private fun valid(observation: MarketObservation, now: Long): Boolean = with(observation) {
        bid.signum() > 0 && ask >= bid &&
            ask.subtract(bid).divide(bid, MathContext.DECIMAL128) <= BigDecimal("0.02") &&
            observedAtMillis <= now + 5_000L && observedAtMillis > now - 120_000L &&
            receivedAtMillis <= now + 5_000L && receivedAtMillis > now - 60_000L
    }
}
