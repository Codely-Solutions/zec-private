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
 * the SDK input and records [WalletInitMode.RestoreWallet] for the current wallet before
 * erasing inactive data. An interrupted erase leaves enough information to resume provisioning.
 * A reported failure throws [SeedMismatchRecoveryException]. One completed erase or
 * [MAX_RECOVERY_ATTEMPTS] failures end automatic recovery for the wallet session.
 * Wallet reset and replacement drain admitted SDK erases.
 */
class RecoverFromSeedMismatchUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
    private val sdkRecovery: WalletSeedRecovery,
    private val walletWriteAccess: WalletWriteAccess,
    private val preferenceRecovery: SdkEncryptedPreferenceRecoveryProvider,
) {
    private var attemptVersion: Long? = null
    private var failedAttempts = 0
    private var recovered = false

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
            recovered = false
        }
        if (recovered || failedAttempts >= MAX_RECOVERY_ATTEMPTS) {
            throw SeedMismatchRecoveryException(failedAttempts, alreadyErased = recovered)
        }

        val readable = canReadSdkPreferences()
        val attempt = runCatchingRecoverable {
            if (readable) waitForErase(wallet, version, onEraseDelayed) else false
        }
        val erased = attempt.getOrNull()
        if (erased == false || attempt.isFailure) {
            failedAttempts += 1
            Twig.error {
                val stage = when {
                    !readable -> "SDK encrypted preferences unreadable"
                    attempt.isFailure -> "SDK recovery preparation failed"
                    else -> "SDK erase failed"
                }
                "Seed-mismatch recovery: $stage (attempt $failedAttempts/$MAX_RECOVERY_ATTEMPTS)"
            }
            throw SeedMismatchRecoveryException(failedAttempts)
        }

        if (erased == true) recovered = true
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
                    prepareRestore(wallet, version)
                }
            }
        } finally {
            notice.cancel()
        }
    }

    private suspend fun prepareRestore(original: PersistableWallet, version: Long): Boolean {
        val current = persistableWalletProvider.getPersistableWallet()?.takeIf {
            it.seedPhrase == original.seedPhrase && it.network == original.network
        } ?: return false
        val replacement = current.copy(
            birthday = current.birthday ?: current.network.saplingActivationHeight,
            walletInitMode = WalletInitMode.RestoreWallet,
        )
        return persistableWalletProvider.storeIfCurrent(current, replacement, version).also { stored ->
            // Discard cached SDK inputs while the recovery gate still holds the engine closed.
            if (stored) persistableWalletProvider.retryRead()
        }
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val PREFERENCE_READ_ATTEMPTS = 3
        private val ERASE_NOTICE_DELAY = 30.seconds
    }
}

/**
 * Reports a failed recovery attempt or exhaustion of safe automatic recovery.
 */
class SeedMismatchRecoveryException(
    val failedAttempts: Int,
    alreadyErased: Boolean = false,
) : Exception(
    if (alreadyErased) "Seed mismatch persisted after the completed SDK erase"
    else "Seed-mismatch recovery failed after $failedAttempts attempt(s)"
)
