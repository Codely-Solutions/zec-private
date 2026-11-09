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

internal fun requireConsistent(
    name: String,
    type: SwapQuoteMismatchType,
    raw: BigDecimal?,
    formatted: BigDecimal?,
    decimals: Int
) {
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
 * Fail-closed slippage check on the server-determined (floating) side of the quote. The user-fixed side
 * is already pinned by requireQuoteMatchesUserAmount in the use case; this asserts the server's own
 * minimum output or included input allowance respects the slippage the user requested.
 *
 * Both bounds are required fields of the 1Click quote response (see QuoteDetails), so the check is always
 * enforced: a server that omits them fails deserialization rather than reaching this point with a null
 * bound that would silently skip the assertion.
 */
internal fun requireWithinSlippage(
    swapType: SwapType?,
    amountIn: BigDecimal,
    amountOut: BigDecimal,
    minAmountIn: BigDecimal,
    minAmountOut: BigDecimal,
    slippageToleranceBps: Int
) {
    // Both bounds are computed in integer base units to match the server's integer arithmetic.
    // Using full-precision (DECIMAL128) arithmetic here would leave a fractional remainder that
    // gets compared against the server's already-truncated integer guarantee, rejecting valid
    // quotes by a single base unit (e.g. amountOut=9897372, bps=3000 → real floor=6928160.4,
    // server returns 6928160 — would throw if we didn't round).
    val bps = BigDecimal(slippageToleranceBps)
    val basisPointsDenominator = BigDecimal("10000")
    when (swapType) {
        // Output floats: the guaranteed minimum out must not be worse than amountOut * (1 - slippage).
        // Round DOWN — the server's worst-case minAmountOut is truncated DOWN for its own safety, so the
        // wallet's floor must do the same to accept the boundary case.
        SwapType.EXACT_INPUT, SwapType.FLEX_INPUT -> {
            val floor =
                amountOut
                    .multiply(basisPointsDenominator.subtract(bps))
                    .divide(basisPointsDenominator, 0, RoundingMode.DOWN)
            if (minAmountOut < floor) {
                throw SwapQuoteMismatchException.Rejected(
                    type = SwapQuoteMismatchType.SLIPPAGE_EXCEEDED,
                    message =
                        "Swap slippage exceeded: server-guaranteed minAmountOut=$minAmountOut is below the " +
                            "slippage floor=$floor (amountOut=$amountOut, slippageBps=$slippageToleranceBps)"
                )
            }
        }

        // EXACT_OUTPUT amountIn includes the allowance; minAmountIn is the minimum needed.
        // https://docs.near-intents.org/integration/distribution-channels/1click-api/swap-types
        // Preserve the provider's integer floor; a stronger minimum (smaller allowance) is acceptable.
        SwapType.EXACT_OUTPUT -> {
            if (slippageToleranceBps !in 0..10000) {
                throw SwapQuoteMismatchException.Rejected(
                    type = SwapQuoteMismatchType.SLIPPAGE_TOLERANCE,
                    message = "Invalid swap slippage tolerance: $slippageToleranceBps bps"
                )
            }
            if (amountIn.signum() <= 0 || minAmountIn.signum() <= 0 ||
                amountIn.stripTrailingZeros().scale() > 0 || minAmountIn.stripTrailingZeros().scale() > 0 ||
                minAmountIn > amountIn
            ) {
                throw SwapQuoteMismatchException.Rejected(
                    type = SwapQuoteMismatchType.INPUT_AMOUNT,
                    message = "Invalid exact-output input amounts: amountIn=$amountIn, minAmountIn=$minAmountIn"
                )
            }
            val floor =
                amountIn
                    .multiply(basisPointsDenominator.subtract(bps))
                    .divide(basisPointsDenominator, 0, RoundingMode.DOWN)
            if (minAmountIn < floor) {
                throw SwapQuoteMismatchException.Rejected(
                    type = SwapQuoteMismatchType.SLIPPAGE_EXCEEDED,
                    message =
                        "Swap input allowance exceeded: minAmountIn=$minAmountIn is below the " +
                            "slippage floor=$floor (amountIn=$amountIn, slippageBps=$slippageToleranceBps)"
                )
            }
        }

        null -> {}
    }
}
