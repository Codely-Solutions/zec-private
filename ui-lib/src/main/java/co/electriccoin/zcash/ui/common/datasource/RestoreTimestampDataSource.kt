package co.electriccoin.zcash.ui.common.datasource

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.RestoreTimestampStorageProvider
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

interface RestoreTimestampDataSource {
    suspend fun getOrCreate(): Instant

    suspend fun clear()
}

class RestoreTimestampDataSourceImpl(
    private val restoreTimestampStorageProvider: RestoreTimestampStorageProvider
) : RestoreTimestampDataSource {
    private val mutex = Mutex()

    override suspend fun getOrCreate(): Instant =
        mutex.withLock {
            val existing = restoreTimestampStorageProvider.get()

            return if (existing == null) {
                val now = Instant.now()
                restoreTimestampStorageProvider.store(now)
                now
            } else {
                existing
            }
        }

    override suspend fun clear() =
        mutex.withLock {
            restoreTimestampStorageProvider.clear()
        }
}

/** Activity can still show transactions when the optional unread marker is unavailable. */
internal suspend fun RestoreTimestampDataSource.getForActivity(): Instant? =
    runCatchingRecoverable { getOrCreate() }
        .onFailure { Twig.warn { "Unread marker unavailable (§{it.javaClass.simpleName})" } }
        .getOrNull()
