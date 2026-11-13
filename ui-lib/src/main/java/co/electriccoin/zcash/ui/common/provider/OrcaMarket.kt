package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.common.model.MarketObservation
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.Base64

internal object OrcaMarket {
    const val REFERENCE = "ZEC-SOL"
    const val ANCHOR = "USDCUSD"
    private const val POOL = "GTHKH8s82ZR8GTSFZ1dUu6wfdxhy59wpMShxzG5zjiPm"
    private const val OWNER = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
    private const val ZEC_MINT_BYTES = "8769d3af5983df446c4ebf258400afda125d71c13f12c6147cfbca313c9ef213"
    private const val USDC_MINT_BYTES = "c6fa7af3bedbad3a3d65f36aabc97431b1bbe4c2d2f6e0e47ca60203452f5d61"
    private const val ACCOUNT_BYTES = 653
    private const val HEX_RADIX = 16
    private const val HEX_BYTE = 2
    private const val Q128 = 128
    private const val MILLIS_PER_SECOND = 1_000L
    private val minimumLiquidity = BigInteger("100000000000")
    private val feeUnits = BigDecimal("1000000")

    suspend fun observe(client: HttpClient, clock: () -> Long): MarketObservation {
        val rpc = PublicMarketRpc(client, "https://solana-rpc.publicnode.com")
        val account = rpc.read("getAccountInfo", JsonArray(listOf(
            JsonPrimitive(POOL), buildJsonObject {
                put("commitment", "confirmed")
                put("encoding", "base64")
            },
        ))).jsonObject
        val data = account.getValue("value").jsonObject
        require(data.field("owner") == OWNER)
        val slot = account.getValue("context").jsonObject.field("slot").toLong()
        require(slot > 0)
        val timestamp = rpc.read("getBlockTime", JsonArray(listOf(JsonPrimitive(slot))))
            .jsonPrimitive.content.toLong() * MILLIS_PER_SECOND
        val encoded = data.getValue("data").jsonArray
        require(encoded.size == 2 && encoded[1].jsonPrimitive.content == "base64")
        return parse(Base64.getDecoder().decode(encoded[0].jsonPrimitive.content), timestamp, clock())
    }

    @Suppress("MagicNumber")
    internal fun parse(bytes: ByteArray, timestamp: Long, now: Long): MarketObservation {
        require(bytes.size == ACCOUNT_BYTES)
        // Account layout from Orca's Whirlpool state. Both token mints and orientation are pinned.
        require(hex(bytes, 0, 8) == "3f95d10ce1806309")
        require(hex(bytes, 101, 133) == ZEC_MINT_BYTES)
        require(hex(bytes, 181, 213) == USDC_MINT_BYTES)
        require(littleEndian(bytes, 49, 65) >= minimumLiquidity)
        val sqrtPrice = littleEndian(bytes, 65, 81)
        val price = sqrtPrice.pow(2).toBigDecimal().scaleByPowerOfTen(2)
            .divide(BigInteger.ONE.shiftLeft(Q128).toBigDecimal(), MathContext.DECIMAL128)
        val fee = littleEndian(bytes, 45, 47).toBigDecimal().divide(feeUnits)
        val factor = BigDecimal.ONE - fee
        return MarketObservation(
            REFERENCE, price * factor, price.divide(factor, MathContext.DECIMAL128), timestamp, now, listOf("Orca"),
        )
    }

    private fun hex(bytes: ByteArray, start: Int, end: Int): String =
        bytes.copyOfRange(start, end).joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(HEX_BYTE, '0') }

    private fun littleEndian(bytes: ByteArray, start: Int, end: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(start, end).reversedArray())
}
