package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType

/** Complete provider identities only; a symbol never supplies a missing chain or contract. */
internal fun canonicalNearAssetId(id: String): String =
    when (id) {
        "1cs_v1:btc:native:coin" -> "nep141:btc.omft.near"
        else -> id
    }

internal fun requireNearAssetIdentity(expected: SwapAsset, echoedId: String, type: SwapQuoteMismatchType) {
    if (canonicalNearAssetId(expected.assetId) != canonicalNearAssetId(echoedId)) {
        throw SwapQuoteMismatchException.Rejected(type, "Swap provider asset identity mismatch")
    }
}

internal fun resolveNearAsset(supported: List<SwapAsset>, echoedId: String): SwapAsset? {
    val matches = supported.filter { canonicalNearAssetId(it.assetId) == canonicalNearAssetId(echoedId) }
    return matches.singleOrNull()
}
