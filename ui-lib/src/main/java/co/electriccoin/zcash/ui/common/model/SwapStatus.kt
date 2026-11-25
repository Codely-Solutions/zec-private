package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal

enum class SwapStatus(
    val value: String
) {
    INCOMPLETE_DEPOSIT("INCOMPLETE_DEPOSIT"),
    PENDING("PENDING"),
    SUCCESS("SUCCESS"),
    PAYMENT_SHORTFALL("PAYMENT_SHORTFALL"),
    REFUNDED("REFUNDED"),
    FAILED("FAILED"),
    PROCESSING("PROCESSING"),
    EXPIRED("EXPIRED");

    val isTerminal: Boolean
        get() = this == SUCCESS || this == REFUNDED || this == FAILED || this == EXPIRED || this == PAYMENT_SHORTFALL
}

/** Keep the provider status recorded, and surface an explicit fixed-output discrepancy separately. */
internal fun SwapStatus.withPaymentOutcome(
    mode: SwapMode,
    quoted: BigDecimal?,
    executed: BigDecimal?,
): SwapStatus {
    if (this != SwapStatus.SUCCESS || mode != SwapMode.EXACT_OUTPUT) return this
    return if (quoted != null && executed != null && executed < quoted) {
        SwapStatus.PAYMENT_SHORTFALL
    } else {
        this
    }
}
