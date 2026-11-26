package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.preference.model.entry.TimestampPreferenceDefault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Wallet-global timestamp for observed sync activity and completed scheduled crossings.
 * The migration driver uses it to enforce the quiet period across callers and accounts.
 *
 * Key: `"last_network_activity_epoch"` — backed by regular (non-encrypted) app storage.
 */
interface LastNetworkActivityStorageProvider {
    /** Writes the current epoch as the last-seen network activity timestamp. */
    suspend fun stampNow()

    /** Returns the last-stamped instant, or `null` when never written. */
    suspend fun get(): Instant?
}

class LastNetworkActivityStorageProviderImpl(
    private val preferenceHolder: StandardPreferenceProvider,
) : LastNetworkActivityStorageProvider {
    private val default = TimestampPreferenceDefault(PreferenceKey("last_network_activity_epoch"))

    private val writeLock = Mutex()

    @Volatile
    private var lastObserved: Instant? = null

    override suspend fun stampNow() {
        writeLock.withLock {
            val now = Instant.now()
            val observed = maxOf(now, lastObserved ?: now)
            // Keep the local quiet window when saving the durable timestamp fails.
            lastObserved = observed
            default.putValue(preferenceHolder(), observed)
        }
    }

    override suspend fun get(): Instant? =
        lastObserved ?: default.getValue(preferenceHolder()).let { persisted -> lastObserved ?: persisted }
}
