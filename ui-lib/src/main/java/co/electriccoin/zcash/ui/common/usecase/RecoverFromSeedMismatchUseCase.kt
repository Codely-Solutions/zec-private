package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Recovers from the SDK's `SeedNotRelevant` state ([WalletCoordinator.isSeedMismatch]) by erasing the SDK
 * databases through the lockout-aware [WalletCoordinator.deleteSdkDataFlow] (wallet secrets are untouched)
 * and re-storing the wallet with [WalletInitMode.RestoreWallet] so the next synchronizer build rescans.
 * A reported failure throws [SeedMismatchRecoveryException]; after [MAX_RECOVERY_ATTEMPTS]
 * failures no further erase is attempted for that wallet generation. Wallet reset and replacement drain
 * admitted SDK erases. Re-storing also verifies that the wallet snapshot is current.
 */
class RecoverFromSeedMismatchUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val walletCoordinator: WalletCoordinator,
    private val walletWriteAccess: WalletWriteAccess,
) {
    private var attemptVersion: Long? = null
    private var failedAttempts = 0

    suspend operator fun invoke(version: Long, onEraseDelayed: () -> Unit = {}) {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return
        walletWriteAccess.withSdkRecovery(version) { eraseSdkData(version, onEraseDelayed) } ?: return
        val recoveryWallet = wallet.copy(walletInitMode = WalletInitMode.RestoreWallet)
        if (!persistableWalletProvider.storeIfCurrent(wallet, recoveryWallet, version)) {
            Twig.info { "Seed-mismatch recovery: wallet changed during SDK erase, restore skipped" }
        }
    }

    private suspend fun eraseSdkData(version: Long, onEraseDelayed: () -> Unit) {
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

        if (!waitForErase(onEraseDelayed)) {
            failedAttempts += 1
            Twig.error {
                "Seed-mismatch recovery: deleteSdkDataFlow reported failure " +
                    "(attempt $failedAttempts/$MAX_RECOVERY_ATTEMPTS)"
            }
            throw SeedMismatchRecoveryException(failedAttempts)
        }

        failedAttempts = 0

    }

    private suspend fun waitForErase(onEraseDelayed: () -> Unit): Boolean = coroutineScope {
        val notice = launch {
            delay(ERASE_NOTICE_DELAY)
            Twig.info { "Seed-mismatch recovery: waiting for the SDK erase result" }
            onEraseDelayed()
        }
        try {
            // SDK 6.0.0 starts the erase on its own wallet scope. Keep the app fence until it reports completion.
            withContext(NonCancellable) { walletCoordinator.deleteSdkDataFlow().first() }
        } finally {
            notice.cancel()
        }
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
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
