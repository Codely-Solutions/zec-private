package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.global.WalletSdkTransportGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Reopens the same SDK database after an acknowledged withdrawal of its input. */
class WalletSeedRecovery(
    private val coordinator: WalletCoordinator,
    private val transportGate: WalletSdkTransportGate,
) {
    // A seed mismatch supplies no inventory of the accounts or plans in the database.
    internal suspend fun reopen(stillApplies: () -> Boolean, reread: () -> Unit): Boolean? {
        if (!stillApplies() || transportGate.restartRequired.value || !coordinator.isSeedMismatch.value) return null
        return transportGate.whileRecoveringSeed {
            val inactive = withTimeoutOrNull(5.seconds) {
                transportGate.awaitSeedRecoveryWithdrawal()
                coordinator.isSeedMismatch.first { !it }
                coordinator.synchronizer.first { it == null }
                true
            }
            if (inactive != true) return@whileRecoveringSeed false
            if (!stillApplies()) return@whileRecoveringSeed null
            reread()
            true
        }
    }
}
