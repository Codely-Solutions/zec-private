package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes wallet-owned writes with reset and invalidates work from an earlier wallet session. */
class WalletWriteAccess(private val progressStore: ResetProgressStore? = null) {
    private val mutex = Mutex()
    private val generation = MutableStateFlow(0L)
    val version = generation.asStateFlow()
    @Volatile private var blocked = false
    @Volatile private var resetting = false

    fun token(): Long = generation.value

    suspend fun <T> write(token: Long, block: suspend () -> T): T? = mutex.withLock {
        if (blocked || progressStore?.isPending?.value == true || generation.value != token) null else block()
    }

    /** Stops admission before draining writes already entered. Remains closed until explicit setup. */
    suspend fun beginReset() {
        resetting = true
        blocked = true
        mutex.withLock {
            blocked = true
            generation.value += 1
        }
    }

    suspend fun finishReset() = mutex.withLock { resetting = false }

    /** Only an explicit create/restore operation may open a new wallet session. */
    suspend fun replaceWallet(block: suspend () -> Unit) = mutex.withLock {
        check(!resetting && progressStore?.isPending?.value != true) { "Wallet reset is pending" }
        block()
        blocked = false
        generation.value += 1
    }
}
