package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.boundedMarketNumber
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.IndependentPricePolicy
import co.electriccoin.zcash.ui.common.model.MarketObservation
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

interface IndependentPriceProvider {
    suspend fun check(quote: SwapQuote, networkFeeZec: BigDecimal): IndependentPriceCheck
}

class KrakenPriceProvider(private val httpClientProvider: HttpClientProvider) : IndependentPriceProvider {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, MarketObservation>()
    @Volatile
    private var retryAfterMillis = 0L
    private var lastRequestStartedMillis = 0L

    override suspend fun check(
        quote: SwapQuote,
        networkFeeZec: BigDecimal
    ): IndependentPriceCheck = withContext(Dispatchers.IO) {
        val pairs = listOf(
            IndependentPricePolicy.market(quote.originAsset) ?: return@withContext IndependentPriceCheck.Unavailable,
            IndependentPricePolicy.market(quote.destinationAsset)
                ?: return@withContext IndependentPriceCheck.Unavailable
        ).distinct()
        try {
            withTimeout(CHECK_TIMEOUT_MILLIS) {
                mutex.withLock {
                    val now = System.currentTimeMillis()
                    if (now < retryAfterMillis) return@withLock IndependentPriceCheck.Unavailable
                    val missing = pairs.filter { pair ->
                        cache[pair]?.let {
                            now - it.receivedAtMillis in 0 until RECEIPT_LIFETIME_MILLIS &&
                                now - it.observedAtMillis in -FUTURE_TOLERANCE_MILLIS until OBSERVATION_LIFETIME_MILLIS
                        } != true
                    }
                    if (missing.isNotEmpty()) {
                        httpClientProvider.createTor().use { client ->
                            missing.forEach { pair ->
                                val waitMillis =
                                    lastRequestStartedMillis + REQUEST_INTERVAL_MILLIS - System.currentTimeMillis()
                                delay(waitMillis.coerceAtLeast(0L))
                                lastRequestStartedMillis = System.currentTimeMillis()
                                cache[pair] = client.prepareGet("https://api.kraken.com/0/public/Spread") {
                                    expectSuccess = false
                                    url { parameters.append("pair", pair) }
                                }.execute { response ->
                                    check(response.status.value == 200)
                                    val bytes = response.bodyAsChannel()
                                        .readBuffer(MAX_RESPONSE_BYTES.toLong() + 1L).readByteArray()
                                    require(bytes.size <= MAX_RESPONSE_BYTES)
                                    parseKrakenObservation(pair, bytes.decodeToString(), System.currentTimeMillis())
                                }
                            }
                        }
                    }
                    IndependentPricePolicy.compare(quote, networkFeeZec, cache, System.currentTimeMillis())
                }
            }
        } catch (_: TimeoutCancellationException) {
            retryAfterMillis = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS
            IndependentPriceCheck.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retryAfterMillis = System.currentTimeMillis() + FAILURE_BACKOFF_MILLIS
            IndependentPriceCheck.Unavailable
        }
    }
}

internal fun parseKrakenObservation(pair: String, body: String, receivedAtMillis: Long): MarketObservation {
    require(body.length <= MAX_RESPONSE_BYTES && body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
    val key = when (pair) {
        "XBTUSD" -> "XXBTZUSD"
        "ZECUSD" -> "XZECZUSD"
        "ETHUSD" -> "XETHZUSD"
        "SOLUSD" -> "SOLUSD"
        else -> error("Unsupported market")
    }
    val root = Json.parseToJsonElement(body).jsonObject
    require(root.getValue("error").jsonArray.isEmpty())
    val result = root.getValue("result").jsonObject
    require(result.keys == setOf(key, "last"))
    val rows = result.getValue(key).jsonArray
    require(rows.isNotEmpty() && rows.size <= MAX_SPREAD_ROWS)
    return rows.reversed().map { element ->
        val row = element.jsonArray
        require(row.size == SPREAD_ROW_FIELDS)
        val seconds = row[0].jsonPrimitive.content.toBigDecimal()
        val observedAtMillis = seconds.multiply(BigDecimal("1000")).longValueExact()
        val bid = row[1].jsonPrimitive.content.toBigDecimal()
        val ask = row[2].jsonPrimitive.content.toBigDecimal()
        require(boundedMarketNumber(bid) && boundedMarketNumber(ask))
        require(bid.signum() > 0 && ask >= bid)
        MarketObservation(pair, bid, ask, observedAtMillis, receivedAtMillis)
    }.maxBy { it.observedAtMillis }
}

private const val CHECK_TIMEOUT_MILLIS = 8_000L
private const val RECEIPT_LIFETIME_MILLIS = 60_000L
private const val FUTURE_TOLERANCE_MILLIS = 5_000L
private const val OBSERVATION_LIFETIME_MILLIS = 120_000L
private const val REQUEST_INTERVAL_MILLIS = 1_000L
private const val FAILURE_BACKOFF_MILLIS = 60_000L
private const val MAX_RESPONSE_BYTES = 65_536
private const val MAX_SPREAD_ROWS = 1_000
private const val SPREAD_ROW_FIELDS = 3
