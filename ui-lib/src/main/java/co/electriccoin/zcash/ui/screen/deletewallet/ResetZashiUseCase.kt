package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.GiftCardRepository
import co.electriccoin.zcash.ui.common.repository.HomeMessageCacheRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.internal.closeQuietly
import kotlin.time.Duration.Companion.seconds

class ResetZashiUseCase(
    private val walletCoordinator: WalletCoordinator,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider,
    private val homeMessageCacheRepository: HomeMessageCacheRepository,
    private val biometricRepository: BiometricRepository,
    private val addressBookRepository: AddressBookRepository,
    private val metadataRepository: MetadataRepository,
    private val migrationAppHooks: MigrationAppHooks,
    private val giftCardRepository: GiftCardRepository,
    private val progressStore: ResetProgressStore,
) {
    private val resetMutex = Mutex()
    private val running = MutableStateFlow(false)
    val isRunning = running.asStateFlow()
    val isPending = progressStore.isPending

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend operator fun invoke(keepFiles: Boolean) {
        if (!resetMutex.tryLock()) return
        running.value = true
        try {
            val stage = progressStore.getStage()
            if (stage == null) requestBiometrics()

            // The pending screen can replace the initiating screen. Finish each confirmed attempt
            // even if that transition cancels the first view model.
            withContext(NonCancellable) {
                prepareReset(keepFiles, stage)
                if (stage != ResetStage.CLEAR_PREFERENCES) {
                    closeSynchronizer()
                    clearSDK()
                }
                progressStore.store(ResetStage.CLEAR_PREFERENCES)
                clearSharedPrefs()
                homeMessageCacheRepository.reset()
                progressStore.store(null)
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

    private suspend fun prepareReset(keepFiles: Boolean, stage: ResetStage?) {
        val prepared = withTimeoutOrNull(PREPARE_RESET_TIMEOUT) {
            if (stage == null) migrationAppHooks.cancelMigrationWork()
            giftCardRepository.quiesceForWalletReset()
            if (stage == null && !keepFiles) {
                addressBookRepository.delete()
                metadataRepository.delete()
            }
            true
        }
        check(prepared == true) { "Timed out preparing wallet reset" }
        if (stage == null) progressStore.store(ResetStage.ERASE_SDK)
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

    /**
     * Waits for a synchronizer to close, bounded by [CLOSE_SYNCHRONIZER_TIMEOUT]. A latched seed
     * mismatch ([WalletCoordinator.isSeedMismatch]) keeps [SynchronizerProvider.getSynchronizer]'s
     * underlying flow null forever, which would otherwise hang this step indefinitely after
     * local files were already removed. When no synchronizer materializes in time, this is a no-op —
     * [clearSDK]'s lockout-aware erase still handles teardown of any lingering SDK state.
     */
    private suspend fun closeSynchronizer() {
        withTimeoutOrNull(CLOSE_SYNCHRONIZER_TIMEOUT) { synchronizerProvider.getSynchronizer() }
            ?.let { (it as CloseableSynchronizer).closeQuietly() }
    }

    private suspend fun clearSDK() {
        val erased = withTimeoutOrNull(ERASE_SDK_TIMEOUT) { walletCoordinator.deleteSdkDataFlow().first() }
        check(erased == true) { "Could not erase wallet data" }
    }

    private suspend fun clearSharedPrefs() {
        check(standardPreferenceProvider().clearPreferences()) { "Could not clear wallet settings" }
        val encryptedPreferences = encryptedPreferenceProvider()
        check(encryptedPreferenceProvider.purgeQuarantine()) { "Could not remove encrypted preference quarantine" }
        check(encryptedPreferences.clearPreferences()) { "Could not clear encrypted wallet preferences" }
    }

    companion object {
        private val PREPARE_RESET_TIMEOUT = 30.seconds
        private val ERASE_SDK_TIMEOUT = 30.seconds
        private val CLOSE_SYNCHRONIZER_TIMEOUT = 5.seconds
    }
}
