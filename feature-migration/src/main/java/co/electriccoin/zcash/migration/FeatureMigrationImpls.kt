package co.electriccoin.zcash.migration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.InternalTestingApi
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.migration.MigrationDebugActions
import co.electriccoin.zcash.ui.common.migration.MigrationGate
import co.electriccoin.zcash.ui.common.migration.MigrationNavContributor
import co.electriccoin.zcash.ui.common.migration.MigrationNavigator
import co.electriccoin.zcash.ui.common.migration.MigrationSyncedHook
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.MigrationGateBypassStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.CheckMigrationRecoveryUseCase
import co.electriccoin.zcash.ui.common.usecase.DebugStartMigrationE2EUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.OnMigrationSyncCompletedUseCase
import co.electriccoin.zcash.ui.common.usecase.RestartMigrationUseCase
import co.electriccoin.zcash.ui.dialogComposable
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryScreen
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteScreen
import co.electriccoin.zcash.ui.screen.migration.customservertor.MigrationCustomServerTorArgs
import co.electriccoin.zcash.ui.screen.migration.customservertor.MigrationCustomServerTorScreen
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksArgs
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksScreen
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidScreen
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanArgs
import co.electriccoin.zcash.ui.screen.migration.keystonescan.MigrationKeystoneScanScreen
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignScreen
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerScreen
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationArgs
import co.electriccoin.zcash.ui.screen.migration.notification.MigrationNotificationScreen
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyArgs
import co.electriccoin.zcash.ui.screen.migration.privacy.MigrationPrivacyScreen
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressScreen
import co.electriccoin.zcash.ui.screen.migration.restart.MigrationRestartArgs
import co.electriccoin.zcash.ui.screen.migration.restart.MigrationRestartScreen
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewScreen
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledScreen
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingArgs
import co.electriccoin.zcash.ui.screen.migration.sending.MigrationSendingScreen
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupArgs
import co.electriccoin.zcash.ui.screen.migration.setup.MigrationSetupScreen
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessScreen
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureArgs
import co.electriccoin.zcash.ui.screen.migration.torfailure.MigrationTorFailureScreen
import co.electriccoin.zcash.ui.common.repository.MigrationTransferStateRepository
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationAccountRetirement
import co.electriccoin.zcash.work.MigrationDriveOnce
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationWorker
import co.electriccoin.zcash.work.MigrationWorkerHeartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MigrationGateImpl(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val gateBypassStorage: MigrationGateBypassStorageProvider,
) : MigrationGate {
    // Engine state IS the gate now — no app-side plan marker exists. Selected-account scoped
    // (matches the sync it gates).
    // TODO [#0]: see post-adoption task C2 for the any-account variant.
    //
    // Both methods guard their state read (2026-08-07 Fable review): this had been unguarded, and
    // isRestartAvailable() is called from AdvancedSettingsVM's bare `viewModelScope.launch` (no
    // LCE, no try/catch of its own) — a "database is locked" throw there would crash the app
    // straight from the Settings screen. isMigrationActive() is called from SyncWorker.doWork()
    // every scheduled background-sync run; a throw there would just fail that one worker run
    // (WorkManager retries), but is guarded the same way for consistency and to avoid a spurious
    // retry loop on a transient DB-lock read.
    //
    // getMigrationStateUnreconciled(), not getMigrationState(): a gate never mutates (2026-08-07
    // read/write-separation design) — a just-mined final transfer this account hasn't reconciled
    // yet keeps the gate conservative for at most one drive-loop publish cycle, which fails safe
    // (sync stays gated slightly longer, never the reverse).
    override suspend fun isMigrationActive(): Boolean =
        runCatching {
            getOrchardMigrationSdk().getMigrationStateUnreconciled() is cash.z.ecc.android.sdk.MigrationState.InProgress
        }.getOrDefault(false)

    /** Persisted: GetOrchardMigrationSdkUseCase applies the stored value to every SDK instance it builds. */
    override suspend fun isNetworkUpgradeGateBypassEnabled(): Boolean = gateBypassStorage.isEnabled()

    @OptIn(InternalTestingApi::class)
    override suspend fun setNetworkUpgradeGateBypass(enabled: Boolean) {
        gateBypassStorage.setEnabled(enabled)
        runCatching { getOrchardMigrationSdk().setMigrationNetworkUpgradeGateBypass(enabled) }
    }

    override suspend fun isRestartAvailable(): Boolean =
        runCatching {
            when (getOrchardMigrationSdk().getMigrationStateUnreconciled()) {
                is cash.z.ecc.android.sdk.MigrationState.InProgress,
                is cash.z.ecc.android.sdk.MigrationState.RequiresAttention -> true

                else -> false
            }
        }.getOrDefault(false)
}

class MigrationSyncedHookImpl(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val onMigrationSyncCompleted: OnMigrationSyncCompletedUseCase,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val resetProgress: ResetProgressStore,
) : MigrationSyncedHook {
    override suspend fun onSynced() {
        if (resetProgress.isPending.value) return
        // Engine state is the only "migration active" signal — no plan cache exists anymore.
        // getMigrationStateUnreconciled(): this gate never mutates (2026-08-07 read/write-
        // separation design) — onMigrationSyncCompleted below still runs the real reconcile pass
        // via the drive loop's own cadence regardless of this gate's staleness.
        val accountKeyId = getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
        val state = getOrchardMigrationSdk(accountKeyId)?.getMigrationStateUnreconciled()
        if (state !is cash.z.ecc.android.sdk.MigrationState.InProgress || resetProgress.isPending.value) return
        onMigrationSyncCompleted(accountKeyId)
    }
}

class MigrationAppHooksImpl(
    private val checkMigrationRecovery: CheckMigrationRecoveryUseCase,
    private val debugStartMigrationE2E: DebugStartMigrationE2EUseCase,
    private val launchNavigation: LaunchNavigationRequests,
    private val accountDataSource: AccountDataSource,
    private val migrationNotifier: MigrationNotifier,
    private val context: Context,
    private val resetProgress: ResetProgressStore,
    private val migrationLiveDriver: MigrationLiveDriver,
    private val migrationDriveOnce: MigrationDriveOnce,
    private val pendingMigrationSchedule: PendingMigrationScheduleRepository,
    private val pendingKeystone: PendingKeystoneMigrationPcztsRepository,
    private val migrationReadouts: MigrationTransferStateRepository,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(resetProgress),
) : MigrationAppHooks {
    override fun handleIntent(
        intent: Intent,
        scope: CoroutineScope
    ): Boolean =
        when {
            resetProgress.isPending.value -> false

            BuildConfig.DEBUG &&
                intent.getBooleanExtra(DebugStartMigrationE2EUseCase.EXTRA_START_MIGRATION, false) -> {
                // Debug-only E2E driver: reset + commit a fresh AUTOMATIC plan from adb, no UI taps.
                scope.launch { debugStartMigrationE2E() }
                true
            }

            intent.component != ComponentName(context, MainActivity::class.java) -> false

            isMigrationNotification(intent) -> {
                val ownerKey = intent.getStringExtra(MigrationNotifier.EXTRA_ACCOUNT_KEY_ID)
                openNotificationProgress(
                    ownerKey, scope, intent.getBooleanExtra(MigrationNotifier.EXTRA_RUN_STEP, false)
                )
                true
            }

            else -> {
                false
            }
        }

    private fun isMigrationNotification(intent: Intent) =
        intent.getBooleanExtra(MigrationNotifier.EXTRA_OPEN_MIGRATION, false) ||
            intent.getBooleanExtra(MigrationNotifier.EXTRA_RUN_STEP, false)

    override fun restoreNotificationProgress(ownerKey: String?, scope: CoroutineScope, runStepPending: Boolean) =
        openNotificationProgress(ownerKey, scope, runStepPending)

    @Suppress("TooGenericExceptionCaught") // Keep account storage and scheduler failures recoverable at entry.
    private fun openNotificationProgress(ownerKey: String?, scope: CoroutineScope, runStep: Boolean) {
        val key = ownerKey?.takeIf { !resetProgress.isPending.value && ACCOUNT_KEY.matches(it) } ?: return
        val generation = walletWriteAccess.token()
        val notice = MigrationAccountRetirement.admit(key) {
            launchNavigation.beginMigrationProgress(key, runStep)
        } ?: return
        scope.launch {
            try {
                val target = accountDataSource.getAllAccounts()
                    .firstOrNull { it.sdkAccount.accountUuid.toStorageKeyId() == key }
                val command = target?.let { account ->
                    walletWriteAccess.write(generation) {
                        if (launchNavigation.resolvingMigrationNotice !== notice ||
                            MigrationAccountRetirement.blocks(key)
                        ) return@write null
                        accountDataSource.selectAccount(account)
                        if (runStep && launchNavigation.resolvingMigrationNotice === notice) {
                            MigrationScheduler(context).runNotificationStep(key)
                        }
                        NavigationCommand.ReplaceAll(listOf(MigrationProgressArgs(key)))
                    }
                }
                val offered = MigrationAccountRetirement.admit(key) {
                    launchNavigation.finishMigrationProgress(notice, command)
                }
                if (offered == null) launchNavigation.finishMigrationProgress(notice, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                launchNavigation.finishMigrationProgress(notice, null)
                migrationLog("Migration notification unavailable (${e.javaClass.simpleName}).")
            }
        }
    }

    override suspend fun checkRecovery() {
        if (!resetProgress.isPending.value) {
            val wallet = walletWriteAccess.token()
            checkMigrationRecovery { ownerKey ->
                MigrationAccountRetirement.admit(ownerKey) {
                    launchNavigation.requestMigrationRecovery(
                        ownerKey,
                        NavigationCommand.ReplaceAll(listOf(MigrationProgressArgs(ownerKey, offerRetry = true)))
                    ) {
                        val selected = accountDataSource.allAccounts.value?.firstOrNull { it.isSelected }
                        selected?.sdkAccount?.accountUuid?.toStorageKeyId() == ownerKey &&
                            walletWriteAccess.token() == wallet && !resetProgress.isPending.value &&
                            !MigrationAccountRetirement.blocks(ownerKey)
                    }
                }
            }
        }
    }

    override suspend fun retireHardwareAccount(accountKeyId: String, deleteAccount: suspend () -> Unit) {
        val generation = walletWriteAccess.token()
        val scheduler = MigrationScheduler(context)
        var retirementStarted = false
        var deleted = false
        try {
            migrationDriveOnce.withIdleDrive {
                // A step already inside the drive finishes arming its successor first.
                MigrationAccountRetirement.begin(accountKeyId)
                retirementStarted = true
            }
            migrationLiveDriver.stopAndJoin(accountKeyId)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                migrationDriveOnce.withIdleDrive {
                    checkNotNull(walletWriteAccess.write(generation) {
                        val recorded = withContext(Dispatchers.IO) {
                            MigrationWorkerHeartbeat.recordRemovalPending(context, accountKeyId)
                        }
                        check(recorded) { "Unable to record migration account removal" }
                        deleteAccount()
                    }) {
                        "The wallet changed before the account could be removed"
                    }
                    MigrationAccountRetirement.markRemoved(accountKeyId)
                    deleted = true
                    launchNavigation.clearMigrationRequests(accountKeyId)
                }
                migrationReadouts.clear(accountKeyId)
                pendingMigrationSchedule.peek(accountKeyId)?.let { pendingMigrationSchedule.clear(accountKeyId, it) }
                pendingKeystone.peek(accountKeyId)?.let { pendingKeystone.clear(accountKeyId, it) }
                migrationNotifier.cancel(accountKeyId)
                migrationNotifier.cancelStepDue(accountKeyId)
                cancelRemovedAccountWork(scheduler, accountKeyId)
            }
        } finally {
            if (retirementStarted && !deleted) {
                restoreRetirement(accountKeyId, generation)
            }
        }
    }

    private suspend fun restoreRetirement(accountKeyId: String, generation: Long) {
        withContext(NonCancellable) {
            var reopened = false
            try {
                walletWriteAccess.write(generation) {
                    withContext(Dispatchers.IO) {
                        MigrationWorkerHeartbeat.clearRemovalPending(context, accountKeyId)
                    }
                    MigrationAccountRetirement.restore(accountKeyId)
                    reopened = true
                    migrationLiveDriver.startIfNotRunning(accountKeyId)
                }
            } finally {
                if (!reopened) MigrationAccountRetirement.restore(accountKeyId)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // The account is gone even if the OS rejects worker cleanup.
    private suspend fun cancelRemovedAccountWork(scheduler: MigrationScheduler, accountKeyId: String) {
        try {
            scheduler.cancelAndWait(accountKeyId)
            withContext(Dispatchers.IO) { MigrationWorkerHeartbeat.clearRemovalPending(context, accountKeyId) }
        } catch (e: Exception) {
            migrationLog("Removed account worker cleanup unavailable (${e.javaClass.simpleName}).")
        }
    }

    override suspend fun cancelMigrationWork(accountKeyId: String?) {
        val keys = if (accountKeyId == null) {
            launchNavigation.clearMigrationRequests()
            val cancellation = WorkManager.getInstance(context).cancelAllWorkByTag(MigrationWorker::class.java.name)
            migrationLiveDriver.stopAll()
            cancellation.result.await()
            migrationDriveOnce.awaitIdle()
            pendingMigrationSchedule.clear()
            pendingKeystone.clear()
            // Reset must cancel the worker chain even when the synchronizer has no account snapshot.
            val accounts = accountDataSource.allAccounts.value.orEmpty()
            (accounts.map { it.sdkAccount.accountUuid.toStorageKeyId() } +
                MigrationWorkerHeartbeat.accountKeyIds(context)).distinct()
        } else {
            pendingMigrationSchedule.peek(accountKeyId)?.let { pendingMigrationSchedule.clear(accountKeyId, it) }
            pendingKeystone.peek(accountKeyId)?.let { pendingKeystone.clear(accountKeyId, it) }
            listOf(accountKeyId)
        }
        keys.forEach { key ->
            MigrationScheduler(context).cancel(key)
            withContext(Dispatchers.IO) { MigrationWorkerHeartbeat.clearRemovalPending(context, key) }
            migrationNotifier.cancel(key)
            migrationNotifier.cancelStepDue(key)
        }
        migrationLog("AppHooks: cancelled migration work (accounts=${keys.size}, sweep=${accountKeyId == null})")
    }

    private companion object {
        val ACCOUNT_KEY = Regex("[0-9a-fA-F]{32}")
    }
}

class MigrationNavigatorImpl(
    private val navigationRouter: NavigationRouter,
) : MigrationNavigator {
    override fun forwardToRestartMigration() = navigationRouter.forward(MigrationRestartArgs())
}

class MigrationNavContributorImpl : MigrationNavContributor {
    override fun contribute(navGraphBuilder: NavGraphBuilder) {
        with(navGraphBuilder) {
            composable<MigrationSetupArgs> { MigrationSetupScreen(it.id) }
            composable<MigrationHowItWorksArgs> { MigrationHowItWorksScreen(it.id) }
            composable<MigrationReviewArgs> { MigrationReviewScreen(it.toRoute(), it.id) }
            composable<MigrationKeystoneSignArgs> { MigrationKeystoneSignScreen(it.toRoute(), it.id) }
            composable<MigrationKeystoneScanArgs> { MigrationKeystoneScanScreen(it.toRoute(), it.id) }
            composable<MigrationBatteryArgs> { MigrationBatteryScreen(it.toRoute()) }
            composable<MigrationNotificationArgs> { MigrationNotificationScreen(it.toRoute()) }
            dialogComposable<MigrationPrivacyArgs> { MigrationPrivacyScreen(it.toRoute()) }
            dialogComposable<MigrationCustomServerTorArgs> { MigrationCustomServerTorScreen(it.toRoute()) }
            dialogComposable<MigrationTorFailureArgs> { entry -> MigrationTorFailureScreen(entry.toRoute(), entry.id) }
            dialogComposable<MigrationLockExplainerArgs> { MigrationLockExplainerScreen() }
            composable<MigrationSendingArgs> { MigrationSendingScreen(it.toRoute(), it.id) }
            composable<MigrationSuccessArgs> { MigrationSuccessScreen(it.toRoute()) }
            composable<MigrationScheduledArgs> { entry -> MigrationScheduledScreen(entry.toRoute()) }
            composable<MigrationCompleteArgs> { MigrationCompleteScreen(it.toRoute(), it.id) }
            composable<MigrationProgressArgs> { entry -> MigrationProgressScreen(entry.toRoute(), entry.id) }
            composable<MigrationTransferInvalidArgs> { MigrationTransferInvalidScreen(it.toRoute(), it.id) }
            composable<MigrationRestartArgs> { MigrationRestartScreen(it.toRoute(), it.id) }
        }
    }
}

class MigrationDebugActionsImpl(
    private val restartMigrationUseCase: RestartMigrationUseCase,
    private val accountDataSource: AccountDataSource,
    private val pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider,
    private val migrationNotifier: MigrationNotifier,
    private val checkMigrationRecovery: CheckMigrationRecoveryUseCase,
) : MigrationDebugActions {
    // Promoted to the production RestartMigrationUseCase (see its kdoc) so there is a single
    // orchestration for both the debug action and the user-facing "Restart Migration" flow.
    override suspend fun restartMigration(): String {
        restartMigrationUseCase()
        return "Migration reset. Propose a new migration to test."
    }

    // Reproduces spec §6.2's "background Tor failure" state (MigrationWorker's non-retryable
    // NetworkError-while-useTor branch) without waiting for a real background run to fail — sets
    // the same persisted flag and posts the same notification, then immediately re-runs the same
    // on-launch reconciliation HomeVM's init{} triggers, so the Sending screen shows up right away
    // instead of only on the next app relaunch/foreground.
    override suspend fun simulateTorFailure(): String {
        val accountKeyId =
            accountDataSource
                .getSelectedAccount()
                .sdkAccount.accountUuid
                .toStorageKeyId()
        pendingMigrationTorFailureStorageProvider.store(true)
        migrationNotifier.notifyMigrationTorFailure(accountKeyId)
        checkMigrationRecovery()
        return "Pending Tor failure flag set. Routing to the Sending screen now " +
            "(same routing HomeVM triggers on every launch/foreground)."
    }
}
