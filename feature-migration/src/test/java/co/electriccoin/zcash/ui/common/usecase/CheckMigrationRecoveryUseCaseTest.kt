package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.AttentionReason
import cash.z.ecc.android.sdk.MigrationNextAction
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.MigrationTransferState
import cash.z.ecc.android.sdk.MigrationTransferStates
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.fixture.AccountFixture
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PendingMigrationTorFailureStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.migration.complete.MigrationCompleteArgs
import co.electriccoin.zcash.ui.screen.migration.invalid.MigrationTransferInvalidArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.work.MigrationAccountRetirement
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.work.MigrationWorkerHeartbeat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CheckMigrationRecoveryUseCaseTest {
    // A single test account — mirrors the pattern used across the other migration VM/use-case
    // tests (e.g. MigrationKeystoneScanVMTest) so accountDataSource.getAllAccounts() has exactly
    // one account for the multi-account driver-start/worker-revival loop to iterate.
    private val testSdkAccount =
        AccountFixture.new(
            accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        )
    private val testWalletAccount: WalletAccount =
        mockk(relaxed = true) {
            every { sdkAccount } returns testSdkAccount
            every { isSpendable } returns true
            every { isSelected } returns true
        }

    private val ownerKey = testSdkAccount.accountUuid.toStorageKeyId()

    @kotlin.test.BeforeTest
    fun resetThrottle() {
        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
    }

    /** A single not-yet-sent transfer, [action] controlling whether it reads as broadcast-ready. */
    private fun transferState(action: MigrationNextAction?) =
        MigrationTransferState(
            id = 1L,
            isTransfer = true,
            isSent = false,
            isProved = action == MigrationNextAction.BROADCAST,
            scheduledHeight = 100L,
            anchorBoundaryHeight = null,
            ready = action != null,
            action = action,
        )

    private fun useCase(
        sdk: OrchardMigrationSdk?,
        navigationRouter: NavigationRouter,
        pendingMigrationTorFailure: Boolean = false,
        pendingMigrationTorFailureStorageProvider: PendingMigrationTorFailureStorageProvider =
            mockk(relaxed = true) {
                coEvery { this@mockk.get(ownerKey) } returns pendingMigrationTorFailure
            },
        // Default: the worker is already RUNNING in tests so the reconciliation branch is a no-op,
        // keeping existing test behaviour unchanged. Override to test reconciliation explicitly.
        getWorkerRunState: suspend (String) -> MigrationWorkerRunState = { MigrationWorkerRunState.RUNNING },
        scheduleNow: suspend (String, Long) -> Unit = { _, _ -> },
        reconcileScheduledWake: suspend (String, Long) -> Unit = { _, _ -> },
        migrationLiveDriver: MigrationLiveDriver = mockk(relaxed = true),
        accounts: List<WalletAccount> = if (sdk != null) listOf(testWalletAccount) else emptyList(),
        accountsState: MutableStateFlow<List<WalletAccount>?> = MutableStateFlow(accounts),
        selectedAccountState: MutableStateFlow<WalletAccount?> = MutableStateFlow(testWalletAccount),
        resetState: MutableStateFlow<Boolean> = MutableStateFlow(false),
        context: Context = mockk(relaxed = true),
        migrationNotifier: MigrationNotifier = mockk(relaxed = true),
        cancelRemovedWake: (String) -> Unit = {},
        getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase =
            mockk<GetOrchardMigrationSdkUseCase> {
                if (sdk != null) {
                    // Explicit-account overload — used by the driver-start/worker-revival loop,
                    // which now enumerates accountDataSource.getAllAccounts() instead of only the
                    // selected account.
                    coEvery { this@mockk(any()) } returns sdk
                }
            },
    ) = CheckMigrationRecoveryUseCase(
        getOrchardMigrationSdk = getOrchardMigrationSdk,
        persistableWalletProvider =
            mockk<PersistableWalletProvider>(relaxed = true) {
                coEvery { getPersistableWallet() } returns if (sdk != null) mockk(relaxed = true) else null
            },
        navigationRouter = navigationRouter,
        pendingMigrationTorFailureStorageProvider = pendingMigrationTorFailureStorageProvider,
        accountDataSource =
            mockk<AccountDataSource>(relaxed = true) {
                every { allAccounts } returns accountsState
                every { selectedAccount } returns selectedAccountState
            },
        context = context,
        getWorkerRunState = getWorkerRunState,
        scheduleNow = scheduleNow,
        reconcileScheduledWake = reconcileScheduledWake,
        migrationLiveDriver = migrationLiveDriver,
        migrationNotifier = migrationNotifier,
        resetPending = resetState,
        cancelRemovedWake = cancelRemovedWake,
    )

    @Test
    fun foregroundRecoveryClearsGoneOwnerNoticesWithoutAWorkerAndRetainsTheSurvivingWake() = runTest {
        val goneKey = "00000000000000000000000000000002"
        val heartbeat = removalPreferences(ownerKey, goneKey)
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        val cancelled = mutableListOf<String>()
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val scheduled = mutableListOf<String>()
        val reconciled = mutableListOf<String>()
        useCase(
            sdk = sdk,
            navigationRouter = mockk(relaxed = true),
            context = heartbeat,
            migrationNotifier = notifier,
            cancelRemovedWake = { cancelled += it },
            getWorkerRunState = { MigrationWorkerRunState.SCHEDULED },
            scheduleNow = { owner, _ -> scheduled += owner },
            reconcileScheduledWake = { owner, _ -> reconciled += owner },
        ).invoke()

        assertEquals(listOf(ownerKey), reconciled)
        verify(exactly = 1) { notifier.cancel(goneKey) }
        verify(exactly = 1) { notifier.cancelStepDue(goneKey) }
        verify(exactly = 0) { notifier.cancel(ownerKey) }
        verify(exactly = 0) { notifier.cancelStepDue(ownerKey) }
        assertEquals(listOf(goneKey), cancelled)
        assertEquals(emptyList(), scheduled)
        assertFalse(MigrationWorkerHeartbeat.removalPending(heartbeat, goneKey))
        assertFalse(MigrationWorkerHeartbeat.removalPending(heartbeat, ownerKey))
    }

    @Test
    fun aCompletedPlanCannotRearmWhileItsOlderWorkStateIsBeingRead() = runTest {
        val context = removalPreferences()
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        var withdrawn = false
        val reconciled = mutableListOf<String>()
        useCase(
            sdk = sdk,
            navigationRouter = mockk(relaxed = true),
            context = context,
            getWorkerRunState = { owner ->
                MigrationScheduler.withdrawCompletedFallback(
                    context, owner, MigrationScheduler.wakeVersion(owner)
                ) { withdrawn = true }
                MigrationWorkerRunState.SCHEDULED
            },
            reconcileScheduledWake = { owner, _ -> reconciled += owner },
        ).invoke()

        assertTrue(withdrawn)
        assertEquals(emptyList(), reconciled)
    }

    @Test
    fun foregroundRecoveryLeavesLiveRemovalAndUnreadySnapshotAlone() = runTest {
        val goneKey = "00000000000000000000000000000002"
        val heartbeat = removalPreferences(goneKey)
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        useCase(sdk, mockk(relaxed = true), accounts = emptyList(), context = heartbeat).invoke()
        assertEquals(setOf(goneKey), MigrationWorkerHeartbeat.accountKeyIds(heartbeat))

        CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
        MigrationAccountRetirement.begin(goneKey)
        try {
            useCase(sdk, mockk(relaxed = true), context = heartbeat, migrationNotifier = notifier).invoke()
            assertEquals(setOf(goneKey), MigrationWorkerHeartbeat.accountKeyIds(heartbeat))
            verify(exactly = 0) { notifier.cancel(any()) }
            verify(exactly = 0) { notifier.cancelStepDue(any()) }
        } finally {
            MigrationAccountRetirement.restore(goneKey)
        }
    }

    private fun removalPreferences(vararg owners: String): Context {
        val values = owners.associate { "removal_pending_$it" to true }.toMutableMap()
        val editor = mockk<SharedPreferences.Editor> {
            every { remove(any()) } answers {
                values.remove(firstArg<String>())
                this@mockk
            }
            every { commit() } returns true
            every { apply() } returns Unit
        }
        val preferences = mockk<SharedPreferences> {
            every { all } answers { values.toMap() }
            every { getBoolean(any(), false) } answers { values[firstArg<String>()] ?: false }
            every { edit() } returns editor
        }
        return mockk {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns preferences
            every { getSystemService(Context.ALARM_SERVICE) } returns null
        }
    }

    // ── Task 6: auto-navigation removal — only Tor-failure fires on app-open ─────────────

    @Test
    fun foregroundWorkerRecoveryDoesNotWaitForTheSelectedPresentationAccount() = runTest {
        val selection = MutableStateFlow<WalletAccount?>(null)
        val listedAccount = mockk<WalletAccount> {
            every { sdkAccount } returns testSdkAccount
            every { isSpendable } returns true
            every { isSelected } returns false
        }
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val recovery = useCase(
            sdk, mockk(relaxed = true), migrationLiveDriver = driver, selectedAccountState = selection,
            accounts = listOf(listedAccount),
        )
        val work = async { recovery() }
        runCurrent()
        assertFalse(work.isCompleted)
        coVerify(exactly = 1) { driver.startIfNotRunning(ownerKey) }

        selection.value = testWalletAccount
        work.await()

        coVerify(exactly = 1) { driver.startIfNotRunning(ownerKey) }
    }

    @Test
    fun resetEndsTheWaitingRecoveryWithoutAnSdkRead() = runTest {
        val selection = MutableStateFlow<WalletAccount?>(null)
        val reset = MutableStateFlow(false)
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val acquire = mockk<GetOrchardMigrationSdkUseCase>()
        val recovery = useCase(
            sdk, mockk(relaxed = true), selectedAccountState = selection, resetState = reset,
            accountsState = MutableStateFlow(null),
            getOrchardMigrationSdk = acquire,
        )
        val work = async { recovery() }
        runCurrent()
        assertFalse(work.isCompleted)

        reset.value = true
        work.await()
        selection.value = testWalletAccount

        coVerify(exactly = 0) { acquire(any()) }
    }

    @Test
    fun sdkOpenFailureLeavesTheNextForegroundRecoveryAvailable() = runTest {
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true) {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        var opens = 0
        val acquire = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { this@mockk(ownerKey) } coAnswers {
                if (++opens == 1) error("fixture wallet disappeared before SDK open")
                sdk
            }
        }
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val recovery = useCase(
            sdk = sdk,
            navigationRouter = mockk(relaxed = true),
            migrationLiveDriver = driver,
            getOrchardMigrationSdk = acquire,
        )

        recovery()
        coVerify(exactly = 0) { driver.startIfNotRunning(any()) }
        recovery()
        assertEquals(2, opens)
        coVerify(exactly = 1) { driver.startIfNotRunning(testSdkAccount.accountUuid.toStorageKeyId()) }
    }

    @Test
    fun anUnavailableSelectedOwnerDoesNotBlockAnotherCommittedAccountsWorker() = runTest {
        val otherSdkAccount = AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val other = mockk<WalletAccount> {
            every { sdkAccount } returns otherSdkAccount
            every { isSpendable } returns true
        }
        val otherKey = otherSdkAccount.accountUuid.toStorageKeyId()
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
        }
        for (openFailure in listOf(false, true)) {
            CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
            val resolver = mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk(ownerKey) } coAnswers {
                    if (openFailure) error("Preference temporarily unavailable")
                    null
                }
                coEvery { this@mockk(otherKey) } returns sdk
            }
            val router = mockk<NavigationRouter>(relaxed = true)
            val scheduled = mutableListOf<String>()
            useCase(
                sdk = sdk,
                navigationRouter = router,
                accounts = listOf(testWalletAccount, other),
                pendingMigrationTorFailure = true,
                getWorkerRunState = { MigrationWorkerRunState.ABSENT },
                scheduleNow = { owner, _ -> scheduled += owner },
                getOrchardMigrationSdk = resolver,
            ).invoke()
            assertEquals(listOf(otherKey), scheduled)
            verify(exactly = 0) { router.replaceAll(any()) }
        }
    }

    @Test
    fun hasOverdueTransfers_doesNotAutoNavigateToMigrationProgress() =
        runTest {
            // After Task 6 the overdue branch no longer auto-navigates — user reaches Progress via
            // home banner only.
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasOverdueTransfers() } returns true
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = false).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationProgressArgs()) }
        }

    @Test
    fun pendingTorFailureStillAutoNavigatesToMigrationSending_whenNextTransferIsBroadcastReady() =
        runTest {
            // The Tor-failure branch is the ONE auto-navigation kept after Task 6 — but only when
            // the engine's next due transfer is actually broadcast-ready right now (see the
            // staleness tests below for why this is re-checked rather than trusted blindly).
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasOverdueTransfers() } returns true
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationTransferStates() } returns
                        MigrationTransferStates(
                            transfers = listOf(transferState(MigrationNextAction.BROADCAST)),
                            tipHeight = 100L,
                        )
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            val otherAccount = mockk<WalletAccount> {
                every { sdkAccount } returns
                    AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000002"))
            }
            val selected = MutableStateFlow<WalletAccount?>(testWalletAccount)
            val recovery = useCase(
                sdk = sdk,
                navigationRouter = router,
                pendingMigrationTorFailure = true,
                selectedAccountState = selected,
                getWorkerRunState = {
                    selected.value = otherAccount
                    MigrationWorkerRunState.RUNNING
                },
            )
            recovery()
            assertEquals(otherAccount, selected.value)
            coVerify(exactly = 1) { router.replaceAll(MigrationProgressArgs(ownerKey, offerRetry = true)) }

            selected.value = testWalletAccount
            CheckMigrationRecoveryUseCase.resetRunThrottleForTests()
            val deferredOwners = mutableListOf<String>()
            recovery { deferredOwners += it }

            assertEquals(listOf(ownerKey), deferredOwners)
        }

    @Test
    fun pendingTorFailureDoesNotAutoNavigateToAnyOtherScreen() =
        runTest {
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasOverdueTransfers() } returns true
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationTransferStates() } returns
                        MigrationTransferStates(
                            transfers = listOf(transferState(MigrationNextAction.BROADCAST)),
                            tipHeight = 100L,
                        )
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = true).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationTransferInvalidArgs(ownerKey)) }
            coVerify(exactly = 0) { router.replaceAll(MigrationProgressArgs()) }
            coVerify(exactly = 0) { router.replaceAll(MigrationCompleteArgs()) }
        }

    @Test
    fun pendingTorFailureIsStale_whenNextTransferStillNeedsProof_clearsFlagInsteadOfNavigating() =
        runTest {
            // Regression: the flag only records THAT a background attempt once failed on Tor, not
            // what the engine's next due transfer needs NOW. Observed live: the flag survived from
            // an old Tor failure while the actual next-due transfer was stuck needing PROVE for an
            // unrelated reason (a stale anchor checkpoint) — navigating to Sending here just hits a
            // guaranteed AwaitingProof and shows a "Couldn't Send" sheet on every app open.
            val storageProvider =
                mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true) {
                    coEvery { this@mockk.get(ownerKey) } returns true
                }
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasOverdueTransfers() } returns true
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationTransferStates() } returns
                        MigrationTransferStates(
                            transfers = listOf(transferState(MigrationNextAction.PROVE)),
                            tipHeight = 100L,
                        )
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(
                sdk = sdk,
                navigationRouter = router,
                pendingMigrationTorFailureStorageProvider = storageProvider,
            ).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationProgressArgs(ownerKey, offerRetry = true)) }
            coVerify(exactly = 1) { storageProvider.store(ownerKey, false) }
        }

    @Test
    fun pendingTorFailureIsStale_whenNothingIsDue_clearsFlagInsteadOfNavigating() =
        runTest {
            val storageProvider =
                mockk<PendingMigrationTorFailureStorageProvider>(relaxed = true) {
                    coEvery { this@mockk.get(ownerKey) } returns true
                }
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasOverdueTransfers() } returns true
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationTransferStates() } returns
                        MigrationTransferStates(transfers = emptyList(), tipHeight = 100L)
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(
                sdk = sdk,
                navigationRouter = router,
                pendingMigrationTorFailureStorageProvider = storageProvider,
            ).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationProgressArgs(ownerKey, offerRetry = true)) }
            coVerify(exactly = 1) { storageProvider.store(ownerKey, false) }
        }

    @Test
    fun requiresAttention_doesNotAutoNavigate() =
        runTest {
            // After Task 6, RequiresAttention no longer auto-navigates to MigrationTransferInvalidArgs.
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns
                        MigrationState.RequiresAttention(AttentionReason.TransferExpired)
                    coEvery { getMigrationStateUnreconciled() } returns
                        MigrationState.RequiresAttention(AttentionReason.TransferExpired)
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(sdk = sdk, navigationRouter = router, pendingMigrationTorFailure = false).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationTransferInvalidArgs(ownerKey)) }
            coVerify(exactly = 0) { router.replaceAll(any()) }
        }

    @Test
    fun complete_doesNotAutoNavigateToCelebration() =
        runTest {
            // After Task 6, MigrationState.Complete no longer auto-routes to MigrationCompleteArgs.
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasInvalidTransfers() } returns false
                    coEvery { hasOverdueTransfers() } returns false
                    coEvery { getMigrationState() } returns MigrationState.Complete
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.Complete
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(
                sdk = sdk,
                navigationRouter = router,
                pendingMigrationTorFailure = false,
            ).invoke()

            coVerify(exactly = 0) { router.replaceAll(MigrationCompleteArgs()) }
            coVerify(exactly = 0) { router.replaceAll(any()) }
        }

    // ── NotStarted (nothing persisted app-side anymore) ──────────────────────────────────

    @Test
    fun notStartedLeavesEverythingAloneAndDoesNotNavigate() =
        runTest {
            // Nothing plan-shaped is persisted app-side anymore — a commit that never happened
            // simply leaves the engine NotStarted and there is nothing to reconcile or clear.
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { hasInvalidTransfers() } returns false
                    coEvery { hasOverdueTransfers() } returns false
                    coEvery { getMigrationState() } returns MigrationState.NotStarted
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.NotStarted
                }
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(sdk = sdk, navigationRouter = router).invoke()

            coVerify(exactly = 0) { router.replaceAll(any()) }
        }

    @Test
    fun noWalletAvailableDoesNothing() =
        runTest {
            val router = mockk<NavigationRouter>(relaxed = true)

            useCase(sdk = null, navigationRouter = router).invoke()

            coVerify(exactly = 0) { router.replaceAll(any()) }
        }

    // ── Worker reconciliation + app-open acceleration ─────────────────────────────────────

    @Test
    fun workerReconciliation_planExistsAndWorkerRunning_doesNotSchedule() =
        runTest {
            var asked = false
            var scheduled = false
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { hasOverdueTransfers() } returns false
                }

            useCase(
                sdk = sdk,
                navigationRouter = mockk(relaxed = true),
                getWorkerRunState = {
                    asked = true
                    MigrationWorkerRunState.RUNNING
                },
                scheduleNow = { _, _ -> scheduled = true },
            ).invoke()

            // The check ran and, because the worker is already executing, nothing was scheduled —
            // a RUNNING worker is already doing the work this trigger wants.
            kotlin.test.assertTrue(asked)
            kotlin.test.assertFalse(scheduled)
        }

    @Test
    fun workerReconciliation_planExistsAndWorkerScheduledForLater_doesNotAccelerateViaScheduleNow() =
        runTest {
            // SCHEDULED no longer triggers scheduleNow directly — the live driver (started
            // unconditionally whenever engineInProgress) is the fast path now.
            var scheduled = false
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { hasOverdueTransfers() } returns false
                }

            useCase(
                sdk = sdk,
                navigationRouter = mockk(relaxed = true),
                getWorkerRunState = { MigrationWorkerRunState.SCHEDULED },
                scheduleNow = { _, _ -> scheduled = true },
            ).invoke()

            kotlin.test.assertFalse(
                scheduled,
                "SCHEDULED must not call scheduleNow anymore — the live driver covers acceleration",
            )
        }

    @Test
    fun workerReconciliation_planExists_alwaysStartsTheLiveDriver() =
        runTest {
            var startedAccountKeyId: String? = null
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { hasOverdueTransfers() } returns false
                }
            val liveDriver =
                mockk<MigrationLiveDriver> {
                    every { startIfNotRunning(any()) } answers { startedAccountKeyId = firstArg() }
                }

            useCase(
                sdk = sdk,
                navigationRouter = mockk(relaxed = true),
                getWorkerRunState = { MigrationWorkerRunState.RUNNING },
                migrationLiveDriver = liveDriver,
            ).invoke()

            kotlin.test.assertTrue(
                startedAccountKeyId != null,
                "an in-progress migration must always start (or no-op) the live driver",
            )
        }

    @Test
    fun workerReconciliation_planExistsAndWorkerAbsent_schedulesNow() =
        runTest {
            var scheduled = false
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { hasOverdueTransfers() } returns false
                }

            useCase(
                sdk = sdk,
                navigationRouter = mockk(relaxed = true),
                getWorkerRunState = { MigrationWorkerRunState.ABSENT },
                scheduleNow = { _, _ -> scheduled = true },
            ).invoke()

            // Revival: an absent worker chain (killed process, cleared WorkManager state) is
            // rescheduled immediately.
            kotlin.test.assertTrue(scheduled)
        }

    @Test
    fun viewOnlyAccountsAreNeverReconciled() =
        runTest {
            val viewOnlySdkAccount =
                AccountFixture.new(accountUuid = UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val viewOnly =
                mockk<ViewOnlyAccount>(relaxed = true) {
                    every { sdkAccount } returns viewOnlySdkAccount
                    every { isSpendable } returns false
                }
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { getMigrationState() } returns MigrationState.NotStarted
                    coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
                    coEvery { hasOverdueTransfers() } returns false
                }
            val getOrchardMigrationSdk =
                mockk<GetOrchardMigrationSdkUseCase> {
                    coEvery { this@mockk(any()) } returns sdk
                }
            val startedAccountKeyIds = mutableListOf<String>()
            val scheduledAccountKeyIds = mutableListOf<String>()
            val liveDriver =
                mockk<MigrationLiveDriver> {
                    every { startIfNotRunning(any()) } answers { startedAccountKeyIds += firstArg<String>() }
                }

            useCase(
                sdk = sdk,
                navigationRouter = mockk(relaxed = true),
                getWorkerRunState = { MigrationWorkerRunState.ABSENT },
                scheduleNow = { owner, _ -> scheduledAccountKeyIds += owner },
                migrationLiveDriver = liveDriver,
                accounts = listOf(testWalletAccount, viewOnly),
                getOrchardMigrationSdk = getOrchardMigrationSdk,
            ).invoke()

            val spendableKeyId = testSdkAccount.accountUuid.toStorageKeyId()
            val viewOnlyKeyId = viewOnlySdkAccount.accountUuid.toStorageKeyId()
            coVerify(exactly = 0) { getOrchardMigrationSdk(viewOnlyKeyId) }
            kotlin.test.assertEquals(listOf(spendableKeyId), startedAccountKeyIds)
            kotlin.test.assertEquals(listOf(spendableKeyId), scheduledAccountKeyIds)
        }
}
