package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import java.time.Instant

interface SwapQuoteStatus {
    val originAsset: SwapAsset
    val destinationAsset: SwapAsset
    val depositAddress: SwapAddress
    val destinationAddress: SwapAddress

    val timestamp: Instant
    val deadline: kotlin.time.Instant

    val status: SwapStatus

    val isSlippageRealized: Boolean
    val maxSlippage: BigDecimal
    val mode: SwapMode

    val amountInFormatted: BigDecimal
    val amountInUsd: BigDecimal
    val amountOutFormatted: BigDecimal
    val amountOutUsd: BigDecimal
    val quotedAmountOut: BigDecimal get() = amountOutFormatted
    val executedAmountIn: BigDecimal? get() = null
    val executedAmountOut: BigDecimal? get() = null
    val originTransactionIds: List<String> get() = emptyList()
    val destinationTransactionIds: List<String> get() = emptyList()
    val depositedAmountFormatted: BigDecimal?
    val refundedFormatted: BigDecimal?
}
