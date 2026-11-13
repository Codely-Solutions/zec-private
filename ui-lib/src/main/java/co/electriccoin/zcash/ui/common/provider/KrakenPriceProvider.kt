package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.IndependentPricePolicy
import co.electriccoin.zcash.ui.common.model.MarketObservation
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private val failures = mutableMapOf<String, Long>()
    private var lastRequestStartedMillis: Long? = null

    @Suppress("TooGenericExceptionCaught")
    override suspend fun check(
        quote: SwapQuote,
        networkFeeZec: BigDecimal
    ): IndependentPriceCheck = withContext(Dispatchers.IO) {
        val markets = listOf(
            IndependentPricePolicy.market(quote.originAsset)
                ?: return@withContext IndependentPriceCheck.NoMarketReference,
            IndependentPricePolicy.market(quote.destinationAsset)
                ?: return@withContext IndependentPriceCheck.NoMarketReference
        ).distinct().sortedByDescending { it.contains('-') }
        try {
            withTimeout(CHECK_TIMEOUT_MILLIS) {
                mutex.withLock {
                    val now = wallTimeMillis()
                    val elapsed = elapsedTimeMillis()
                    if (now < 0L || elapsed < 0L) return@withLock IndependentPriceCheck.Unavailable
                    val missing = markets.filterNot { isCacheFresh(it, now, elapsed) }
                    if (missing.any { isBackingOff(it, elapsed) }) {
                        return@withLock IndependentPriceCheck.Unavailable
                    }
                    if (missing.isNotEmpty()) refreshObservations(missing)
                    compareCached(markets, quote, networkFeeZec)
                }
            }
        } catch (_: TimeoutCancellationException) {
            Twig.warn { "Independent market check: timeout" }
            IndependentPriceCheck.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MarketDataFailure) {
            Twig.warn { "Independent market check: " + failure.message }
            IndependentPriceCheck.Unavailable
        } catch (failure: Exception) {
            Twig.warn { "Independent market check: request failed (${failure.javaClass.simpleName})" }
            IndependentPriceCheck.Unavailable
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshObservations(missing: List<String>) {
        var active = missing.first()
        try {
            httpClientProvider.create().use { client ->
                missing.forEach { market ->
                    active = market
                    val observation = readObservation(client, market)
                    val now = wallTimeMillis()
                    require(IndependentPricePolicy.isUsable(observation, now))
                    val receiptAge = (now - observation.receivedAtMillis).coerceAtLeast(0)
                    cache[market] = CachedObservation(observation, elapsedTimeMillis() - receiptAge)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures[active] = elapsedTimeMillis()
            throw failure
        }
    }

    private suspend fun readObservation(client: HttpClient, market: String): MarketObservation {
        val evmPool = EvmPoolMarket.pool(market)
        val nearPool = RheaMarket.pool(market)
        return when {
            evmPool != null -> withAnchor(client, evmPool.anchor) {
                EvmPoolMarket.observe(client, market, evmPool, wallTimeMillis)
            }
            market == OrcaMarket.REFERENCE -> withAnchor(client, OrcaMarket.ANCHOR) {
                OrcaMarket.observe(client, wallTimeMillis)
            }
            nearPool != null -> withAnchor(client, RheaMarket.ANCHOR_MARKET) {
                RheaMarket.observe(client, market, nearPool, wallTimeMillis)
            }
            else -> readKraken(client, market)
        }
    }

    private suspend fun withAnchor(
        client: HttpClient,
        market: String,
        observe: suspend () -> MarketObservation,
    ): MarketObservation = coroutineScope {
        val pool = async { observe() }
        val anchor = anchorObservation(client, market)
        pool.await().inDollars(anchor)
    }

    private suspend fun anchorObservation(client: HttpClient, market: String): MarketObservation {
        val cached = cache[market]
        if (cached != null && isCacheFresh(market, wallTimeMillis(), elapsedTimeMillis())) return cached.observation
        return readKraken(client, market).also { cache[market] = CachedObservation(it, elapsedTimeMillis()) }
    }

    private suspend fun readKraken(client: HttpClient, market: String): MarketObservation {
        val endpoints = if (market in DEPTH_FIRST) listOf("Depth", "Spread") else listOf("Spread")
        for (endpoint in endpoints) {
            awaitRequestInterval()
            val root = client.readMarketJson("https://api.kraken.com/0/public/$endpoint", KRAKEN_RESPONSE_BYTES) {
                url {
                    parameters.append("pair", market)
                    if (endpoint == "Depth") parameters.append("count", "1")
                }
            }
            val now = wallTimeMillis()
            val observation = if (endpoint == "Depth") parseKrakenDepth(market, root, now)
            else parseKrakenSpread(market, root, now)
            if (IndependentPricePolicy.isUsable(observation, now)) return observation
        }
        throw MarketDataFailure("stale or unusable book")
    }

    private fun isBackingOff(market: String, elapsed: Long): Boolean =
        failures[market]?.let { elapsed - it in 0 until FAILURE_BACKOFF_MILLIS } == true

    private fun isCacheFresh(pair: String, now: Long, elapsed: Long): Boolean =
        cache[pair]?.let {
            elapsed - it.receivedAtElapsedMillis in 0 until RECEIPT_LIFETIME_MILLIS &&
                now - it.observation.observedAtMillis in
                -FUTURE_TOLERANCE_MILLIS until OBSERVATION_LIFETIME_MILLIS
        } == true

    private suspend fun awaitRequestInterval() {
        val elapsed = elapsedTimeMillis()
        val sinceRequest = lastRequestStartedMillis?.let { elapsed - it }
        require(elapsed >= 0L && (sinceRequest == null || sinceRequest >= 0L))
        requestDelay((REQUEST_INTERVAL_MILLIS - (sinceRequest ?: REQUEST_INTERVAL_MILLIS)).coerceAtLeast(0L))
        lastRequestStartedMillis = elapsedTimeMillis()
    }

    private fun compareCached(
        markets: List<String>,
        quote: SwapQuote,
        networkFeeZec: BigDecimal
    ): IndependentPriceCheck {
        val comparisonTime = wallTimeMillis()
        val comparisonElapsed = elapsedTimeMillis()
        val observations = markets.associateWith { market ->
            val cached = cache.getValue(market)
            val age = comparisonElapsed - cached.receivedAtElapsedMillis
            if (comparisonTime < 0L || comparisonElapsed < 0L || age !in 0 until RECEIPT_LIFETIME_MILLIS) {
                return IndependentPriceCheck.Unavailable
            }
            cached.observation.copy(receivedAtMillis = comparisonTime - age)
        }
        return IndependentPricePolicy.compare(quote, networkFeeZec, observations, comparisonTime)
    }

    private data class CachedObservation(val observation: MarketObservation, val receivedAtElapsedMillis: Long)
}

private val DEPTH_FIRST = setOf("USDTUSD", "USDCUSD")
private const val CHECK_TIMEOUT_MILLIS = 8_000L
private const val RECEIPT_LIFETIME_MILLIS = 60_000L
private const val FUTURE_TOLERANCE_MILLIS = 5_000L
private const val OBSERVATION_LIFETIME_MILLIS = 120_000L
private const val REQUEST_INTERVAL_MILLIS = 1_000L
private const val FAILURE_BACKOFF_MILLIS = 60_000L
