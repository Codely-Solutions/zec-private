package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.global.WalletSdkTransportGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@ConsistentCopyVisibility
data class WalletEnrollmentToken internal constructor(internal val wallet: Long, internal val sdkErasure: Long)

class WalletEnrollmentAccess(
    private val walletWriteAccess: WalletWriteAccess,
    private val walletCoordinator: WalletCoordinator,
    private val transportGate: WalletSdkTransportGate? = null,
) {
    val ownershipChanges = walletWriteAccess.enrollmentChanges

    fun capture(): WalletEnrollmentToken? = walletWriteAccess.enrollmentToken()

    fun permits(token: WalletEnrollmentToken): Boolean = walletWriteAccess.permitsEnrollment(token)

    /**
     * The user can retry while a synchronizer is restarting. Once admitted, the native mutation and
     * its receipt finish on the same SDK instance, and reset or recovery waits for them.
     */
    suspend fun <T> complete(token: WalletEnrollmentToken, block: suspend (Synchronizer) -> T): T? {
        if (!permits(token)) return null
        return walletWriteAccess.withSdkRecovery(token.wallet) {
            if (!permits(token) || transportGate?.restartRequired?.value == true) return@withSdkRecovery null
            val sdk = checkNotNull(walletCoordinator.synchronizer.value) {
                "The wallet is connecting. Try again when it is ready."
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { block(sdk) }
        }
    }

    /** Call during [complete] for writes that also use the general wallet write fence. */
    suspend fun <T> write(token: WalletEnrollmentToken, block: suspend () -> T): T? =
        walletWriteAccess.write(token.wallet) { if (permits(token)) block() else null }

    /** Publish a pending enrollment only while its original wallet and database still own it. */
    suspend fun <T> stage(token: WalletEnrollmentToken, block: () -> T): T? =
        walletWriteAccess.withSdkRecovery(token.wallet) { if (permits(token)) block() else null }
}
