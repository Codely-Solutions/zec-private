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
    val observedAtProvider: kotlin.time.Instant? get() = null

    val status: SwapStatus

    val isSlippageRealized: Boolean
    val maxSlippage: BigDecimal
    val mode: SwapMode

    val amountInFormatted: BigDecimal
    /** USD valuation from the same observation as [amountInFormatted]. */
    val amountInUsd: BigDecimal?
    /** Funding requirement stays quoted even when execution uses a different input. */
    val quotedAmountIn: BigDecimal get() = amountInFormatted
    val fundingThreshold: BigDecimal? get() = null
    val amountOutFormatted: BigDecimal
    /** USD valuation of [amountOutFormatted], when the provider reported both. */
    val amountOutUsd: BigDecimal?
    val quotedAmountOut: BigDecimal get() = amountOutFormatted
    val executedAmountIn: BigDecimal? get() = null
    val executedAmountOut: BigDecimal? get() = null
    val originTransactionIds: List<String> get() = emptyList()
    val destinationTransactionIds: List<String> get() = emptyList()
    val depositedAmountFormatted: BigDecimal?
    val refundedFormatted: BigDecimal?
}

internal inline fun SwapQuoteStatus.settlementStatus(retainedOutput: () -> BigDecimal? = { null }): SwapStatus {
    val reported = status
    if (reported != SwapStatus.SUCCESS || mode != SwapMode.EXACT_OUTPUT) return reported
    return reported.withPaymentOutcome(SwapMode.EXACT_OUTPUT, quotedAmountOut, executedAmountOut ?: retainedOutput())
}
