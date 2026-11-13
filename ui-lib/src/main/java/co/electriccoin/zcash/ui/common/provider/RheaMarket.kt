package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.MarketObservation
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

internal object RheaMarket {
    private const val RPC = "https://free.rpc.fastnear.com"
    private const val EXCHANGE = "v2.ref-finance.near"
    private const val WNEAR = "wrap.near"
    private const val NBTC_POOL = 5949
    private const val ZEC_POOL = 6065
    private const val WNEAR_DECIMALS = 24
    private const val TOKEN_DECIMALS = 8
    private const val PRICE_DECIMALS = 12
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    const val ANCHOR_MARKET = "NEARUSD"
    private val wnearInput = BigDecimal("4")

    internal data class Pool(val id: Int, val token: String, val tokenInput: BigDecimal)

    private val pools = mapOf(
        "BTC-NEAR" to Pool(NBTC_POOL, "nbtc.bridge.near", BigDecimal("0.0002")),
        "ZEC-NEAR" to Pool(ZEC_POOL, "zec.omft.near", BigDecimal("0.015")),
    )

    fun pool(reference: String): Pool? = pools[reference]

    suspend fun observe(client: HttpClient, reference: String, pool: Pool, clock: () -> Long): MarketObservation =
        coroutineScope {
            val rpc = PublicMarketRpc(client, RPC)
            val header = rpc.read("block", buildJsonObject { put("finality", "final") })
                .jsonObject.getValue("header").jsonObject
            val block = header.field("hash")
            val timestamp = header.field("timestamp_nanosec").toLong() / NANOS_PER_MILLISECOND
            val sell = async {
                viewReturn(rpc, pool, block, pool.token, WNEAR, pool.tokenInput, TOKEN_DECIMALS)
                    .movePointLeft(WNEAR_DECIMALS)
            }
            val buy = async {
                viewReturn(rpc, pool, block, WNEAR, pool.token, wnearInput, WNEAR_DECIMALS)
                    .movePointLeft(TOKEN_DECIMALS)
            }
            MarketObservation(
                reference,
                sell.await().divide(pool.tokenInput, PRICE_DECIMALS, RoundingMode.DOWN),
                wnearInput.divide(buy.await(), PRICE_DECIMALS, RoundingMode.UP),
                timestamp,
                clock(),
                listOf("Rhea Finance"),
            )
        }

    private suspend fun viewReturn(
        rpc: PublicMarketRpc,
        pool: Pool,
        block: String,
        tokenIn: String,
        tokenOut: String,
        amount: BigDecimal,
        decimals: Int,
    ): BigDecimal {
        val args = buildJsonObject {
            put("pool_id", pool.id)
            put("token_in", tokenIn)
            put("token_out", tokenOut)
            put("amount_in", amount.movePointRight(decimals).toBigIntegerExact().toString())
        }
        val response = rpc.read("query", buildJsonObject {
            put("request_type", "call_function")
            put("block_id", block)
            put("account_id", EXCHANGE)
            put("method_name", "get_return")
            put("args_base64", Base64.getEncoder().encodeToString(args.toString().encodeToByteArray()))
        }).jsonObject
        require(response.field("block_hash") == block)
        val bytes = response.getValue("result").jsonArray.map { element ->
            element.jsonPrimitive.content.toInt().also { require(it in 0..UByte.MAX_VALUE.toInt()) }.toByte()
        }
        val amountText = Json.parseToJsonElement(bytes.toByteArray().decodeToString()).jsonPrimitive.content
        require(amountText.length <= WNEAR_DECIMALS + TOKEN_DECIMALS && amountText.all { it in '0'..'9' })
        return marketDecimal(amountText)
    }
}
