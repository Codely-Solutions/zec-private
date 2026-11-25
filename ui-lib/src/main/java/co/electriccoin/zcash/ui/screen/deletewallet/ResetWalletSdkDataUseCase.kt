package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.eraseInactiveSdkData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Called under Reset Zashi's single admitted, durably pending reset. */
class ResetWalletSdkDataUseCase(
    private val context: Context,
    private val coordinator: WalletCoordinator,
    private val preferenceRecovery: SdkEncryptedPreferenceRecoveryProvider,
    private val progressStore: ResetProgressStore,
) {
    suspend operator fun invoke() {
        val network = ZcashNetwork.fromResources(context)
        val ready = withTimeoutOrNull(5.seconds) {
            coordinator.synchronizer.first { it == null }
            true
        }
        check(ready == true) { "Still waiting for the wallet SDK to close" }
        // The public erasers enforce native instance ownership even if the flow was already null.
        val erased = withTimeoutOrNull(30.seconds) {
            withContext(Dispatchers.IO) {
                progressStore.withMigrationInputStopped {
                    preferenceRecovery.ensureReadable()
                    eraseInactiveSdkData(context, network)
                }
            }
            true
        }
        check(erased == true) { "Could not confirm SDK wallet data erasure" }
    }
}
