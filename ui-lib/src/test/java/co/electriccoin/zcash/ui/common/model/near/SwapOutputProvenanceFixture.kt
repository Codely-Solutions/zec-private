package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import java.math.BigDecimal
import kotlin.time.Instant

internal object SwapOutputProvenanceFixture {
    val quotedAmount = BigDecimal("2")
    val quotedUsd = BigDecimal("10")
    val executedAmount = BigDecimal("2.300001")
    val executedUsd = BigDecimal("11")

    fun status(
        origin: SwapAsset,
        destination: SwapAsset,
        deposit: String,
        mode: SwapType,
        hasExecutedAmount: Boolean,
        hasExecutedUsd: Boolean,
        reportedOutput: BigDecimal = executedAmount,
    ): NearSwapQuoteStatus {
        val recipient = "fixture-recipient"
        val refund = "fixture-refund"
        val timestamp = Instant.fromEpochSeconds(0)
        val quote = QuoteResponseDto(
            timestamp = timestamp,
            quoteRequest = QuoteRequest(
                dry = false,
                swapType = mode,
                slippageTolerance = 100,
                originAsset = origin.assetId,
                destinationAsset = destination.assetId,
                amount = if (mode == SwapType.EXACT_OUTPUT) {
                    quotedAmount.movePointRight(destination.decimals)
                } else {
                    BigDecimal.ONE.movePointRight(origin.decimals)
                },
                refundTo = refund,
                recipient = recipient,
                deadline = timestamp,
                appFees = emptyList(),
            ),
            quote = QuoteDetails(
                depositAddress = deposit,
                amountIn = BigDecimal.ONE.movePointRight(origin.decimals),
                amountInFormatted = BigDecimal.ONE,
                amountInUsd = BigDecimal("30"),
                minAmountIn = BigDecimal.ONE.movePointRight(origin.decimals),
                amountOut = quotedAmount.movePointRight(destination.decimals),
                amountOutFormatted = quotedAmount,
                amountOutUsd = quotedUsd,
                minAmountOut = quotedAmount.movePointRight(destination.decimals),
                deadline = timestamp,
            ),
        )
        return NearSwapQuoteStatus(
            response = SwapStatusResponseDto(
                quoteResponse = quote,
                status = SwapStatus.SUCCESS,
                updatedAt = "",
                swapDetails = SwapDetails(
                    amountOut = reportedOutput.movePointRight(destination.decimals).takeIf { hasExecutedAmount },
                    amountOutUsd = executedUsd.takeIf { hasExecutedUsd },
                ),
            ),
            origin = origin,
            destination = destination,
            depositAddress = DynamicSwapAddress(deposit),
            destinationAddress = DynamicSwapAddress(recipient),
            refundAddress = DynamicSwapAddress(refund),
        )
    }
}
