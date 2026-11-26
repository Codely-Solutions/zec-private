package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.WalletSeedRecovery
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess

/** Makes one read-only retry for a wallet session. A recurring mismatch needs attention. */
class RecoverFromSeedMismatchUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val sdkRecovery: WalletSeedRecovery,
    private val walletWriteAccess: WalletWriteAccess,
) {
    private var attemptedVersion: Long? = null

    suspend operator fun invoke(version: Long) {
        if (!walletWriteAccess.permits(version) || persistableWalletProvider.getPersistableWallet() == null) return
        walletWriteAccess.withSdkRecovery(version) {
            if (attemptedVersion == version) throw SeedMismatchRecoveryException()
            val reopened = sdkRecovery.reopen({ walletWriteAccess.permits(version) }) {
                attemptedVersion = version
                persistableWalletProvider.retryRead()
            }
            if (reopened != null) attemptedVersion = version
            if (reopened == false) throw SeedMismatchRecoveryException()
        }
    }
}

internal class SeedMismatchRecoveryException : IllegalStateException("The stored wallet could not be reopened")
