package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Erases only the inactive SDK database while the app retains the encrypted wallet record. */
class WalletSeedRecovery(
    private val context: Context,
    private val coordinator: WalletCoordinator,
    private val transportGate: WalletSdkTransportGate,
    private val resetProgressStore: ResetProgressStore,
) {
    // Call under the SDK recovery fence. The public erasers also check native ownership.
    internal suspend fun eraseAndRestore(
        network: ZcashNetwork,
        beforeErase: () -> Unit,
        prepareRestore: suspend () -> Boolean,
    ): Boolean? {
        if (transportGate.restartRequired.value || !coordinator.isSeedMismatch.value) return null
        return transportGate.whileRecoveringSeed {
            val inactive = withTimeoutOrNull(5.seconds) {
                transportGate.awaitSeedRecoveryWithdrawal()
                coordinator.isSeedMismatch.first { !it }
                coordinator.synchronizer.first { it == null }
                true
            }
            if (inactive != true) return@whileRecoveringSeed false
            withContext(Dispatchers.IO) {
                var outcome: Boolean? = false
                withTimeoutOrNull(30.seconds) {
                    resetProgressStore.withMigrationInputStopped({ transportGate.inputSuspended }) {
                        // Bound preparation. An admitted erase drains and records its result.
                        withContext(NonCancellable) {
                            outcome = if (prepareRestore()) {
                                beforeErase()
                                runCatchingRecoverable { eraseInactiveSdkData(context, network) }.isSuccess
                            } else {
                                null
                            }
                        }
                    }
                }
                outcome
            }
        }
    }
}
