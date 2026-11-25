package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.global.WalletSdkTransportGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** Keeps endpoint writes disjoint from admitted wallet operations and their broadcasts. */
class WalletTransportAccess(private val transportGate: WalletSdkTransportGate? = null) {
    private val endpointWrite = Mutex()
    private val activeOperations = AtomicInteger()
    private val retired = AtomicReference<WeakReference<Synchronizer>?>(null)

    internal suspend fun <T> whileOperating(block: suspend (Lease) -> T): T {
        val lease = endpointWrite.withLock {
            if (restartPending()) throw CancellationException("Wallet transport change pending restart")
            retain()
        }
        return try {
            block(lease)
        } finally {
            lease.release()
        }
    }

    internal suspend fun <T> ifIdleSwitch(block: suspend () -> T): T? {
        if (!endpointWrite.tryLock()) return null
        try {
            return if (!restartPending() && activeOperations.get() == 0) block() else null
        } finally {
            endpointWrite.unlock()
        }
    }

    internal fun retire(synchronizer: Synchronizer?) {
        retired.set(synchronizer?.let(::WeakReference))
    }

    internal fun isRetired(synchronizer: Synchronizer): Boolean =
        restartPending() || retired.get()?.get() === synchronizer

    internal suspend fun current(provider: SynchronizerProvider, waitForInitial: Boolean = true): Synchronizer? {
        if (restartPending()) return null
        val retirement = retired.get()
        val old = retirement?.get()
        return when {
            old == null && waitForInitial -> provider.getSynchronizer()
            old == null -> provider.synchronizer.value
            else -> withTimeoutOrNull(8.seconds) {
                provider.synchronizer.first { it != null && it !== old }
            }.also { if (it != null) retired.compareAndSet(retirement, null) }
        }
    }

    private fun restartPending() = transportGate?.restartRequired?.value == true

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
