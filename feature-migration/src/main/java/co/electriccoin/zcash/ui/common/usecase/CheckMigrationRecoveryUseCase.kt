package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationNextAction
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Single source of truth for migration re-entry routing AND for starting/reviving migration
 * execution on app-lifecycle events — MainActivity's onStart() and RootNavGraph's
 * secretState-driven redirect both delegate here instead of calling the SDK checks directly, so
 * the two never drift out of sync with each other or with this ordering. Cheap and idempotent
 * (NavigationRouter dedupes identical commands; the driver-start/worker-revival block below is
 * per-account idempotent too).
 *
 * After Task 6 (remove app-open auto-navigation), the ONLY auto-navigation that fires here is the
 * Tor-failure branch: a pending background Tor failure routes to the Sending screen so that
 * MigrationSendingVM's init{} reproduces the exact condition, using its own existing routing to
 * resolve or re-surface the failure. That branch stays scoped to the currently SELECTED account —
 * it's about what to show the user right now, unlike the driver-start/worker-revival block below.
 *
 * All other migration states (RequiresAttention, ReadyToSend, Overdue, Complete, the
 * unprovable-anchor attention state) are reachable exclusively via the home banner + button
 * (HomeVM.onMigrationMessageClick), preventing repeated screen hijacking on every launch during a
 * healthy migration.
 *
 * The driver-start/worker-revival block enumerates EVERY account (2026-08-0X), not just the
 * selected one: `MigrationLiveDriver`/`MigrationDriveOnce.DRIVE_LOCK` are per-account already, and
 * a migration committed for a Keystone account keeps running whether or not that account is
 * currently selected in the UI — this is the sole place migration execution gets triggered from
 * (besides `FinalizeMigrationScheduleUseCase`'s own post-commit start for the account that was
 * just committed), so it must not silently skip a non-selected account. It and the
 * stale-write-ahead-plan clear are NOT navigation — they are retained unchanged in spirit, just
 * widened from one account to all of them.
 */
class CheckMigrationRecoveryUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val navigationRouter: NavigationRouter,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val accountDataSource: AccountDataSource,
    private val context: Context,
    private val migrationLiveDriver: MigrationLiveDriver,
    /** Extracted for testability — production default checks WorkManager. */
    private val getWorkerRunState: suspend (String) -> MigrationWorkerRunState = {
        migrationWorkerRunState(context, it)
    },
    /**
     * Extracted for testability — production default enqueues an immediate WorkManager run
     * (`Duration.ZERO`), replacing whatever delay the worker last armed for itself.
     */
    private val scheduleNow: suspend (String) -> Unit = { MigrationScheduler(context).schedule(it, Duration.ZERO) },
    private val resetPending: StateFlow<Boolean> = MutableStateFlow(false),
) {
    @Suppress("TooGenericExceptionCaught") // Foreground recovery handles native SDK and storage failures together.
    suspend operator fun invoke(
        requestSendingScreen: (String) -> Unit = { navigationRouter.replaceAll(HomeArgs, MigrationSendingArgs(it)) },
    ) {
        if (!recoveryMutex.tryLock()) return
        try {
            val elapsed = lastRunMark?.elapsedNow()
            if (!resetPending.value && (elapsed == null || elapsed >= RUN_THROTTLE)) {
                recoverPersistedWallet(requestSendingScreen)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            migrationLog("MigrationRecovery: unavailable (${e.javaClass.simpleName}). Retry on next trigger.")
        } finally {
            recoveryMutex.unlock()
        }
    }

    private suspend fun recoverPersistedWallet(requestSendingScreen: (String) -> Unit) {
        if (persistableWalletProvider.getPersistableWallet() == null) return
        val ownerKey = awaitOwnerKey() ?: return
        val sdk = if (resetPending.value) null else getOrchardMigrationSdk(ownerKey)
        if (sdk != null && !resetPending.value) {
            reviveAccountWorkers()
            recoverBroadcast(sdk, ownerKey, requestSendingScreen)
            if (!resetPending.value) lastRunMark = TimeSource.Monotonic.markNow()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitOwnerKey(): String? =
        resetPending.flatMapLatest { pending ->
            if (pending) flowOf(null) else accountDataSource.selectedAccount.filterNotNull()
        }.first()?.sdkAccount?.accountUuid?.toStorageKeyId()

    private suspend fun reviveAccountWorkers() {
        // The process-scoped driver accelerates committed migrations. WorkManager keeps the same work
        // alive after process death, reboot or an update. Recover both for every spendable account.
        for (account in accountDataSource.getAllAccounts().filter { it.isSpendable }) {
            if (resetPending.value) return
            val accountKey = account.sdkAccount.accountUuid.toStorageKeyId()
            val sdk = getOrchardMigrationSdk(accountKey)
            if (sdk?.getMigrationStateUnreconciled() is MigrationState.InProgress && !resetPending.value) {
                migrationLiveDriver.startIfNotRunning(accountKey)
                reviveScheduledWorker(accountKey)
            }
        }
    }

    private suspend fun reviveScheduledWorker(accountKey: String) {
        if (getWorkerRunState(accountKey) == MigrationWorkerRunState.ABSENT && !resetPending.value) {
            scheduleNow(accountKey)
        }
    }

    private suspend fun recoverBroadcast(
        sdk: OrchardMigrationSdk,
        ownerKey: String,
        requestSendingScreen: (String) -> Unit,
    ) {
        if (!resetPending.value && pendingMigrationTorFailureStorageProvider.get(ownerKey)) {
            // The flag only records THAT a background attempt once failed on Tor, not what the
            // engine's next due transfer needs NOW — the plan moves on (proving, dependency
            // mining, rescheduling) between when it was set and the next app open, and the flag
            // is never invalidated by any of that. Re-verify against the engine's live state
            // before trusting it: only navigate when the earliest not-yet-sent transfer is
            // actually broadcast-ready. Otherwise this flag is stale (observed live: the flag
            // survived from an old Tor failure while the actual next-due transfer was stuck
            // needing PROVE for an unrelated reason) — MigrationSendingVM would immediately hit
            // AwaitingProof/NothingDue and show a "Couldn't Send" sheet whose message has nothing
            // to do with Tor, on every single app open, for a transfer that was never going to
            // attempt a network send in the first place. Clear it instead; the transfer's actual
            // blocker is already covered by the normal Migration Progress home banner.
            val nextTransfer =
                sdk
                    .getMigrationTransferStates()
                    ?.transfers
                    ?.filter { it.isTransfer && !it.isSent }
                    ?.minByOrNull { it.scheduledHeight }
            if (nextTransfer?.action == MigrationNextAction.BROADCAST) {
                // A background attempt failed specifically because of Tor — route through the Sending
                // screen first rather than straight to MigrationProgressArgs/MigrationTorFailureArgs:
                // MigrationSendingVM's init{} always attempts a send immediately on construction,
                // reproducing the exact condition that failed in the background using the current
                // migration Tor setting. If it fails again, MigrationSendingVM's own existing
                // sendOnce() logic already forwards to MigrationTorFailureArgs — no need to duplicate
                // that routing here.
                migrationLog("MigrationRecovery: pending background Tor failure — redirecting to Sending.")
                if (!resetPending.value) requestSendingScreen(ownerKey)
            } else {
                migrationLog(
                    "MigrationRecovery: pending background Tor failure flag is stale " +
                        "(next transfer action=${nextTransfer?.action}) — clearing."
                )
                if (!resetPending.value) pendingMigrationTorFailureStorageProvider.store(ownerKey, false)
            }
        }
    }

    companion object {
        private val RUN_THROTTLE: Duration = 10_000.milliseconds

        private val recoveryMutex = Mutex()

        @Volatile
        private var lastRunMark: TimeSource.Monotonic.ValueTimeMark? = null

        /** Tests run in one JVM — reset the shared throttle between them. */
        internal fun resetRunThrottleForTests() {
            lastRunMark = null
        }
    }
}

/**
 * Production implementation of the migration-worker active check — reads WorkManager unique work
 * state (per-account unique work name). Extracted so [CheckMigrationRecoveryUseCase] tests can
 * supply a lambda stub instead of needing a real WorkManager context (unit tests can't
 * initialise WorkManager).
 */
internal suspend fun isMigrationWorkerActiveInWorkManager(context: Context, accountKeyId: String): Boolean =
    withContext(Dispatchers.IO) {
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(
                MigrationScheduler.workId(accountKeyId)
            ).get()
    }.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }

/**
 * What the migration worker's unique work is doing right now, distinguishing [SCHEDULED] (a
 * worker run is already armed for later — nothing to do here; the live driver, started
 * unconditionally whenever a migration is in progress, covers acceleration) from [RUNNING]
 * (already executing — also nothing to do) and [ABSENT] (needs reviving).
 * [isMigrationWorkerActiveInWorkManager] collapses the first two together, which is all
 * [OnMigrationSyncCompletedUseCase] needs; this finer state is what
 * [CheckMigrationRecoveryUseCase]'s app-open trigger needs.
 */
enum class MigrationWorkerRunState { RUNNING, SCHEDULED, ABSENT }

/**
 * Production implementation of the migration-worker run-state check — reads WorkManager unique
 * work state (per-account unique work name). Extracted so [CheckMigrationRecoveryUseCase] tests
 * can supply a lambda stub instead of needing a real WorkManager context (unit tests can't
 * initialise WorkManager).
 */
internal suspend fun migrationWorkerRunState(context: Context, accountKeyId: String): MigrationWorkerRunState {
    val infos =
        withContext(Dispatchers.IO) {
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWork(
                    MigrationScheduler.workId(accountKeyId)
                ).get()
        }
    return when {
        infos.any { it.state == WorkInfo.State.RUNNING } -> MigrationWorkerRunState.RUNNING
        infos.any { it.state == WorkInfo.State.ENQUEUED } -> MigrationWorkerRunState.SCHEDULED
        else -> MigrationWorkerRunState.ABSENT
    }
}
