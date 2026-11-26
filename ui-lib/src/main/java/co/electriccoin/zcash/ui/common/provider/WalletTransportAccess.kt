package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.global.WalletSdkTransportGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

/** Keeps endpoint writes disjoint from admitted wallet operations and their broadcasts. */
class WalletTransportAccess(private val transportGate: WalletSdkTransportGate? = null) {
    private val endpointWrite = Mutex()
    private val activeOperations = MutableStateFlow(0)
    private val retired = MutableStateFlow<WeakReference<Synchronizer>?>(null)
    internal val authorityChanges = combine(retired, transportGate?.inputSuspension ?: flowOf(false)) { _, _ -> Unit }

    internal suspend fun <T> whileOperating(block: suspend (Lease) -> T): T {
        val lease = endpointWrite.withLock {
            if (transportUnavailable()) throw CancellationException("Wallet transport is temporarily unavailable")
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
            return if (!transportUnavailable() && activeOperations.value == 0) block() else null
        } finally {
            endpointWrite.unlock()
        }
    }

    internal suspend fun switchWhenIdle(block: suspend () -> Unit) {
        while (!transportUnavailable()) {
            activeOperations.first { it == 0 }
            endpointWrite.withLock {
                if (transportUnavailable()) return
                if (activeOperations.value == 0) {
                    block()
                    return
                }
            }
        }
    }

    internal fun retire(synchronizer: Synchronizer?) {
        retired.value = synchronizer?.let(::WeakReference)
    }

    internal fun isRetired(synchronizer: Synchronizer): Boolean =
        transportUnavailable() || retired.value?.get() === synchronizer

    internal fun awaitingEndpointReplacement(current: Synchronizer?): Boolean {
        val previous = retired.value ?: return false
        return current == null || current === previous.get()
    }

    internal suspend fun current(provider: SynchronizerProvider, waitForInitial: Boolean = true): Synchronizer? {
        if (transportUnavailable()) return null
        val retirement = retired.value
        val old = retirement?.get()
        return when {
            old == null && waitForInitial -> provider.getSynchronizer()
            old == null -> provider.synchronizer.value
            else -> withTimeoutOrNull(8.seconds) {
                provider.synchronizer.first { it != null && it !== old }
            }.also { if (it != null) retired.compareAndSet(retirement, null) }
        }
    }

    private fun transportUnavailable() = transportGate?.inputSuspended == true

    private fun retain(): Lease {
        activeOperations.update { it + 1 }
        return Lease()
    }

    internal inner class Lease {
        /** Call while the owning operation itself is still active. */
        fun retainUntilDrain(): () -> Unit {
            val completion = retain()
            return completion::release
        }

        fun release() {
            activeOperations.update { it - 1 }
        }
    }
}
