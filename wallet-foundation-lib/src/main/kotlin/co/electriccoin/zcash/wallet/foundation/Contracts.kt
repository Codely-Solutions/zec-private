package co.electriccoin.zcash.wallet.foundation

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Identifiers remain case-sensitive and are never silently normalized. */
data class AssetId(val network: String, val asset: String) {
    init {
        requireIdentifier(network)
        requireIdentifier(asset)
    }
}

/** [decimals] is supplied by the owning asset integration; conversion never rounds. */
class AssetAmount(val asset: AssetId, value: BigDecimal, val decimals: Int) {
    val value: BigDecimal

    init {
        require(decimals >= 0) { "Asset decimals must be nonnegative" }
        require(value.signum() >= 0) { "Amount must be nonnegative" }
        this.value =
            try {
                value.setScale(decimals, RoundingMode.UNNECESSARY)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Amount exceeds asset precision", e)
            }
    }

    override fun equals(other: Any?): Boolean =
        other is AssetAmount && asset == other.asset && decimals == other.decimals && value == other.value

    override fun hashCode(): Int = 31 * (31 * asset.hashCode() + value.hashCode()) + decimals

    override fun toString(): String = "${value.toPlainString()} $asset"
}

enum class QuoteMode { EXACT_INPUT, EXACT_OUTPUT, FLEX_INPUT }

data class ProviderId(val value: String) {
    init {
        requireIdentifier(value)
    }
}

/** Opaque provider-owned recovery data. Unknown providers require explicit routing failure. */
data class RecoveryReference(val provider: ProviderId, val schema: Int, val reference: String) {
    init {
        require(schema > 0) { "Recovery schema must be positive" }
        require(reference.isNotBlank()) { "Recovery reference is required" }
    }
}

sealed interface Fee {
    data class Known(val amount: AssetAmount) : Fee
    data class Estimated(val amount: AssetAmount) : Fee
    data class Included(val asset: AssetId) : Fee
    data class Unknown(val asset: AssetId) : Fee
}

/** A provider may constrain a flexible deposit to an inclusive range. */
@Suppress("LongParameterList")
data class DepositInstructions(
    val amount: AssetAmount,
    val address: String,
    val recovery: RecoveryReference,
    val memo: String? = null,
    val tag: String? = null,
    val expiresAt: Instant? = null,
    val maximumAmount: AssetAmount = amount
) {
    init {
        require(address.isNotBlank()) { "Deposit address is required" }
        require(memo == null || memo.isNotBlank()) { "Memo must be absent or nonblank" }
        require(tag == null || tag.isNotBlank()) { "Tag must be absent or nonblank" }
        require(maximumAmount.asset == amount.asset && maximumAmount.decimals == amount.decimals)
        require(maximumAmount.value >= amount.value) { "Maximum deposit is below minimum" }
    }
}

enum class FundingCapability { EXTERNAL_DEPOSIT, LOCAL_TRANSACTION }

enum class FlowFailure {
    UNSUPPORTED_MODE, UNSUPPORTED_FUNDING, PROVIDER_MISMATCH, UNSUPPORTED_ASSET,
    UNSUPPORTED_MEMO, UNSUPPORTED_TAG, EXPIRED, UNSUPPORTED_RANGE
}

class FlowRejected(val reason: FlowFailure) : IllegalArgumentException("Flow rejected: $reason")

/** Product wiring declares supported behavior without depending on an engine or provider DTO. */
@Suppress("LongParameterList")
class FlowComposition(
    val provider: ProviderId,
    quoteModes: Set<QuoteMode>,
    depositAssets: Set<AssetId>,
    fundingCapabilities: Set<FundingCapability>,
    val supportsMemo: Boolean = false,
    val supportsTag: Boolean = false
) {
    private val quoteModes = quoteModes.toSet()
    private val depositAssets = depositAssets.toSet()
    private val fundingCapabilities = fundingCapabilities.toSet()

    /** Call before approval and again immediately before funding with the current time. */
    fun validate(
        mode: QuoteMode,
        funding: FundingCapability,
        instructions: DepositInstructions,
        now: Instant
    ) {
        rejectUnless(mode in quoteModes, FlowFailure.UNSUPPORTED_MODE)
        rejectUnless(funding in fundingCapabilities, FlowFailure.UNSUPPORTED_FUNDING)
        rejectUnless(instructions.recovery.provider == provider, FlowFailure.PROVIDER_MISMATCH)
        rejectUnless(instructions.amount.asset in depositAssets, FlowFailure.UNSUPPORTED_ASSET)
        rejectUnless(instructions.memo == null || supportsMemo, FlowFailure.UNSUPPORTED_MEMO)
        rejectUnless(instructions.tag == null || supportsTag, FlowFailure.UNSUPPORTED_TAG)
        rejectUnless(instructions.expiresAt?.let { now < it } != false, FlowFailure.EXPIRED)
        rejectUnless(
            mode == QuoteMode.FLEX_INPUT || instructions.amount == instructions.maximumAmount,
            FlowFailure.UNSUPPORTED_RANGE
        )
    }
}

private fun rejectUnless(condition: Boolean, reason: FlowFailure) {
    if (!condition) throw FlowRejected(reason)
}

private fun requireIdentifier(value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none { it.isISOControl() }) {
        "Identity must be nonblank, unpadded and contain no control characters"
    }
}
