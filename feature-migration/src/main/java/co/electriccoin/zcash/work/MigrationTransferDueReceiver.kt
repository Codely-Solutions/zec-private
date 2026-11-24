package co.electriccoin.zcash.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The worker's DEAD-MAN'S SWITCH (design with the product owner, 2026-07-30): an inexact-while-idle alarm
 * armed by [MigrationScheduler] a late margin PAST the worker's expected run. By construction,
 * firing means the OS had its chance to run the worker and didn't (Doze, App-Standby, OEM
 * killers, user-disabled background) — so this compares the [MigrationWorkerHeartbeat] stamps
 * and, if the worker really did not run, raises the generic STEP-DUE fallback notification
 * (prove and broadcast alike — everything is pre-signed, there is nothing to review). Tapping it
 * opens the app, which re-kicks the worker; the worker's own next run start cancels the
 * notification again.
 *
 * Reads the reset and account-removal guards and the existing heartbeat. It can run without
 * the network, wallet database or synchronizer.
 */
class MigrationTransferDueReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val migrationNotifier: MigrationNotifier by inject()
    private val resetProgress: ResetProgressStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val accountKeyId = intent.getStringExtra(MigrationDueAlarmScheduler.EXTRA_ACCOUNT_KEY_ID)
        if (accountKeyId == null) {
            migrationLog("MigrationStepDueReceiver: no accountKeyId extra (pre-upgrade alarm) — no-op.")
            return
        }

        MigrationAccountRetirement.keepWake(accountKeyId) {
            if (resetProgress.isPending.value) return@keepWake
            val expectedRunAt = MigrationWorkerHeartbeat.nextWakeAt(context, accountKeyId)
            val lastRunAt = MigrationWorkerHeartbeat.lastRunAt(context, accountKeyId)
            if (migrationWorkerIsLate(System.currentTimeMillis(), expectedRunAt, lastRunAt)) {
                val removalUnresolved = MigrationWorkerHeartbeat.removalPending(context, accountKeyId)
                migrationLog("MigrationStepDueReceiver: missed worker run — posting migration fallback.")
                migrationNotifier.notifyMigrationFallback(accountKeyId, removalUnresolved)
            }
        }
    }

}

private const val EARLY_WORKER_TOLERANCE_MILLIS = 30_000L

internal fun migrationWorkerIsLate(now: Long, expectedRunAt: Long?, lastRunAt: Long?): Boolean =
    expectedRunAt != null &&
        expectedRunAt <= now - MigrationScheduler.WORKER_LATE_MARGIN.inWholeMilliseconds &&
        (lastRunAt == null || lastRunAt < expectedRunAt - EARLY_WORKER_TOLERANCE_MILLIS)
