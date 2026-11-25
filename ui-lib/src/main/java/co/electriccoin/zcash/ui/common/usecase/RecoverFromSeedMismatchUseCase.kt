package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.WalletInitMode
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Recovers from the SDK's `SeedNotRelevant` state ([WalletCoordinator.isSeedMismatch]) by erasing the SDK
 * databases through the lockout-aware [WalletCoordinator.deleteSdkDataFlow] (wallet secrets are untouched)
 * and re-storing the wallet with [WalletInitMode.RestoreWallet] so the next synchronizer build rescans.
 * A failed or timed-out erase throws [SeedMismatchRecoveryException]; after [MAX_RECOVERY_ATTEMPTS]
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

    suspend operator fun invoke(version: Long) {
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return
        walletWriteAccess.withSdkRecovery(version) { eraseSdkData(version) } ?: return
        val recoveryWallet = wallet.copy(walletInitMode = WalletInitMode.RestoreWallet)
        if (!persistableWalletProvider.storeIfCurrent(wallet, recoveryWallet, version)) {
            Twig.info { "Seed-mismatch recovery: wallet changed during SDK erase, restore skipped" }
        }
    }

    @Suppress("ThrowsCount")
    private suspend fun eraseSdkData(version: Long) {
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

        val erased =
            try {
                withTimeout(ERASE_TIMEOUT) { walletCoordinator.deleteSdkDataFlow().first() }
            } catch (e: TimeoutCancellationException) {
                failedAttempts += 1
                Twig.error(e) {
                    "Seed-mismatch recovery: deleteSdkDataFlow timed out after $ERASE_TIMEOUT " +
                        "(attempt $failedAttempts/$MAX_RECOVERY_ATTEMPTS)"
                }
                throw SeedMismatchRecoveryException(failedAttempts)
            }
        if (!erased) {
            failedAttempts += 1
            Twig.error {
                "Seed-mismatch recovery: deleteSdkDataFlow reported failure " +
                    "(attempt $failedAttempts/$MAX_RECOVERY_ATTEMPTS)"
            }
            throw SeedMismatchRecoveryException(failedAttempts)
        }

        failedAttempts = 0

    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private val ERASE_TIMEOUT = 30.seconds
    }
}

/**
 * Thrown when the SDK erase fails, times out, or [RecoverFromSeedMismatchUseCase.MAX_RECOVERY_ATTEMPTS]
 * is exhausted.
 */
class SeedMismatchRecoveryException(
    val failedAttempts: Int
) : Exception("Seed-mismatch recovery failed after $failedAttempts attempt(s)")
