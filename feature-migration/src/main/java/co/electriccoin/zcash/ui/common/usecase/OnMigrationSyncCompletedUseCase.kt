package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.work.MigrationScheduler
import kotlin.time.Duration.Companion.seconds

/**
 * Called on every foreground Status.SYNCED transition while a migration plan is active.
 * Finalizes any transfers whose funding note became witnessed since the last sync, then
 * checks for invalidations — if any, notifies the user and cancels the background worker
 * chain. The synchronizer provider records wallet-wide network activity before invoking this hook.
 */
class OnMigrationSyncCompletedUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val migrationNotifier: MigrationNotifier,
    private val migrationScheduler: MigrationScheduler,
    private val context: Context,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) {
    suspend operator fun invoke(accountKeyId: String) {
        walletWriteAccess.write(walletWriteAccess.token()) { reconcileAfterSync(accountKeyId) }
    }

    private suspend fun reconcileAfterSync(accountKeyId: String) {
        val sdk = getOrchardMigrationSdk(accountKeyId) ?: return
        val proved = sdk.finalizeReadyTransfers()
        val invalidated = sdk.reconcileInvalidations()
        migrationLog("ForegroundHook: SYNCED — proved=$proved, invalidated=$invalidated")
        if (invalidated) {
            migrationNotifier.notifyMigrationPlanInvalid(accountKeyId)
            migrationScheduler.cancel(accountKeyId)
            return
        }
        // The worker may have committed just before the scheduling process stopped.
        val wakeVersion = MigrationScheduler.wakeVersion(accountKeyId)
        if (sdk.getMigrationStateUnreconciled() is cash.z.ecc.android.sdk.MigrationState.InProgress) {
            when (migrationWorkerRunState(context, accountKeyId)) {
                MigrationWorkerRunState.ABSENT ->
                    migrationScheduler.scheduleIfMissing(accountKeyId, 60.seconds, wakeVersion)
                MigrationWorkerRunState.SCHEDULED ->
                    migrationScheduler.reconcileScheduledWake(accountKeyId, wakeVersion)
                MigrationWorkerRunState.RUNNING -> Unit
            }
        }
    }
}
