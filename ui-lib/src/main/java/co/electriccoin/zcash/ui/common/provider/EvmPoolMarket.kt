package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.MarketObservation
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.math.pow

internal object EvmPoolMarket {
    internal data class Pool(
        val host: String,
        val address: String,
        val token0: String,
        val token1: String,
        val tokenIsFirst: Boolean,
        val decimalsDelta: Int,
        val anchor: String,
        val venue: String,
    )

    private const val ETH = "ETHUSD"
    private const val WBNB = "bb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c"
    private const val ARB_WETH = "82af49447d8a07e3bd95bd0d56f35241523fbab1"
    private const val POL_WETH = "7ceb23fd6bc0add59e62ac25578270cff1b9f619"
    private const val WINDOW = 120
    private const val ORACLE_HEAD_OFFSET = 64
    private const val MAX_SQRT_PRICE_BITS = 160
    private const val Q192 = 192
    private const val WORD = 64
    private const val HEX_RADIX = 16
    private const val HEX_BYTE = 2
    private const val TICK_BASE = 1.0001
    private const val MAX_TICK = 887_272
    private const val MILLIS_PER_SECOND = 1_000L
    private val minLiquidity = BigInteger("100000000000000")
    private val divisor = BigInteger.ONE.shiftLeft(Q192).toBigDecimal()

    @Suppress("MagicNumber")
    private val pools = mapOf(
        "USDT-BSC" to Pool(
            "bsc", "0x172fcd41e0913e95784454622d1c3724f546f849",
            "55d398326f99059ff775485246999027b3197955", WBNB, true, 0, "BNBUSD", "PancakeSwap",
        ),
        "USDT-POL" to Pool(
            "polygon-bor", "0xbb98b3d2b18aef63a3178023a920971cf5f29be4",
            POL_WETH, "c2132d05d31c914a87c6611c10748aeb04b58e8f", false, 12, ETH, "Uniswap",
        ),
        "USDT0-ARB" to Pool(
            "arbitrum-one", "0x641c00a822e8b671738d32a431a4fb6074e5c79d",
            ARB_WETH, "fd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", false, 12, ETH, "Uniswap",
        ),
    )

    fun pool(reference: String): Pool? = pools[reference]

    @Suppress("MagicNumber")
    suspend fun observe(client: HttpClient, reference: String, pool: Pool, clock: () -> Long): MarketObservation {
        val rpc = PublicMarketRpc(client, "https://${pool.host}-rpc.publicnode.com")
        val latest = rpc.read(
            "eth_getBlockByNumber", JsonArray(listOf(JsonPrimitive("latest"), JsonPrimitive(false))),
        ).jsonObject
        val block = latest.field("number")
        require(block.matches(Regex("0x[0-9a-f]{1,16}")))
        val timestamp = latest.field("timestamp").removePrefix("0x").toLong(16) * MILLIS_PER_SECOND
        val observe = "883bdbfd" + listOf(32, 2, WINDOW, 0).joinToString("") { it.toString(16).padStart(WORD, '0') }
        val responses = rpc.batch(listOf("0dfe1681", "d21220a7", "1a686502", "3850c7bd", observe).map { selector ->
            "eth_call" to JsonArray(listOf(buildJsonObject {
                put("to", pool.address)
                put("data", "0x$selector")
            }, JsonPrimitive(block)))
        }).map { it.jsonPrimitive.content }
        return parse(reference, pool, responses, timestamp, clock())
    }

    @Suppress("MagicNumber")
    internal fun parse(
        reference: String,
        pool: Pool,
        responses: List<String>,
        timestamp: Long,
        now: Long,
    ): MarketObservation {
        val words = responses.map { data ->
            require(data.startsWith("0x") && data.length % WORD == 2)
            data.drop(2).chunked(WORD)
        }
        require(words[0].single() == pool.token0.padStart(WORD, '0'))
        require(words[1].single() == pool.token1.padStart(WORD, '0'))
        require(words[2].single().toBigInteger(16) >= minLiquidity)
        val squareRoot = words[3].first().toBigInteger(16)
        require(squareRoot.signum() > 0 && squareRoot.bitLength() <= MAX_SQRT_PRICE_BITS)
        val spot = squareRoot.pow(2).toBigDecimal().divide(divisor, MathContext.DECIMAL128)
        val oracle = words[4]
        require(oracle[0].toInt(16) == ORACLE_HEAD_OFFSET && oracle[2].toInt(16) == 2)
        val meanTick = (signedTick(oracle[4]) - signedTick(oracle[3])).toDouble() / WINDOW
        require(meanTick in -MAX_TICK.toDouble()..MAX_TICK.toDouble())
        val average = BigDecimal.valueOf(TICK_BASE.pow(meanTick))
        val scale = { price: BigDecimal ->
            val human = price.scaleByPowerOfTen(pool.decimalsDelta)
            if (pool.tokenIsFirst) human else BigDecimal.ONE.divide(human, MathContext.DECIMAL128)
        }
        val current = scale(spot)
        val historical = scale(average)
        return MarketObservation(
            reference, minOf(current, historical), maxOf(current, historical), timestamp, now, listOf(pool.venue),
        )
    }

    private fun signedTick(word: String): Long = BigInteger(
        word.chunked(HEX_BYTE).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    ).longValueExact()
}
