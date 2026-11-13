package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.MarketObservation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun krakenResponseKey(pair: String): String =
    when (pair) {
        "XBTUSD" -> "XXBTZUSD"
        "ZECUSD" -> "XZECZUSD"
        "ETHUSD" -> "XETHZUSD"
        "LTCUSD" -> "XLTCZUSD"
        "XRPUSD" -> "XXRPZUSD"
        "USDTUSD" -> "USDTZUSD"
        "SOLUSD", "WBTCUSD", "TRXUSD", "BNBUSD", "AVAXUSD", "DASHUSD", "BCHUSD", "DAIUSD", "USDCUSD", "NEARUSD" -> pair
        else -> error("Unsupported market")
    }

internal fun parseKrakenObservation(pair: String, body: String, receivedAtMillis: Long): MarketObservation {
    require(body.length <= KRAKEN_RESPONSE_BYTES && body.toByteArray(Charsets.UTF_8).size <= KRAKEN_RESPONSE_BYTES)
    return parseKrakenSpread(pair, Json.parseToJsonElement(body).jsonObject, receivedAtMillis)
}

private fun krakenResult(root: JsonObject): JsonObject {
    val errors = root.getValue("error").jsonArray
    val rateLimited = errors.any {
        val error = it.jsonPrimitive.content
        error.startsWith("EAPI:Rate limit") || error.startsWith("EService:Throttled")
    }
    if (rateLimited) throw MarketDataFailure("rate limited")
    require(errors.isEmpty())
    return root.getValue("result").jsonObject
}

internal fun parseKrakenSpread(pair: String, root: JsonObject, receivedAtMillis: Long): MarketObservation {
    val key = krakenResponseKey(pair)
    val result = krakenResult(root)
    require(result.keys == setOf(key, "last"))
    val rows = result.getValue(key).jsonArray
    require(rows.isNotEmpty() && rows.size <= MAX_SPREAD_ROWS)
    return rows.reversed().map { element ->
        val row = element.jsonArray
        require(row.size == SPREAD_ROW_FIELDS)
        val observedAtMillis = marketTime(row[0].jsonPrimitive.content)
        val bid = marketDecimal(row[1].jsonPrimitive.content)
        val ask = marketDecimal(row[2].jsonPrimitive.content)
        require(ask >= bid)
        MarketObservation(pair, bid, ask, observedAtMillis, receivedAtMillis)
    }.maxBy { it.observedAtMillis }
}

internal fun parseKrakenDepth(pair: String, root: JsonObject, receivedAtMillis: Long): MarketObservation {
    val key = krakenResponseKey(pair)
    val result = krakenResult(root)
    require(result.keys == setOf(key))
    val book = result.getValue(key).jsonObject
    val bid = book.getValue("bids").jsonArray.single().jsonArray
    val ask = book.getValue("asks").jsonArray.single().jsonArray
    require(bid.size == SPREAD_ROW_FIELDS && ask.size == SPREAD_ROW_FIELDS)
    val bidTime = marketTime(bid[2].jsonPrimitive.content)
    val askTime = marketTime(ask[2].jsonPrimitive.content)
    require(maxOf(bidTime, askTime) <= receivedAtMillis + FUTURE_TOLERANCE_MILLIS)
    return MarketObservation(
        pair, marketDecimal(bid[0].jsonPrimitive.content), marketDecimal(ask[0].jsonPrimitive.content),
        minOf(bidTime, askTime), receivedAtMillis,
    )
}

private fun marketTime(seconds: String): Long =
    marketDecimal(seconds).movePointRight(MILLIS_DECIMALS).longValueExact()

internal const val KRAKEN_RESPONSE_BYTES = 65_536
private const val MILLIS_DECIMALS = 3
private const val FUTURE_TOLERANCE_MILLIS = 5_000L
private const val MAX_SPREAD_ROWS = 1_000
private const val SPREAD_ROW_FIELDS = 3
