package co.electriccoin.zcash.work

import android.content.Context
import androidx.core.content.edit

/**
 * Two plain-SharedPreferences timestamps forming the worker's dead-man's switch (design with
 * the product owner, 2026-07-30 late evening): [stampScheduled] records when the NEXT worker run is
 * expected (written by [MigrationScheduler.schedule]); [stampRun] records when a worker run
 * actually STARTED. [MigrationTransferDueReceiver]'s alarm fires a late-margin after the expected
 * time and compares the two — a worker that ran clears the check; one the OS silently killed or
 * deferred (Doze, OEM killers, background restrictions) trips it and raises the fallback
 * "step due" notification whose tap re-kicks the worker through the app.
 *
 * OS-plumbing telemetry only — nothing about the migration plan is persisted here (that stays
 * engine-owned); plain prefs (not encrypted) on purpose: two wall-clock instants, no secrets.
 */
object MigrationWorkerHeartbeat {
    /** Accounts with app-owned fallback state, including accounts unavailable from the SDK during reset. */
    fun accountKeyIds(context: Context): Set<String> =
        prefs(context).all.keys.mapNotNull { key ->
            when {
                key.startsWith(KEY_NEXT_WAKE) -> key.removePrefix(KEY_NEXT_WAKE)
                key.startsWith(KEY_LAST_RUN) -> key.removePrefix(KEY_LAST_RUN)
                key.startsWith(KEY_REMOVAL_PENDING) -> key.removePrefix(KEY_REMOVAL_PENDING)
                else -> null
            }?.takeIf { it.isNotEmpty() }
        }.toSet()

    fun stampScheduled(context: Context, accountKeyId: String, dueAtEpochMillis: Long) {
        prefs(context).edit { putLong(KEY_NEXT_WAKE + accountKeyId, dueAtEpochMillis) }
    }

    fun stampRun(context: Context, accountKeyId: String) {
        prefs(context).edit { putLong(KEY_LAST_RUN + accountKeyId, System.currentTimeMillis()) }
    }

    fun nextWakeAt(context: Context, accountKeyId: String): Long? =
        prefs(context).getLong(KEY_NEXT_WAKE + accountKeyId, -1L).takeIf { it >= 0 }

    fun lastRunAt(context: Context, accountKeyId: String): Long? =
        prefs(context).getLong(KEY_LAST_RUN + accountKeyId, -1L).takeIf { it >= 0 }

    internal fun pendingRemovalAccountKeyIds(context: Context): Set<String> =
        prefs(context).all.mapNotNull { (key, value) ->
            key.takeIf { it.startsWith(KEY_REMOVAL_PENDING) && value == true }
                ?.removePrefix(KEY_REMOVAL_PENDING)?.takeIf { it.isNotEmpty() }
        }.toSet()

    internal fun removalPending(context: Context, accountKeyId: String): Boolean =
        prefs(context).getBoolean(KEY_REMOVAL_PENDING + accountKeyId, false)

    /** Record before native deletion; a restarted process must resolve ownership before notifying. */
    internal fun recordRemovalPending(context: Context, accountKeyId: String): Boolean =
        prefs(context).edit().putBoolean(KEY_REMOVAL_PENDING + accountKeyId, true).commit()

    internal fun clearRemovalPending(context: Context, accountKeyId: String) {
        if (removalPending(context, accountKeyId)) {
            prefs(context).edit().remove(KEY_REMOVAL_PENDING + accountKeyId).commit()
        }
    }

    fun clear(context: Context, accountKeyId: String) {
        prefs(context).edit {
            remove(KEY_NEXT_WAKE + accountKeyId)
            remove(KEY_LAST_RUN + accountKeyId)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE)

    private const val KEY_NEXT_WAKE = "next_wake_at_"
    private const val KEY_LAST_RUN = "last_run_at_"
    private const val KEY_REMOVAL_PENDING = "removal_pending_"
}
