package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.wallet.foundation.ProviderId
import co.electriccoin.zcash.wallet.foundation.RecoveryReference

/** Existing V2/V3 records use a NEAR deposit address. No persisted fields or identifiers change. */
internal fun TransactionSwapMetadata.requireNearRecovery(): RecoveryReference {
    if (provider != "near") throw UnsupportedSwapRecoveryException()
    return RecoveryReference(ProviderId(provider), 1, depositAddress)
}

/** The record remains intact so a supported recovery adapter can be supplied later. */
class UnsupportedSwapRecoveryException :
    IllegalArgumentException("This swap provider is not supported for recovery; the saved record has been preserved")
