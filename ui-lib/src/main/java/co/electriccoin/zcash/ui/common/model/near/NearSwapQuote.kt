package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.datasource.NEAR_SWAP_PROVIDER
import co.electriccoin.zcash.ui.common.model.SwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.isSame
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.time.Instant

/**
 * @param expectedSlippageToleranceBps The slippage tolerance (in basis points) the client actually requested,
 * snapshotted before the call. The fail-closed slippage check below MUST use this rather than the server-echoed
 * `response.quoteRequest.slippageTolerance`: a malicious server could echo a wider tolerance (e.g.
 * 10000 = 100%) so the slippage floor collapses to zero and the check always passes. When non-null
 * the echo is additionally required to match it exactly. Null only for the status-display path,
 * where the original client request is no longer available and there is nothing left to authorize.
 */
data class NearSwapQuote(
    val response: QuoteResponseDto,
    override val originAsset: SwapAsset,
    override val destinationAsset: SwapAsset,
    override val depositAddress: SwapAddress,
    override val destinationAddress: SwapAddress,
    override val refundAddress: SwapAddress,
    val expectedSlippageToleranceBps: Int? = null,
) : SwapQuote {
    init {
        requireNearAssetIdentity(originAsset, response.quoteRequest.originAsset, SwapQuoteMismatchType.ORIGIN_ASSET)
        requireNearAssetIdentity(
            destinationAsset, response.quoteRequest.destinationAsset, SwapQuoteMismatchType.DESTINATION_ASSET
        )
        // Guards input USD unit pricing (= amountInUsd / amountInFormatted) against a
        // divide-by-zero. A quote with a non-positive input amount is invalid anyway; fail closed with a
        // clear message before presentation attempts to calculate a unit price.
        if (response.quote.amountInFormatted.signum() <= 0) {
            throw SwapQuoteMismatchException.Rejected(
                type = SwapQuoteMismatchType.NON_POSITIVE_AMOUNT,
                message = "Swap quote has non-positive amountInFormatted=${response.quote.amountInFormatted}"
            )
        }
        requireConsistent(
            name = "amountIn",
            type = SwapQuoteMismatchType.INPUT_AMOUNT,
            raw = response.quote.amountIn,
            formatted = response.quote.amountInFormatted,
            decimals = originAsset.decimals
        )
        requireConsistent(
            name = "amountOut",
            type = SwapQuoteMismatchType.OUTPUT_AMOUNT,
            raw = response.quote.amountOut,
            formatted = response.quote.amountOutFormatted,
            decimals = destinationAsset.decimals
        )
        // The slippage tolerance must come from the client, not from the server's echoed request. When we
        // know what we asked for, require the echo to match it exactly so the displayed `slippage` (derived
        // below from the echo) is also trustworthy; then feed the client value into the fail-closed check.
        if (expectedSlippageToleranceBps != null &&
            response.quoteRequest.slippageTolerance != expectedSlippageToleranceBps
        ) {
            throw SwapQuoteMismatchException.Rejected(
                type = SwapQuoteMismatchType.SLIPPAGE_TOLERANCE,
                message =
                    "Swap slippage tolerance mismatch: requested $expectedSlippageToleranceBps bps " +
                        "but server returned ${response.quoteRequest.slippageTolerance}"
            )
        }
        if (expectedSlippageToleranceBps != null) {
            response.quote.requireFundingAmounts(expectedSlippageToleranceBps)
            if (response.quoteRequest.swapType == SwapType.FLEX_INPUT) {
                requireSlippageFloor(
                    field = "minAmountIn",
                    amount = response.quote.amountIn,
                    minimum = response.quote.minAmountIn,
                    toleranceBps = expectedSlippageToleranceBps
                )
            }
        }
        requireWithinSlippage(
            swapType = response.quoteRequest.swapType,
            amountIn = response.quote.amountIn,
            amountOut = response.quote.amountOut,
            minAmountIn = response.quote.minAmountIn,
            minAmountOut = response.quote.minAmountOut,
            slippageToleranceBps = expectedSlippageToleranceBps ?: response.quoteRequest.slippageTolerance
        )
    }

    override val slippage: BigDecimal =
        BigDecimal(response.quoteRequest.slippageTolerance)
            .divide(BigDecimal("100", MathContext.DECIMAL128))

    override val provider = NEAR_SWAP_PROVIDER

    override val mode: SwapMode =
        when (response.quoteRequest.swapType) {
            SwapType.FLEX_INPUT -> SwapMode.FLEX_INPUT
            SwapType.EXACT_INPUT -> SwapMode.EXACT_INPUT
            SwapType.EXACT_OUTPUT -> SwapMode.EXACT_OUTPUT
            null -> SwapMode.EXACT_INPUT
        }

    override val amountIn: BigDecimal = response.quote.amountIn
    override val amountInFormatted: BigDecimal = response.quote.amountInFormatted
    override val amountInUsd: BigDecimal = response.quote.amountInUsd

    override val amountOut: BigDecimal = response.quote.amountOut
    override val amountOutUsd: BigDecimal = response.quote.amountOutUsd
    override val amountOutFormatted: BigDecimal = response.quote.amountOutFormatted
    override val minimumAmountIn: BigDecimal?
        get() = response.quote.minAmountIn.takeIf {
            mode == SwapMode.FLEX_INPUT && it.isPositiveAtomic() && it <= amountIn
        }?.movePointLeft(originAsset.decimals)

    override val minimumAmountOut: BigDecimal = response.quote.minAmountOut.movePointLeft(destinationAsset.decimals)
    override val inputAllowance: BigDecimal?
        get() =
            if (mode == SwapMode.EXACT_OUTPUT) {
                response.quote.amountIn.subtract(response.quote.minAmountIn).movePointLeft(originAsset.decimals)
            } else {
                null
            }

    override val affiliateFee: BigDecimal = BigDecimal.ZERO

    override val affiliateFeeUsd: BigDecimal = BigDecimal.ZERO

    override val timestamp: Instant = response.timestamp

    override val deadline: Instant = response.quote.deadline
}

internal fun SwapQuote.reviewedIdentity(): String? =
    (this as? NearSwapQuote)?.response?.reviewedIdentity()

/** Keep the route verifiable without storing another copy of the recipient and refund addresses. */
internal fun QuoteResponseDto.reviewedIdentity(): String {
    val fields = listOf(
        "near-swap-v1", quote.depositAddress, quoteRequest.originAsset, quoteRequest.destinationAsset,
        requireNotNull(quoteRequest.swapType).apiValue, quoteRequest.recipient, quoteRequest.refundTo
    )
    val framed = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(framed.encodeToByteArray()).toHexString()
}

internal fun requireConsistent(
    name: String,
    type: SwapQuoteMismatchType,
    raw: BigDecimal?,
    formatted: BigDecimal?,
    decimals: Int
) {
    requireNearTokenDecimals(decimals)
    if (raw == null || formatted == null) return
    if (raw.compareTo(formatted.movePointRight(decimals)) != 0) {
        throw SwapQuoteMismatchException.AmountInconsistency(
            type = type,
            field = name,
            decimals = decimals,
            message =
                "Swap amount inconsistency: $name ($raw) does not match " +
                    "${name}Formatted ($formatted) at $decimals decimals"
        )
    }
}

/**
 * Asserts the quote echoes back the amount the user actually requested for the user-fixed side of the
 * swap. The requested amount is truncated (RoundingMode.DOWN) to the asset's decimal precision before
 * comparing, because that is the precision the request is sent at — anything beyond `decimals` cannot be
 * represented on-chain and is intentionally dropped. So e.g. a user entry of 1.123456789 on an 8-decimal
 * asset is accepted against a quote of 1.12345678.
 */
internal fun requireQuoteMatchesUserAmount(quoted: BigDecimal, requested: BigDecimal, decimals: Int) {
    val truncated = requested.setScale(decimals, RoundingMode.DOWN)
    if (quoted.compareTo(truncated) != 0) {
        throw SwapQuoteMismatchException.Rejected(
            type = SwapQuoteMismatchType.REQUESTED_AMOUNT,
            message =
                "Swap quote does not match user-requested amount: " +
                    "quote=$quoted, requested=$truncated (at $decimals decimals)"
        )
    }
}

internal fun requireMatchingAsset(
    name: String,
    type: SwapQuoteMismatchType,
    expectedTokenTicker: String,
    expectedChainTicker: String,
    actual: SwapAsset
) {
    if (!actual.isSame(expectedTokenTicker, expectedChainTicker)) {
        throw SwapQuoteMismatchException.Rejected(
            type = type,
            message =
                "Swap asset mismatch: expected $name=$expectedTokenTicker/$expectedChainTicker " +
                    "but server returned ${actual.tokenTicker}/${actual.chainTicker}"
        )
    }
}

/**
 * Checks the slippage guarantees also required when reconstructing saved quotes. Fresh FLEX_INPUT
 * funding admission checks the input floor separately so older observations remain readable.
 */
internal fun requireWithinSlippage(
    swapType: SwapType?,
    amountIn: BigDecimal,
    amountOut: BigDecimal,
    minAmountIn: BigDecimal,
    minAmountOut: BigDecimal,
    slippageToleranceBps: Int
) {
    when (swapType) {
        SwapType.EXACT_INPUT, SwapType.FLEX_INPUT -> {
            requireSlippageFloor("minAmountOut", amountOut, minAmountOut, slippageToleranceBps)
        }
        SwapType.EXACT_OUTPUT -> {
            requireExactOutputAmounts(amountIn, minAmountIn, slippageToleranceBps)
            requireSlippageFloor("minAmountIn", amountIn, minAmountIn, slippageToleranceBps)
        }
        null -> {}
    }
}

private fun requireSlippageFloor(field: String, amount: BigDecimal, minimum: BigDecimal, toleranceBps: Int) {
    // 1Click rounds its guarantees down to whole base units on both sides of FLEX_INPUT.
    val denominator = BigDecimal("10000")
    val floor = amount.multiply(denominator - BigDecimal(toleranceBps)).divide(denominator, 0, RoundingMode.DOWN)
    if (minimum < floor) {
        throw SwapQuoteMismatchException.Rejected(
            type = SwapQuoteMismatchType.SLIPPAGE_EXCEEDED,
            message = "Swap slippage exceeded: $field=$minimum is below $floor " +
                "(amount=$amount, slippageBps=$toleranceBps)"
        )
    }
}

private const val MAX_SLIPPAGE_BPS = 10000

private fun BigDecimal.isPositiveAtomic() = signum() > 0 && stripTrailingZeros().scale() <= 0

private fun QuoteDetails.requireFundingAmounts(slippageBps: Int) {
    if (slippageBps !in 0..MAX_SLIPPAGE_BPS) {
        throw SwapQuoteMismatchException.Rejected(
            SwapQuoteMismatchType.SLIPPAGE_TOLERANCE, "Invalid swap slippage tolerance"
        )
    }
    requireAtomicBounds(amountIn, minAmountIn, SwapQuoteMismatchType.INPUT_AMOUNT)
    requireAtomicBounds(amountOut, minAmountOut, SwapQuoteMismatchType.OUTPUT_AMOUNT)
}

private fun requireAtomicBounds(amount: BigDecimal, minimum: BigDecimal, type: SwapQuoteMismatchType) {
    if (!amount.isPositiveAtomic() || !minimum.isPositiveAtomic() || minimum > amount) {
        throw SwapQuoteMismatchException.Rejected(type, "Invalid swap amount or minimum in atomic units")
    }
}

private fun requireExactOutputAmounts(amountIn: BigDecimal, minAmountIn: BigDecimal, slippageBps: Int) {
    if (slippageBps !in 0..MAX_SLIPPAGE_BPS) {
        throw SwapQuoteMismatchException.Rejected(
            type = SwapQuoteMismatchType.SLIPPAGE_TOLERANCE,
            message = "Invalid swap slippage tolerance: $slippageBps bps"
        )
    }
    if (!amountIn.isPositiveAtomic() || !minAmountIn.isPositiveAtomic() || minAmountIn > amountIn) {
        throw SwapQuoteMismatchException.Rejected(
            type = SwapQuoteMismatchType.INPUT_AMOUNT,
            message = "Invalid exact-output input amounts: amountIn=$amountIn, minAmountIn=$minAmountIn"
        )
    }
}
