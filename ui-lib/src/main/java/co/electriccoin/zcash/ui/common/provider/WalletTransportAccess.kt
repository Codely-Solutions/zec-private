package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Keeps automatic endpoint writes disjoint from admitted wallet operations and their broadcasts. */
class WalletTransportAccess {
    private val endpointWrite = Mutex()
    private val activeOperations = AtomicInteger()
    @Volatile private var retired: WeakReference<Synchronizer>? = null

    internal suspend fun <T> whileOperating(block: suspend (Lease) -> T): T {
        val lease = endpointWrite.withLock { retain() }
        return try {
            block(lease)
        } finally {
            lease.release()
        }
    }

    internal suspend fun ifIdleSwitch(block: suspend () -> Unit) {
        if (!endpointWrite.tryLock()) return
        try {
            if (activeOperations.get() == 0) block()
        } finally {
            endpointWrite.unlock()
        }
    }

    internal fun retire(synchronizer: Synchronizer?) {
        retired = synchronizer?.let(::WeakReference)
    }

    internal fun isRetired(synchronizer: Synchronizer): Boolean = retired?.get() === synchronizer

    internal suspend fun current(provider: SynchronizerProvider, waitForInitial: Boolean = true): Synchronizer? {
        val old = retired?.get()
        return when {
            old == null && waitForInitial -> provider.getSynchronizer()
            old == null -> provider.synchronizer.value
            else -> withTimeoutOrNull(8.seconds) {
                provider.synchronizer.first { it != null && it !== old }
            }
        }
    }

    private fun retain(): Lease {
        activeOperations.incrementAndGet()
        return Lease()
    }

    internal inner class Lease {
        /** Call while the owning operation itself is still active. */
        fun retainUntilDrain(): () -> Unit {
            val completion = retain()
            return completion::release
        }

        fun release() {
            activeOperations.decrementAndGet()
        }
    }
}
