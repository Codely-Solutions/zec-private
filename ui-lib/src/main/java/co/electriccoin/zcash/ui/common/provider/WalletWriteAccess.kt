package co.electriccoin.zcash.ui.common.provider

import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes wallet-owned writes with reset and invalidates work from an earlier wallet session. */
class WalletWriteAccess(private val progressStore: ResetProgressStore? = null) {
    private val mutex = Mutex()
    private val sdkRecovery = Mutex()
    private val generation = MutableStateFlow(0L)
    private val completedResets = MutableStateFlow(0L)
    val version = generation.asStateFlow()
    internal val resetCompletions = completedResets.asStateFlow()
    internal val admissionChanges = combine(generation, progressStore?.isPending ?: flowOf(false)) { _, _ -> Unit }
    @Volatile private var blocked = false
    @Volatile private var resetting = false

    fun token(): Long = generation.value

    internal fun permits(token: Long): Boolean =
        !blocked && !resetting && progressStore?.isPending?.value != true && generation.value == token

    suspend fun <T> write(token: Long, block: suspend () -> T): T? = mutex.withLock {
        if (permits(token)) block() else null
    }

    internal suspend fun <T> withSdkRecovery(token: Long, block: suspend () -> T): T? = sdkRecovery.withLock {
        if (permits(token)) block() else null
    }

    internal fun enrollmentToken(): WalletEnrollmentToken? {
        val wallet = token()
        return WalletEnrollmentToken(wallet).takeIf(::permitsEnrollment)
    }

    internal fun permitsEnrollment(token: WalletEnrollmentToken): Boolean = permits(token.wallet)

    /** Stops admission before draining SDK recovery and writes. Remains closed until explicit setup. */
    suspend fun beginReset() {
        resetting = true
        blocked = true
        sdkRecovery.withLock {
            mutex.withLock {
                blocked = true
                generation.value += 1
            }
        }
    }

    suspend fun finishReset() = mutex.withLock {
        if (resetting) {
            resetting = false
            completedResets.value += 1
        }
    }

    /** Only an explicit create/restore operation may open a new wallet session. */
    suspend fun replaceWallet(block: suspend () -> Unit) = sdkRecovery.withLock {
        mutex.withLock {
            check(!resetting && progressStore?.isPending?.value != true) { "Wallet reset is pending" }
            block()
            blocked = false
            generation.value += 1
        }
    }
}
