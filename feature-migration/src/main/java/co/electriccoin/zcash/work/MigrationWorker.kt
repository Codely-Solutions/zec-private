package co.electriccoin.zcash.work

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The single migration execution worker — the app-side half of the engine-driven loop
 * (spec: 2026-07-30-engine-state-machine-adoption-design.md).
 *
 * A thin WorkManager wrapper around [MigrationDriveOnce] — the shared "what now, do it, re-arm"
 * decision-and-execution logic lives there so a future live-process driver can call the exact same
 * logic without ever executing concurrently with this worker (both share [MigrationDriveOnce]'s
 * process-wide lock). This class only supplies WorkManager-specific plumbing: resolving the
 * account/SDK, translating [DriveOnceResult] into [Result], and the kill-switch/not-ready retry
 * handling around SDK lookup.
 */
@Keep
class MigrationWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters),
    KoinComponent {
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase by inject()
    private val migrationNotifier: MigrationNotifier by inject()
    private val migrationDriveOnce: MigrationDriveOnce by inject()
    private val resetProgress: ResetProgressStore by inject()

    override suspend fun doWork(): Result {
        // Old unbound work cannot recover an owner from the foreground selection.
        // Normal startup restores committed work for each actual wallet account.
        val accountKeyId = inputData.getString(MigrationScheduler.KEY_ACCOUNT_KEY_ID)
        if (accountKeyId.isNullOrBlank() || resetProgress.isPending.value) return Result.success()

        return when (val lookup = getOrchardMigrationSdk.lookup(accountKeyId)) {
            is MigrationSdkLookup.Ready -> driveMigration(lookup.sdk, accountKeyId)
            MigrationSdkLookup.RemovalManaged -> retryWhileRemoving(accountKeyId)
            MigrationSdkLookup.NotReady -> waitForSdk(accountKeyId)
            MigrationSdkLookup.Gone -> stopMissingAccount(accountKeyId)
        }
    }

    private fun waitForSdk(accountKeyId: String): Result {
        MigrationAccountRetirement.admit(accountKeyId) {
            migrationNotifier.cancelStepDue(accountKeyId)
            // A pending retry is still a worker wake for the fallback alarm.
            migrationLog("Worker: SDK not ready — retrying via WorkManager backoff.")
            MigrationWorkerHeartbeat.stampRun(applicationContext, accountKeyId)
        } ?: return retryWhileRemoving(accountKeyId)
        return Result.retry()
    }

    private fun stopMissingAccount(accountKeyId: String): Result {
        MigrationAccountRetirement.admit(accountKeyId) {
            migrationNotifier.cancelStepDue(accountKeyId)
            migrationLog("Worker: account/wallet gone — cancelling the migration work chain.")
            MigrationScheduler(applicationContext).cancel(accountKeyId)
            migrationNotifier.cancel(accountKeyId)
            MigrationWorkerHeartbeat.clearRemovalPending(applicationContext, accountKeyId)
        } ?: return retryWhileRemoving(accountKeyId)
        return Result.success()
    }

    private suspend fun driveMigration(sdk: OrchardMigrationSdk, accountKeyId: String): Result {
        MigrationAccountRetirement.admit(accountKeyId) {
            MigrationWorkerHeartbeat.clearRemovalPending(applicationContext, accountKeyId)
            migrationNotifier.cancelStepDue(accountKeyId)
        }
        when (val result = migrationDriveOnce.run(sdk, accountKeyId)) {
            is DriveOnceResult.ReArmed -> {
                // Already re-armed the WorkManager chain inside run() — nothing more to do.
            }

            is DriveOnceResult.LockBusy -> {
                // No step ran, so nothing re-armed the chain — this call must, or a lost lock
                // race silently kills the durable background path.
                if (!resetProgress.isPending.value) {
                    MigrationScheduler(applicationContext).schedule(accountKeyId, result.retryDelay)
                }
            }

            DriveOnceResult.RemovalManaged -> return retryWhileRemoving(accountKeyId)

            DriveOnceResult.Terminal -> {
                // Complete, or a terminal-until-user-acts state — the chain intentionally ends
                // here, matching today's behavior exactly.
            }
        }
        return Result.success()
    }

    private fun retryWhileRemoving(accountKeyId: String): Result =
        if (MigrationAccountRetirement.isRemoved(accountKeyId)) Result.success() else Result.retry()
}
