package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.metadata.SwapMetadataV3
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NearProviderDecimalTest {
    @Test
    fun decimalsAtTheBudgetAndFullTokenRangeDecodeWithoutRounding() {
        val maximumUint256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE).toBigDecimal()
        val maxPrecision = "9".repeat(128)
        val maxWireLength = "0".repeat(511) + "1"
        val maxWireExponent = "0." + "0".repeat(399) + "1E+512"
        val details = Json.decodeFromString<SwapDetails>(
            """{
                "amountIn":"$maximumUint256", "amountInFormatted":"${maximumUint256.movePointLeft(255)}",
                "amountOut":"$maxPrecision", "amountOutFormatted":"$maxWireLength", "amountOutUsd":"1E+127",
                "refundedAmountUsd":"1E-320", "depositedAmount":"$maxWireExponent", "depositedAmountUsd":null
            }"""
        )

        assertEquals(maximumUint256, details.amountIn)
        assertEquals(maximumUint256.movePointLeft(255), details.amountInFormatted)
        assertEquals(BigDecimal(maxPrecision), details.amountOut)
        assertEquals(BigDecimal.ONE, details.amountOutFormatted)
        assertEquals(BigDecimal("1E+127"), details.amountOutUsd)
        assertEquals(BigDecimal("1E-320"), details.refundedAmountUsd)
        assertEquals(BigDecimal("1E+112"), details.depositedAmount)
        assertNull(details.depositedAmountUsd)
    }

    @Test
    fun wireDecodeRejectsEachDecimalBudgetBeforeTheValueCanReachConsumers() {
        val invalidAmounts = listOf(
            "0".repeat(512) + "1", // Wire length with safe precision and scale.
            "0." + "0".repeat(399) + "1E+513", // Wire exponent with safe effective scale.
            "0." + "1".repeat(129), // Coefficient precision with safe scale and magnitude.
            "1E-321", // Positive scale.
            "0E+128", // Negative scale even for zero.
            "99E+127", // Expanded integer width with safe coefficient precision and scale.
        )

        for (amount in invalidAmounts) {
            assertFailsWith<SerializationException>("Expected rejection for a ${amount.length}-character value") {
                Json.decodeFromString<SwapDetails>("""{"amountInUsd":"$amount"}""")
            }
        }
    }

    @Test
    fun programmaticCopiesRejectOversizedQuoteAndStatusDecimals() {
        val quote = Json.decodeFromString<QuoteResponseDto>(boundedNearQuoteWire)
        val details = SwapDetails()
        val oversized = BigDecimal("1E+10000")

        assertFailsWith<IllegalArgumentException> { quote.quoteRequest.copy(amount = oversized) }
        assertFailsWith<IllegalArgumentException> { quote.quote.copy(amountOut = oversized) }
        assertFailsWith<IllegalArgumentException> { quote.quote.copy(minAmountOut = oversized) }
        assertFailsWith<IllegalArgumentException> { quote.quote.copy(amountInFormatted = oversized) }
        assertFailsWith<IllegalArgumentException> { quote.quote.copy(amountOutUsd = oversized) }
        assertFailsWith<IllegalArgumentException> { details.copy(amountOut = oversized) }
        assertFailsWith<IllegalArgumentException> { details.copy(refundedAmountFormatted = oversized) }
        assertFailsWith<IllegalArgumentException> { details.copy(depositedAmountUsd = oversized) }
    }

    @Test
    fun catalogDecimalsBoundScaleShifts() {
        val maxDecimals = """{"assetId":"asset","decimals":255,"blockchain":"eth","symbol":"TOKEN"}"""
        val token = Json.decodeFromString<NearTokenDto>(maxDecimals)

        assertEquals(255, token.decimals)
        assertNull(token.price)
        assertFailsWith<IllegalArgumentException> { token.copy(decimals = -1) }
        assertFailsWith<IllegalArgumentException> { token.copy(price = BigDecimal("1E+10000")) }
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<NearTokenDto>(maxDecimals.replace("255", "256"))
        }
    }

    @Test
    fun historicalSwapMetadataStillDecodesDecimalValuesOutsideTheProviderBudget() {
        val historical = Json.decodeFromString<SwapMetadataV3>(
            """{
                "depositAddress":"synthetic-deposit", "provider":"near", "totalFees":0, "totalUSDFees":"1.25",
                "lastUpdated":1795132800000, "fromAsset":"zec.zec", "toAsset":"btc.btc",
                "exactInput":true, "status":"SUCCESS", "amountOutFormatted":"1E+10000"
            }"""
        )

        assertEquals(BigDecimal("1E+10000"), historical.amountOutFormatted)
        assertEquals(BigDecimal("1.25"), historical.totalFeesUsd)
    }
}

internal val boundedNearQuoteWire = """
    {
        "timestamp":"2026-11-20T00:00:00Z",
        "quoteRequest":{
            "dry":false, "swapType":"EXACT_INPUT", "slippageTolerance":100,
            "originAsset":"origin", "destinationAsset":"destination", "amount":"100000000",
            "refundTo":"synthetic-refund", "recipient":"synthetic-recipient",
            "deadline":"2026-11-21T00:00:00Z", "appFees":[]
        },
        "quote":{
            "depositAddress":"synthetic-deposit", "deadline":"2026-11-21T00:00:00Z",
            "amountIn":"100000000", "minAmountIn":"99000000", "amountInFormatted":"1", "amountInUsd":"3.5",
            "amountOut":"100000000", "minAmountOut":"99000000", "amountOutFormatted":"1", "amountOutUsd":"3.5"
        }
    }
""".trimIndent()
