package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import co.electriccoin.zcash.migration.BuildConfig
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.common.usecase.MigrationWorkerRunState
import co.electriccoin.zcash.ui.common.usecase.migrationWorkerRunState
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import org.koin.core.context.GlobalContext
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * No iOS-style margin/cadence-reset constants (first +30 min, then +6.5 h) are ported here on
 * purpose: unlike iOS's BGProcessingTask, [androidx.work.WorkManager.enqueueUniqueWork] with
 * [androidx.work.OneTimeWorkRequest.Builder.setInitialDelay] is driven directly and
 * deterministically from the SDK's authoritative `scheduledAt`/height-derived time — there's no
 * OS-level "best-effort earliest begin" uncertainty to buffer against. The real Android-side
 * timing risk is Doze/App-Standby deferral, and that's already handled by the overdue-recovery
 * self-healing path (`CheckMigrationRecoveryUseCase` + `hasOverdueTransfers()`), not by adding
 * scheduling margins here — except for the specific case where the app can't run in the
 * background at all (see [MigrationDueAlarmScheduler]), where an inexact-while-idle `AlarmManager`
 * alarm is armed/cancelled alongside the WorkManager job below purely to surface a "ready to send"
 * notification (spec §6.4), never to run the transfer itself.
 */
class MigrationScheduler(
    private val context: Context,
) {
    private val resetPending = {
        GlobalContext.getOrNull()?.getOrNull<ResetProgressStore>()?.isPending?.value == true ||
            ResetProgressStore.hasPendingMarker(context)
    }
    // Armed/cancelled alongside the WorkManager job below rather than threaded through every call
    // site separately — see MigrationDueAlarmScheduler's kdoc for why this needs its own
    // AlarmManager alarm instead of relying on the WorkManager job itself.
    private val migrationDueAlarmScheduler = MigrationDueAlarmScheduler(context)

    private data class Wake(
        val request: UUID,
        val attempt: Int,
        val dueAt: Long,
    )

    internal suspend fun scheduleAndAwait(accountKeyId: String, delay: Duration, expectedVersion: Long? = null) {
        val startsPlan = expectedVersion == null
        val enqueued = enqueueConfirmed(accountKeyId, delay, ExistingWorkPolicy.REPLACE, expectedVersion, startsPlan)
        check(enqueued || !startsPlan) { "Migration scheduling is paused" }
    }

    internal suspend fun scheduleIfMissing(
        accountKeyId: String,
        delay: Duration = Duration.ZERO,
        expectedVersion: Long? = null,
    ) {
        enqueueConfirmed(accountKeyId, delay, ExistingWorkPolicy.KEEP, expectedVersion)
    }

    internal suspend fun scheduleSuccessorAndAwait(accountKeyId: String, delay: Duration, version: Long) {
        if (resetPending() || wakeVersion(accountKeyId) != version) return
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(workId(accountKeyId)).await()
        // An interrupted worker may already have committed its child before acknowledging it.
        val existing = work.firstOrNull { it.state == WorkInfo.State.BLOCKED }
        if (existing == null) {
            val enqueued = enqueueConfirmed(accountKeyId, delay, ExistingWorkPolicy.APPEND, version)
            check(enqueued || wakeVersion(accountKeyId) != version) { "Migration scheduling is paused" }
        } else {
            armFallback(accountKeyId, version) {
                Wake(existing.id, existing.runAttemptCount, System.currentTimeMillis() + existing.initialDelayMillis)
            }
        }
    }

    internal suspend fun reconcileScheduledWake(accountKeyId: String, version: Long = wakeVersion(accountKeyId)) {
        if (resetPending()) return
        val manager = WorkManager.getInstance(context)
        val pending = manager.getWorkInfosForUniqueWork(workId(accountKeyId)).await()
            .filter { it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis != Long.MAX_VALUE }
            .minByOrNull { it.nextScheduleTimeMillis }
        if (pending != null) armFallback(accountKeyId, version) {
            Wake(pending.id, pending.runAttemptCount, pending.nextScheduleTimeMillis)
        }
    }

    private suspend fun enqueueConfirmed(
        accountKeyId: String,
        delay: Duration,
        policy: ExistingWorkPolicy,
        expectedVersion: Long? = null,
        startsPlan: Boolean = false,
    ): Boolean {
        val manager by lazy { WorkManager.getInstance(context) }
        val request by lazy { newWorkRequest(accountKeyId, delay) }
        val (operation, version) = MigrationAccountRetirement.keepWake(accountKeyId) {
            synchronized(wakeVersions) {
                if (resetPending()) return@synchronized null
                val current = wakeVersion(accountKeyId)
                if (startsPlan) completedPlans.remove(accountKeyId)
                if (accountKeyId in completedPlans || expectedVersion != null && expectedVersion != current) {
                    null
                } else {
                    val admitted =
                        if (policy == ExistingWorkPolicy.REPLACE) advanceWakeVersion(accountKeyId) else current
                    manager.enqueueUniqueWork(workId(accountKeyId), policy, request) to admitted
                }
            }
        } ?: return false
        operation.result.await()
        armFallback(accountKeyId, version) {
            if (policy == ExistingWorkPolicy.APPEND) {
                Wake(request.id, 0, System.currentTimeMillis() + delay.inWholeMilliseconds)
            } else {
                val pending = manager.getWorkInfoById(request.id).await()
                if (pending == null && policy == ExistingWorkPolicy.KEEP) reconcileScheduledWake(accountKeyId, version)
                pending?.takeIf { it.state == WorkInfo.State.ENQUEUED && it.nextScheduleTimeMillis != Long.MAX_VALUE }
                    ?.let { Wake(request.id, it.runAttemptCount, it.nextScheduleTimeMillis) }
            }
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught") // The wake is already durable. An alarm failure must not enqueue again.
    private suspend fun armFallback(accountKeyId: String, version: Long, resolve: suspend () -> Wake?) {
        if (resetPending()) return
        try {
            val wake = resolve() ?: return
            MigrationAccountRetirement.keepWake(accountKeyId) {
                synchronized(wakeVersions) {
                    if (resetPending()) return@synchronized
                    val stillPending = accountKeyId !in completedPlans && wakeVersion(accountKeyId) == version &&
                        !MigrationWorkerHeartbeat.hasStarted(context, accountKeyId, wake.request, wake.attempt)
                    if (stillPending) {
                        migrationLog("MigrationScheduler: arming the fallback for the next migration wake.")
                        MigrationWorkerHeartbeat.stampScheduled(
                            context, accountKeyId, wake.request, wake.attempt, wake.dueAt
                        )
                        val delay = (wake.dueAt - System.currentTimeMillis()).milliseconds + WORKER_LATE_MARGIN
                        migrationDueAlarmScheduler.schedule(accountKeyId, delay.coerceAtLeast(Duration.ZERO))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            migrationLog("MigrationScheduler: fallback alarm unavailable (${e.javaClass.simpleName}).")
        }
    }

    fun cancel(accountKeyId: String) {
        cancelWork(accountKeyId)
    }

    suspend fun cancelAndWait(accountKeyId: String) {
        cancelWork(accountKeyId).result.await()
    }

    suspend fun cancelWalletWorkAndWait() {
        val cancellation = synchronized(wakeVersions) {
            WorkManager.getInstance(context).cancelAllWorkByTag(MigrationWorker::class.java.name)
        }
        cancellation.result.await()
    }

    private fun cancelWork(accountKeyId: String): Operation = synchronized(wakeVersions) {
        advanceWakeVersion(accountKeyId)
        completedPlans.remove(accountKeyId)
        migrationLog("MigrationScheduler: cancelling a migration work chain.")
        val operation = WorkManager.getInstance(context).cancelUniqueWork(workId(accountKeyId))
        migrationDueAlarmScheduler.cancel(accountKeyId)
        MigrationWorkerHeartbeat.clear(context, accountKeyId)
        operation
    }

    companion object {
        // A resumed WorkManager read cannot arm an alarm for a chain already replaced or cancelled.
        private val wakeVersions = mutableMapOf<String, Long>()
        private val completedPlans = mutableSetOf<String>()

        internal fun wakeVersion(accountKeyId: String): Long =
            synchronized(wakeVersions) { wakeVersions[accountKeyId] ?: 0L }

        internal fun recordWorkerStart(context: Context, accountKeyId: String, requestId: UUID, attempt: Int): Long? =
            synchronized(wakeVersions) {
                if (accountKeyId in completedPlans) {
                    null
                } else {
                    MigrationWorkerHeartbeat.stampRun(context, accountKeyId, requestId, attempt)
                    advanceWakeVersion(accountKeyId)
                }
            }

        internal suspend fun armRetry(
            context: Context,
            accountKeyId: String,
            requestId: UUID,
            startedAttempt: Int,
            version: Long,
        ) {
            // The WorkManager retry reuses this UUID and starts its configured default backoff
            // when doWork returns. The alarm's late margin absorbs that handoff interval.
            MigrationScheduler(context).armFallback(accountKeyId, version) {
                val multiplier = 1L shl startedAttempt.coerceIn(0, RETRY_BACKOFF_SHIFT_LIMIT)
                val backoff = WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS * multiplier
                val dueAt = System.currentTimeMillis() + backoff.coerceAtMost(WorkRequest.MAX_BACKOFF_MILLIS)
                Wake(requestId, startedAttempt + 1, dueAt)
            }
        }

        internal fun withdrawCompletedFallback(
            context: Context,
            accountKeyId: String,
            onCleared: () -> Unit,
        ) {
            MigrationAccountRetirement.admit(accountKeyId) {
                synchronized(wakeVersions) {
                    completedPlans.add(accountKeyId)
                    advanceWakeVersion(accountKeyId)
                    MigrationDueAlarmScheduler(context).cancel(accountKeyId)
                    MigrationWorkerHeartbeat.clear(context, accountKeyId)
                    onCleared()
                }
            }
        }

        private fun advanceWakeVersion(accountKeyId: String): Long =
            (wakeVersion(accountKeyId) + 1).also { wakeVersions[accountKeyId] = it }

        /**
         * How long past the expected worker run the fallback alarm waits before concluding the
         * worker is dead — absorbs ordinary WorkManager dispatch slack without false alarms.
         */
        val WORKER_LATE_MARGIN = 2.minutes

        private const val RETRY_BACKOFF_SHIFT_LIMIT = 20

        const val WORK_ID_PREFIX = "co.electriccoin.zcash.migration_transfer"
        const val KEY_ACCOUNT_KEY_ID = "co.electriccoin.zcash.migration.work_account_key_id"

        fun workId(accountKeyId: String): String = "${WORK_ID_PREFIX}_$accountKeyId"

        fun newWorkRequest(accountKeyId: String, delay: Duration): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MigrationWorker>()
                .setConstraints(workConstraints())
                .setInitialDelay(delay.toJavaDuration())
                .setInputData(workDataOf(KEY_ACCOUNT_KEY_ID to accountKeyId))
                .build()

        private fun workConstraints(): Constraints =
            if (BuildConfig.DEBUG) {
                Constraints.NONE
            } else {
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            }
    }
}
