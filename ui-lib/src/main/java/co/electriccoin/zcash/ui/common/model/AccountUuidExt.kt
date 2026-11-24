package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.ext.toHex
import cash.z.ecc.android.sdk.model.AccountUuid

/**
 * Stable hex-string form of this account's UUID, suitable for use as a per-account storage key
 * suffix (e.g. `PreferenceKey("some_flag_${accountUuid.toStorageKeyId()}")`). [AccountUuid] doesn't
 * override [Any.toString], so its default `data class` form (`AccountUuid(value=[B@...)`) is
 * neither stable nor human-usable as a key — this is the one to use instead for that purpose.
 *
 * Not the canonical dashed-UUID string form some external APIs need — see
 * `AccountUuid.toCanonicalUuidString()` (voting) for that.
 */
fun AccountUuid.toStorageKeyId(): String = value.toHex()
