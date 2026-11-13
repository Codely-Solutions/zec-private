package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.spackle.Twig
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

    // A failure in this optional reference must leave the provider quote usable.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun check(
        quote: SwapQuote,
        networkFeeZec: BigDecimal
    ): IndependentPriceCheck = withContext(Dispatchers.IO) {
        val pairs = listOf(
            IndependentPricePolicy.market(quote.originAsset)
                ?: return@withContext IndependentPriceCheck.NoMarketReference,
            IndependentPricePolicy.market(quote.destinationAsset)
                ?: return@withContext IndependentPriceCheck.NoMarketReference
        ).distinct()
        try {
            withTimeout(CHECK_TIMEOUT_MILLIS) {
                mutex.withLock {
                    val now = wallTimeMillis()
                    val elapsedNow = elapsedTimeMillis()
                    if (now < 0L || elapsedNow < 0L) return@withLock IndependentPriceCheck.Unavailable
                    val missing = pairs.filterNot { isCacheFresh(it, now, elapsedNow) }
                    if (missing.isNotEmpty()) {
                        if (failureAtMillis?.let { elapsedNow - it < FAILURE_BACKOFF_MILLIS } == true) {
                            return@withLock IndependentPriceCheck.Unavailable
                        }
                        refreshObservations(missing)
                    }
                    compareCached(pairs, quote, networkFeeZec)
                }
            }
        } catch (_: TimeoutCancellationException) {
            unavailable("timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MarketDataFailure) {
            unavailable(failure.message)
        } catch (failure: Exception) {
            unavailable("request failed (" + failure.javaClass.simpleName + ")")
        }
    }

    private suspend fun refreshObservations(missing: List<String>) {
        httpClientProvider.create().use { client ->
            missing.forEach { pair ->
                awaitRequestInterval()
                val observation = client.prepareGet("https://api.kraken.com/0/public/Spread") {
                    expectSuccess = false
                    url { parameters.append("pair", pair) }
                }.execute { response ->
                    val status = response.status
                    if (status != HttpStatusCode.OK) {
                        val reason = if (status == HttpStatusCode.TooManyRequests) {
                            "rate limited"
                        } else {
                            "HTTP " + status.value
                        }
                        throw MarketDataFailure(reason)
                    }
                    val bytes = response.bodyAsChannel().readBuffer(MAX_RESPONSE_BYTES.toLong() + 1L).readByteArray()
                    try {
                        require(bytes.size <= MAX_RESPONSE_BYTES)
                        parseKrakenObservation(pair, bytes.decodeToString(), wallTimeMillis())
                    } catch (_: RuntimeException) {
                        throw MarketDataFailure("rejected response")
                    }
                }
                cache[pair] = CachedObservation(observation, elapsedTimeMillis())
            }
        }
    }

    private fun unavailable(reason: String): IndependentPriceCheck {
        failureAtMillis = elapsedTimeMillis()
        Twig.warn { "Independent market check: " + reason }
        return IndependentPriceCheck.Unavailable
    }

    private fun isCacheFresh(pair: String, now: Long, elapsedNow: Long): Boolean =
        cache[pair]?.let {
            elapsedNow - it.receivedAtElapsedMillis in 0 until RECEIPT_LIFETIME_MILLIS &&
                now - it.observation.observedAtMillis in
                -FUTURE_TOLERANCE_MILLIS until OBSERVATION_LIFETIME_MILLIS
        } == true

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
        val result = IndependentPricePolicy.compare(quote, networkFeeZec, observations, comparisonTime)
        if (result == IndependentPriceCheck.Unavailable) {
            Twig.warn { "Independent market check: market data failed comparison checks" }
        }
        return result
    }

    private data class CachedObservation(val observation: MarketObservation, val receivedAtElapsedMillis: Long)
}

private fun spreadResponseKey(pair: String): String =
    when (pair) {
        "XBTUSD" -> "XXBTZUSD"
        "ZECUSD" -> "XZECZUSD"
        "ETHUSD" -> "XETHZUSD"
        "LTCUSD" -> "XLTCZUSD"
        "XRPUSD" -> "XXRPZUSD"
        "USDTUSD" -> "USDTZUSD"
        "SOLUSD", "WBTCUSD", "TRXUSD", "BNBUSD", "AVAXUSD", "DASHUSD", "BCHUSD", "DAIUSD" -> pair
        else -> error("Unsupported market")
    }

internal fun parseKrakenObservation(pair: String, body: String, receivedAtMillis: Long): MarketObservation {
    require(body.length <= MAX_RESPONSE_BYTES && body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES)
    val key = spreadResponseKey(pair)
    val root = Json.parseToJsonElement(body).jsonObject
    val errors = root.getValue("error").jsonArray
    val rateLimited = errors.any {
        val error = it.jsonPrimitive.content
        error.startsWith("EAPI:Rate limit") || error.startsWith("EService:Throttled")
    }
    if (rateLimited) throw MarketDataFailure("rate limited")
    require(errors.isEmpty())
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

private class MarketDataFailure(override val message: String) : Exception(message)

private const val CHECK_TIMEOUT_MILLIS = 8_000L
private const val RECEIPT_LIFETIME_MILLIS = 60_000L
private const val FUTURE_TOLERANCE_MILLIS = 5_000L
private const val OBSERVATION_LIFETIME_MILLIS = 120_000L
private const val REQUEST_INTERVAL_MILLIS = 1_000L
private const val FAILURE_BACKOFF_MILLIS = 60_000L
private const val MAX_RESPONSE_BYTES = 65_536
private const val MAX_SPREAD_ROWS = 1_000
private const val SPREAD_ROW_FIELDS = 3
