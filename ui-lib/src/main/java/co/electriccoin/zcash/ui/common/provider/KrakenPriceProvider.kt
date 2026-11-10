package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.boundedMarketNumber
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.IndependentPricePolicy
import co.electriccoin.zcash.ui.common.model.MarketObservation
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
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

class KrakenPriceProvider(
    private val httpClientProvider: HttpClientProvider,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val elapsedTimeMillis: () -> Long = GetMonotonicTimeProvider()::invoke,
    private val requestDelay: suspend (Long) -> Unit = { delay(it) },
) : IndependentPriceProvider {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, CachedObservation>()
    @Volatile
    private var failureAtMillis: Long? = null
    private var lastRequestStartedMillis: Long? = null

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
                    val now = wallTimeMillis()
                    val elapsedNow = elapsedTimeMillis()
                    if (now < 0L || elapsedNow < 0L || failureAtMillis?.let {
                        elapsedNow - it < FAILURE_BACKOFF_MILLIS
                    } == true) return@withLock IndependentPriceCheck.Unavailable
                    val missing = pairs.filter { pair ->
                        cache[pair]?.let {
                            elapsedNow - it.receivedAtElapsedMillis in 0 until RECEIPT_LIFETIME_MILLIS &&
                                now - it.observation.observedAtMillis in
                                -FUTURE_TOLERANCE_MILLIS until OBSERVATION_LIFETIME_MILLIS
                        } != true
                    }
                    if (missing.isNotEmpty()) {
                        httpClientProvider.createTor().use { client ->
                            missing.forEach { pair ->
                                awaitRequestInterval()
                                val observation = client.prepareGet("https://api.kraken.com/0/public/Spread") {
                                    expectSuccess = false
                                    url { parameters.append("pair", pair) }
                                }.execute { response ->
                                    check(response.status.value == HttpStatusCode.OK.value)
                                    val bytes = response.bodyAsChannel()
                                        .readBuffer(MAX_RESPONSE_BYTES.toLong() + 1L).readByteArray()
                                    require(bytes.size <= MAX_RESPONSE_BYTES)
                                    parseKrakenObservation(pair, bytes.decodeToString(), wallTimeMillis())
                                }
                                cache[pair] = CachedObservation(observation, elapsedTimeMillis())
                            }
                        }
                    }
                    compareCached(pairs, quote, networkFeeZec)
                }
            }
        } catch (_: TimeoutCancellationException) {
            failureAtMillis = elapsedTimeMillis()
            IndependentPriceCheck.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failureAtMillis = elapsedTimeMillis()
            IndependentPriceCheck.Unavailable
        }
    }

    private suspend fun awaitRequestInterval() {
        val elapsed = elapsedTimeMillis()
        val sinceRequest = lastRequestStartedMillis?.let { elapsed - it }
        require(elapsed >= 0L && (sinceRequest == null || sinceRequest >= 0L))
        requestDelay(
            (REQUEST_INTERVAL_MILLIS - (sinceRequest ?: REQUEST_INTERVAL_MILLIS)).coerceAtLeast(0L)
        )
        lastRequestStartedMillis = elapsedTimeMillis()
    }

    private fun compareCached(
        pairs: List<String>,
        quote: SwapQuote,
        networkFeeZec: BigDecimal
    ): IndependentPriceCheck {
        val comparisonTime = wallTimeMillis()
        val comparisonElapsed = elapsedTimeMillis()
        val observations = pairs.associateWith { pair ->
            val cached = cache.getValue(pair)
            val age = comparisonElapsed - cached.receivedAtElapsedMillis
            if (comparisonTime < 0L || comparisonElapsed < 0L || age !in 0 until RECEIPT_LIFETIME_MILLIS) {
                return IndependentPriceCheck.Unavailable
            }
            // The policy uses absolute expiry timestamps. Preserve the local receipt's elapsed age
            // while leaving the provider observation and quote timestamps unchanged.
            cached.observation.copy(receivedAtMillis = comparisonTime - age)
        }
        return IndependentPricePolicy.compare(quote, networkFeeZec, observations, comparisonTime)
    }

    private data class CachedObservation(val observation: MarketObservation, val receivedAtElapsedMillis: Long)
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
