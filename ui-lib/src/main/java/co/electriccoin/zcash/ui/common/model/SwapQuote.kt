package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import kotlin.time.Instant

interface SwapQuote {
    val originAsset: SwapAsset
    val destinationAsset: SwapAsset
    val depositAddress: SwapAddress
    val destinationAddress: SwapAddress
    val refundAddress: SwapAddress
    val provider: String
    val mode: SwapMode

    val amountIn: BigDecimal
    val amountInFormatted: BigDecimal
    val amountInUsd: BigDecimal

    val amountOut: BigDecimal
    val amountOutUsd: BigDecimal
    val amountOutFormatted: BigDecimal
    val minimumAmountIn: BigDecimal? get() = null
    val minimumAmountOut: BigDecimal? get() = null
    val inputAllowance: BigDecimal? get() = null

    val affiliateFee: BigDecimal
    val affiliateFeeUsd: BigDecimal

    val timestamp: Instant
    val deadline: Instant

    val slippage: BigDecimal

}
