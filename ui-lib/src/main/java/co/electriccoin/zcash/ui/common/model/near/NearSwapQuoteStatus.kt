package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant
import co.electriccoin.zcash.ui.common.model.SwapStatus as ModelSwapStatus

data class NearSwapQuoteStatus(
    val response: SwapStatusResponseDto,
    val origin: SwapAsset,
    val destination: SwapAsset,
    override val depositAddress: SwapAddress,
    override val destinationAddress: SwapAddress,
    val refundAddress: SwapAddress,
) : SwapQuoteStatus {
    init {
        val details = response.swapDetails
        if (details != null) {
            require(details.amountOut == null || details.amountOut.signum() >= 0) {
                "Swap status has negative output"
            }
            require(details.amountOutFormatted == null || details.amountOutFormatted.signum() >= 0) {
                "Swap status has negative formatted output"
            }
            // Input USD unit pricing divides by the effective amountInFormatted (swapDetails value when present),
            // so reject a non-positive server-supplied value to avoid a divide-by-zero. A null value is
            // fine: it falls back to the quote's amountInFormatted, which is already guarded.
            require(details.amountInFormatted == null || details.amountInFormatted.signum() > 0) {
                "Swap status has non-positive amountInFormatted=${details.amountInFormatted}"
            }
            require(details.refundedAmount == null || details.refundedAmount.signum() >= 0) {
                "Swap status has negative refund"
            }
            require(details.refundedAmountFormatted == null || details.refundedAmountFormatted.signum() >= 0) {
                "Swap status has negative formatted refund"
            }
            require(details.depositedAmount == null || details.depositedAmount.signum() >= 0) {
                "Swap status has negative deposit"
            }
            require(details.depositedAmountFormatted == null || details.depositedAmountFormatted.signum() >= 0) {
                "Swap status has negative formatted deposit"
            }
            requireConsistent(
                name = "depositedAmount",
                type = SwapQuoteMismatchType.INPUT_AMOUNT,
                raw = details.depositedAmount,
                formatted = details.depositedAmountFormatted,
                decimals = origin.decimals
            )
            requireConsistent(
                name = "refundedAmount",
                type = SwapQuoteMismatchType.INPUT_AMOUNT,
                raw = details.refundedAmount,
                formatted = details.refundedAmountFormatted,
                decimals = origin.decimals
            )
            requireConsistent(
                name = "amountIn",
                type = SwapQuoteMismatchType.INPUT_AMOUNT,
                raw = details.amountIn,
                formatted = details.amountInFormatted,
                decimals = origin.decimals
            )
            requireConsistent(
                name = "amountOut",
                type = SwapQuoteMismatchType.OUTPUT_AMOUNT,
                raw = details.amountOut,
                formatted = details.amountOutFormatted,
                decimals = destination.decimals
            )
        }
    }

    val quote: SwapQuote =
        NearSwapQuote(
            response = response.quoteResponse,
            originAsset = origin,
            destinationAsset = destination,
            depositAddress = depositAddress,
            destinationAddress = destinationAddress,
            refundAddress = refundAddress,
        )

    override val originAsset: SwapAsset = origin
    override val destinationAsset: SwapAsset = destination
    override val deadline = quote.deadline

    override val timestamp: Instant = response.quoteResponse.timestamp.toJavaInstant()

    override val status: ModelSwapStatus
        get() =
            if (
                response.status == SwapStatus.PENDING_DEPOSIT &&
                Instant.now() > (response.quoteResponse.quote.deadline - 5.minutes).toJavaInstant()
            ) {
                ModelSwapStatus.EXPIRED
            } else {
                when (response.status) {
                    SwapStatus.KNOWN_DEPOSIT_TX -> ModelSwapStatus.PENDING
                    SwapStatus.PENDING_DEPOSIT -> ModelSwapStatus.PENDING
                    SwapStatus.INCOMPLETE_DEPOSIT -> ModelSwapStatus.INCOMPLETE_DEPOSIT
                    SwapStatus.PROCESSING -> ModelSwapStatus.PROCESSING
                    SwapStatus.SUCCESS -> ModelSwapStatus.SUCCESS
                    SwapStatus.REFUNDED -> ModelSwapStatus.REFUNDED
                    SwapStatus.FAILED -> ModelSwapStatus.FAILED
                    null -> ModelSwapStatus.PENDING
                }
            }

    override val isSlippageRealized: Boolean = response.swapDetails?.slippage != null

    @Suppress("MagicNumber")
    override val maxSlippage: BigDecimal =
        response.swapDetails
            ?.slippage
            ?.let {
                BigDecimal(it).divide(BigDecimal(100), MathContext.DECIMAL128)
            }
            ?: quote.slippage

    override val mode: SwapMode = quote.mode

    override val amountInFormatted: BigDecimal = response.swapDetails?.amountInFormatted ?: quote.amountInFormatted

    override val amountInUsd: BigDecimal = response.swapDetails?.amountInUsd ?: quote.amountInUsd

    override val quotedAmountOut: BigDecimal = quote.amountOutFormatted

    override val executedAmountIn: BigDecimal? =
        response.swapDetails?.amountInFormatted ?: response.swapDetails?.amountIn?.movePointLeft(origin.decimals)

    override val executedAmountOut: BigDecimal? =
        response.swapDetails?.amountOutFormatted
            ?: response.swapDetails?.amountOut?.movePointLeft(destination.decimals)

    override val destinationTransactionIds: List<String> =
        response.swapDetails?.destinationChainTxHashes?.map { it.hash }.orEmpty()

    override val amountOutFormatted: BigDecimal = executedAmountOut ?: quotedAmountOut

    override val amountOutUsd: BigDecimal = response.swapDetails?.amountOutUsd ?: quote.amountOutUsd

    override val depositedAmountFormatted: BigDecimal? = response.swapDetails?.depositedAmountFormatted
        ?: response.swapDetails?.depositedAmount?.movePointLeft(origin.decimals)

    override val refundedFormatted: BigDecimal? = response.swapDetails?.refundedAmountFormatted
        ?: response.swapDetails?.refundedAmount?.movePointLeft(origin.decimals)
}
