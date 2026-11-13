package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.MarketObservation
import co.electriccoin.zcash.ui.common.model.boundedMarketNumber
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

internal suspend fun HttpClient.readMarketJson(
    url: String,
    maxBytes: Int,
    configure: HttpRequestBuilder.() -> Unit = {}
): JsonObject = readMarketBody(url, maxBytes, configure).jsonObject

internal suspend fun HttpClient.readMarketBody(
    url: String,
    maxBytes: Int,
    configure: HttpRequestBuilder.() -> Unit
): JsonElement = prepareRequest(url) {
    expectSuccess = false
    configure()
}.execute { response ->
    when (response.status) {
        HttpStatusCode.OK -> Unit
        HttpStatusCode.TooManyRequests -> throw MarketDataFailure("rate limited")
        else -> throw MarketDataFailure("HTTP ${response.status.value}")
    }
    val bytes = response.bodyAsChannel().readBuffer(maxBytes.toLong() + 1L).readByteArray()
    require(bytes.size <= maxBytes)
    Json.parseToJsonElement(bytes.decodeToString())
}

internal fun marketDecimal(value: String): BigDecimal = value.toBigDecimal().also {
    require(boundedMarketNumber(it) && it.signum() > 0)
}

internal fun JsonObject.field(name: String): String = getValue(name).jsonPrimitive.content

internal fun MarketObservation.inDollars(anchor: MarketObservation): MarketObservation = copy(
    bid = (bid * anchor.bid).setScale(USD_SCALE, RoundingMode.DOWN).stripTrailingZeros(),
    ask = (ask * anchor.ask).setScale(USD_SCALE, RoundingMode.UP).stripTrailingZeros(),
    observedAtMillis = minOf(observedAtMillis, anchor.observedAtMillis),
    receivedAtMillis = minOf(receivedAtMillis, anchor.receivedAtMillis),
    sources = (sources + anchor.sources).distinct(),
)

private const val USD_SCALE = 18
