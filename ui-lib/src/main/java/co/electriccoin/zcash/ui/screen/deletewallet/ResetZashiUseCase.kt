package co.electriccoin.zcash.ui.screen.deletewallet

import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.GiftWalletRestartRequiredException
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.GiftCardRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.usecase.DeleteLegacySharedExportsUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteSharedQRImagesUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class ResetZashiUseCase(
    private val resetWalletSdkData: ResetWalletSdkDataUseCase,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val biometricRepository: BiometricRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val migrationAppHooks: MigrationAppHooks,
    private val giftCardRepository: GiftCardRepository,
    private val progressStore: ResetProgressStore,
    private val deleteSharedQRImages: DeleteSharedQRImagesUseCase,
    private val deleteLegacySharedExports: DeleteLegacySharedExportsUseCase,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) {
    private val resetMutex = Mutex()
    private val running = MutableStateFlow(false)
    private val pendingFailure = MutableStateFlow<PendingResetFailure?>(null)
    val isRunning = running.asStateFlow()
    val isPending = progressStore.isPending
    internal val failedAttempt = pendingFailure.asStateFlow()

    internal fun dismissFailure(failure: PendingResetFailure) = pendingFailure.compareAndSet(failure, null)

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend operator fun invoke(keepFiles: Boolean) {
        if (!resetMutex.tryLock()) return
        running.value = true
        pendingFailure.value = null
        try {
            val stage = runCatchingRecoverable { progressStore.getStage() }
                .onFailure(::reportPendingFailure)
                .getOrThrow()
            if (stage == null) requestBiometrics()

            // The pending screen can replace the initiating screen. Finish each confirmed attempt
            // even if that transition cancels the first view model.
            withContext(NonCancellable) {
                runCatchingRecoverable {
                    progressStore.store(stage ?: if (keepFiles) ResetStage.ERASE_SDK else ResetStage.DELETE_LOCAL_FILES)
                    prepareReset(keepFiles, stage)
                    if (stage != ResetStage.CLEAR_PREFERENCES) {
                        progressStore.suppressSdkInput()
                        resetWalletSdkData()
                    }
                    progressStore.store(ResetStage.CLEAR_PREFERENCES)
                    clearSharedPrefs()
                    homeMessageCacheRepository.reset()
                    progressStore.store(null)
                    walletWriteAccess.finishReset()
                }.onFailure(::reportPendingFailure).getOrThrow()
            }
        } catch (_: BiometricsFailureException) {
            // Wait for another confirmation.
        } catch (_: BiometricsCancelledException) {
            // Wait for another confirmation.
        } finally {
            if (!progressStore.isPending.value) giftCardRepository.resumeSessions()
            running.value = false
            resetMutex.unlock()
        }
    }

    internal class PendingResetFailure(val requiresRestart: Boolean)

    private fun reportPendingFailure(cause: Throwable) {
        if (isPending.value) {
            pendingFailure.value = PendingResetFailure(requiresRestart = cause is GiftWalletRestartRequiredException)
        }
    }

    private suspend fun prepareReset(keepFiles: Boolean, stage: ResetStage?) {
        val prepared = withTimeoutOrNull(PREPARE_RESET_TIMEOUT) {
            walletWriteAccess.beginReset()
            if (stage != ResetStage.CLEAR_PREFERENCES) migrationAppHooks.cancelMigrationWork()
            giftCardRepository.quiesceForWalletReset()
            deleteSharedQRImages.deleteViewingKeyImages()
            deleteLegacySharedExports()
            if (stage == ResetStage.DELETE_LOCAL_FILES || (stage == null && !keepFiles)) {
                addressBookRepository.delete()
                metadataRepository.delete()
                progressStore.store(ResetStage.ERASE_SDK)
            }
            true
        }
        check(prepared == true) { "Timed out preparing wallet reset" }
    }

    private suspend fun requestBiometrics() {
        biometricRepository.requestBiometrics(
            BiometricRequest(
                message =
                    stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_delete_wallet)
                    )
            )
        )
    }

    private suspend fun clearSharedPrefs() {
        val encryptedPreferences = encryptedPreferenceProvider()
        check(encryptedPreferenceProvider.purgeQuarantine()) { "Could not remove encrypted preference quarantine" }
        check(encryptedPreferences.clearPreferences()) { "Could not clear encrypted wallet preferences" }
        check(standardPreferenceProvider().clearPreferences()) { "Could not clear wallet settings" }
    }

    companion object {
        private val PREPARE_RESET_TIMEOUT = 30.seconds
    }
}
