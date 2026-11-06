package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import kotlin.time.Instant

/**
 * Hand-written [SwapQuote] double for tests. Cannot be a mockk: stubbing a member that returns a
 * `@JvmInline value class` (every [SwapAddress] impl is one) hangs mockk, so the address fields are
 * constructed directly here. The identity/amount/address fields are constructor parameters because
 * the swap quote validation in `SwapRepository` cross-checks them; everything else is a harmless
 * constant.
 */
@Suppress("LongParameterList")
class FakeSwapQuote(
    override val originAsset: SwapAsset,
    override val destinationAsset: SwapAsset,
    override val mode: SwapMode,
    override val amountIn: BigDecimal,
    override val amountInFormatted: BigDecimal,
    override val amountOutFormatted: BigDecimal,
    depositAddress: String,
    destinationAddress: String,
    refundAddress: String,
    override val provider: String = "near",
    override val deadline: Instant = Instant.parse("2100-01-01T00:00:00Z")
) : SwapQuote {
    override val depositAddress: SwapAddress = DynamicSwapAddress(depositAddress)
    override val destinationAddress: SwapAddress = DynamicSwapAddress(destinationAddress)
    override val refundAddress: SwapAddress = DynamicSwapAddress(refundAddress)
    override val amountInUsd: BigDecimal = BigDecimal.ONE
    override val amountOut: BigDecimal = BigDecimal.ONE
    override val amountOutUsd: BigDecimal = BigDecimal.ONE
    override val affiliateFee: BigDecimal = BigDecimal.ZERO
    override val affiliateFeeUsd: BigDecimal = BigDecimal.ZERO
    override val timestamp: Instant = Instant.fromEpochMilliseconds(0)
    override val slippage: BigDecimal = BigDecimal("2")

}