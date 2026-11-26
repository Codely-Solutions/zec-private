package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.WalletSeedRecovery
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Recovers from the SDK's `SeedNotRelevant` state ([WalletCoordinator.isSeedMismatch]). Withdraws
 * the SDK input, erases the inactive wallet data and re-stores the existing wallet with
 * [WalletInitMode.RestoreWallet] before the next synchronizer build.
 * A reported failure throws [SeedMismatchRecoveryException]; after [MAX_RECOVERY_ATTEMPTS]
 * failures no further erase is attempted for that wallet generation. Wallet reset and replacement drain
 * admitted SDK erases. Re-storing also verifies that the wallet snapshot is current.
 */
class RecoverFromSeedMismatchUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val sdkRecovery: WalletSeedRecovery,
    private val walletWriteAccess: WalletWriteAccess,
    private val preferenceRecovery: SdkEncryptedPreferenceRecoveryProvider,
) {
    private var attemptVersion: Long? = null
    private var failedAttempts = 0

    suspend operator fun invoke(version: Long, onEraseDelayed: () -> Unit = {}) {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return
        walletWriteAccess.withSdkRecovery(version) {
            eraseSdkData(wallet, version, onEraseDelayed)
        }
    }

    private suspend fun eraseSdkData(wallet: PersistableWallet, version: Long, onEraseDelayed: () -> Unit) {
        if (attemptVersion != version) {
            attemptVersion = version
            failedAttempts = 0
        }
        if (failedAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Twig.error {
                "Seed-mismatch recovery: giving up after $failedAttempts failed attempt(s), not retrying erase again"
            }
            throw SeedMismatchRecoveryException(failedAttempts)
        }

        val readable = canReadSdkPreferences()
        val erased = if (readable) waitForErase(wallet, version, onEraseDelayed) else false
        if (erased == false) {
            failedAttempts += 1
            Twig.error {
                val stage = if (readable) "SDK erase failed" else "SDK encrypted preferences unreadable"
                "Seed-mismatch recovery: $stage (attempt $failedAttempts/$MAX_RECOVERY_ATTEMPTS)"
            }
            throw SeedMismatchRecoveryException(failedAttempts)
        }

        if (erased == true) failedAttempts = 0
    }

    private suspend fun canReadSdkPreferences(): Boolean {
        repeat(PREFERENCE_READ_ATTEMPTS) { attempt ->
            if (runCatchingRecoverable { preferenceRecovery.checkReadableForErase() }.isSuccess) return true
            if (attempt < PREFERENCE_READ_ATTEMPTS - 1) delay(1.seconds)
        }
        return false
    }

    private suspend fun waitForErase(
        wallet: PersistableWallet,
        version: Long,
        onEraseDelayed: () -> Unit,
    ): Boolean? = coroutineScope {
        val notice = launch {
            delay(ERASE_NOTICE_DELAY)
            Twig.info { "Seed-mismatch recovery: waiting for the SDK erase result" }
            onEraseDelayed()
        }
        try {
            withContext(NonCancellable) {
                sdkRecovery.eraseAndRestore(wallet.network, walletWriteAccess::onSdkErasureStarting) {
                    val recoveryWallet = wallet.copy(
                        birthday = wallet.birthday ?: wallet.network.saplingActivationHeight,
                        walletInitMode = WalletInitMode.RestoreWallet,
                    )
                    if (persistableWalletProvider.storeIfCurrent(wallet, recoveryWallet, version)) {
                        // Withdraw the cached pre-recovery input until a fresh wallet decode carries RestoreWallet.
                        persistableWalletProvider.retryRead(recoveredWallet = recoveryWallet)
                    } else {
                        Twig.info { "Seed-mismatch recovery: wallet changed during SDK erase, restore skipped" }
                    }
                }
            }
        } finally {
            notice.cancel()
        }
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val PREFERENCE_READ_ATTEMPTS = 3
        private val ERASE_NOTICE_DELAY = 30.seconds
    }
}

/**
 * Thrown when the SDK reports that an erase failed or [RecoverFromSeedMismatchUseCase.MAX_RECOVERY_ATTEMPTS]
 * is exhausted.
 */
class SeedMismatchRecoveryException(
    val failedAttempts: Int
) : Exception("Seed-mismatch recovery failed after $failedAttempts attempt(s)")
