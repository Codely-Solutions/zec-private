package co.electriccoin.zcash.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Operation
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MigrationTransferStateRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.deletewallet.ResetStage
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.work.MigrationDriveOnce
import co.electriccoin.zcash.work.MigrationAccountRetirement
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationWorker
import co.electriccoin.zcash.work.MigrationWorkerHeartbeat
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationAppHooksResetTest {
    @Test
    fun hardwareRetirementClosesItsOwnerAfterTheEnteredDriveLeaves() = runTest {
        val key = "retirement-owner"
        val drain = CompletableDeferred<Unit>()
        val cancel = CompletableDeferred<Unit>()
        val cancelStarted = CompletableDeferred<Unit>()
        val removed = CompletableDeferred<Unit>()
        val context = retirementContext()
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        var driveEntries = 0
        val drive = mockk<MigrationDriveOnce> {
            coEvery { withIdleDrive<Unit>(any()) } coAnswers {
                if (++driveEntries == 1) drain.await()
                firstArg<suspend () -> Unit>().invoke()
            }
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), LaunchNavigationRequests(), mockk(), mockk(relaxed = true), context, reset, driver, drive,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(key) } coAnswers {
                assertTrue(removed.isCompleted)
                assertTrue(MigrationAccountRetirement.isRemoved(key))
                assertTrue(MigrationWorkerHeartbeat.removalPending(context, key))
                cancelStarted.complete(Unit)
                cancel.await()
            }
            val retirement = launch {
                hooks.retireHardwareAccount(key) {
                    assertTrue(MigrationWorkerHeartbeat.removalPending(context, key))
                    removed.complete(Unit)
                }
            }
            runCurrent()

            assertFalse(MigrationAccountRetirement.blocks(key))
            assertFalse(MigrationAccountRetirement.blocks("other-owner"))
            assertFalse(removed.isCompleted)
            coVerify(exactly = 0) { driver.stopAndJoin(any()) }

            drain.complete(Unit)
            cancelStarted.await()
            assertTrue(MigrationAccountRetirement.blocks(key))
            assertFalse(MigrationAccountRetirement.blocks("other-owner"))
            coVerify(exactly = 1) { driver.stopAndJoin(key) }
            assertTrue(removed.isCompleted)
            assertFalse(retirement.isCompleted)
            assertEquals(null, MigrationAccountRetirement.keepWake(key) { "stale wake" })

            cancel.complete(Unit)
            retirement.join()
            assertTrue(removed.isCompleted)
            assertFalse(MigrationWorkerHeartbeat.removalPending(context, key))
            assertTrue(MigrationAccountRetirement.blocks(key))
            coVerify(exactly = 0) { driver.startIfNotRunning(key) }
        } finally {
            MigrationAccountRetirement.restore(key)
            unmockkConstructor(MigrationScheduler::class)
        }
    }

    @Test
    fun failedDeletionReopensOnlyTheOriginalAccountsMigration() = runTest {
        val key = "failed-retirement-owner"
        val context = retirementContext()
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val drive = mockk<MigrationDriveOnce>(relaxed = true) {
            coEvery { withIdleDrive<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(), mockk(), mockk(), context, reset, driver, drive,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            val sdk = GetOrchardMigrationSdkUseCase(mockk(), mockk(), mockk(), mockk(), mockk())
            var queuedWake: String? = "existing wake"

            assertFailsWith<IllegalStateException> {
                hooks.retireHardwareAccount(key) {
                    assertEquals(MigrationSdkLookup.RemovalManaged, sdk.lookup(key))
                    assertTrue(MigrationWorkerHeartbeat.removalPending(context, key))
                    assertEquals("existing wake", queuedWake)
                    MigrationAccountRetirement.keepWake(key) { queuedWake = "wake from sync" }
                    error("Synthetic account deletion failed")
                }
            }
            assertFalse(MigrationAccountRetirement.blocks(key))
            assertEquals("wake from sync", queuedWake)
            assertFalse(MigrationWorkerHeartbeat.removalPending(context, key))
            coVerify(exactly = 0) { anyConstructed<MigrationScheduler>().cancelAndWait(any()) }
            coVerify(exactly = 1) { driver.startIfNotRunning(key) }
        } finally {
            MigrationAccountRetirement.restore(key)
            unmockkConstructor(MigrationScheduler::class)
        }
    }

    @Test
    fun retirementRefusesNativeDeletionIfTheDurableGuardCannotBeWritten() = runTest {
        val key = "failed-removal-checkpoint"
        val drive = mockk<MigrationDriveOnce> {
            coEvery { withIdleDrive<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(), mockk(), mockk(), retirementContext(commitSucceeds = false), reset,
            mockk(relaxed = true), drive, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        var deletionEntered = false
        try {
            assertFailsWith<IllegalStateException> {
                hooks.retireHardwareAccount(key) { deletionEntered = true }
            }
            assertFalse(deletionEntered)
            assertFalse(MigrationAccountRetirement.blocks(key))
        } finally {
            MigrationAccountRetirement.restore(key)
        }
    }

    @Test
    fun retirementDiscardsAStaleNotificationLookupForTheRemovedAccount() = runTest {
        val uuid = AccountUuid.new(ByteArray(16) { 7 })
        val key = uuid.toStorageKeyId()
        val accounts = mockk<AccountDataSource>()
        val lookup = CompletableDeferred<List<WalletAccount>>()
        coEvery { accounts.getAllAccounts() } coAnswers { lookup.await() }
        val target = mockk<WalletAccount> { every { sdkAccount } returns Account.new(uuid) }
        val readouts = mockk<MigrationTransferStateRepository>(relaxed = true)
        val navigation = spyk(LaunchNavigationRequests())
        val drive = mockk<MigrationDriveOnce>(relaxed = true) {
            coEvery { withIdleDrive<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), navigation, accounts, mockk(relaxed = true), retirementContext(), reset,
            mockk(relaxed = true), drive, mockk(relaxed = true), mockk(relaxed = true), readouts,
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(key) } returns Unit

            hooks.restoreNotificationProgress(key, this)
            runCurrent()
            assertTrue(navigation.resolvingMigrationNotice?.ownerKey == key)
            hooks.retireHardwareAccount(key) {}
            lookup.complete(listOf(target))
            runCurrent()

            assertTrue(navigation.resolvingMigrationNotice == null)
            coVerify(exactly = 0) { accounts.selectAccount(target) }
            verify(exactly = 1) { readouts.clear(key) }
            verify(exactly = 1) { navigation.finishMigrationProgress(any(), null, any()) }
        } finally {
            MigrationAccountRetirement.restore(key)
            unmockkConstructor(MigrationScheduler::class)
        }
    }

    @Test
    fun resetCancelsWorkerTagAndWaitsForAcknowledgementWithoutALiveAccount() = runTest {
        val context = mockk<Context> {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns mockk {
                every { all } returns emptyMap()
            }
        }
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(null)
        }
        val cancellation = SettableFuture.create<Operation.State.SUCCESS>()
        val workManager = mockk<WorkManager> {
            every { cancelAllWorkByTag(MigrationWorker::class.java.name) } returns mockk {
                every { result } returns cancellation
            }
        }
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val releaseEnteredDrive = CompletableDeferred<Unit>()
        val driveOnce = mockk<MigrationDriveOnce>(relaxed = true) {
            coEvery { awaitIdle() } coAnswers { releaseEnteredDrive.await() }
        }
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(relaxed = true), accounts, notifier, context, pendingReset(), driver, driveOnce,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns workManager
            val reset = launch { hooks.cancelMigrationWork(null) }
            runCurrent()

            coVerify(exactly = 0) { accounts.getAllAccounts() }
            verify(exactly = 0) { workManager.cancelAllWorkByTag(MigrationWorker::class.java.name) }
            assertFalse(reset.isCompleted)

            releaseEnteredDrive.complete(Unit)
            runCurrent()
            verify(exactly = 1) { workManager.cancelAllWorkByTag(MigrationWorker::class.java.name) }
            assertFalse(reset.isCompleted)

            cancellation.set(Operation.SUCCESS)
            runCurrent()
            assertTrue(reset.isCompleted)
            coVerifyOrder {
                driver.stopAll()
                driveOnce.awaitIdle()
                workManager.cancelAllWorkByTag(MigrationWorker::class.java.name)
                notifier.cancelWalletMigrationNotices()
            }
        } finally {
            releaseEnteredDrive.cancel()
            cancellation.cancel(false)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun resetCancelsOwnedFallbackAlarmWithoutAnAccountSnapshot() = runTest {
        val preferences = mockk<SharedPreferences> {
            every { all } returns mapOf("next_wake_at_fixture" to 100L, "last_run_at_fixture" to 5L, "other" to 1L)
            every { getBoolean("removal_pending_fixture", false) } returns false
        }
        val context = mockk<Context> {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns preferences
        }
        val accounts = mockk<AccountDataSource> { every { allAccounts } returns MutableStateFlow(null) }
        val workManager = mockk<WorkManager> {
            every { cancelAllWorkByTag(MigrationWorker::class.java.name) } returns mockk {
                every { result } returns Futures.immediateFuture(Operation.SUCCESS)
            }
        }
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(relaxed = true), accounts, notifier, context,
            pendingReset(), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        mockkObject(WorkManager.Companion)
        mockkConstructor(MigrationScheduler::class)
        try {
            every { WorkManager.getInstance(context) } returns workManager
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(any()) } returns Unit

            hooks.cancelMigrationWork(null)

            coVerify(exactly = 1) { anyConstructed<MigrationScheduler>().cancelAndWait("fixture") }
            verify(exactly = 1) { notifier.cancel("fixture") }
            verify(exactly = 1) { notifier.cancelStepDue("fixture") }
            coVerify(exactly = 0) { anyConstructed<MigrationScheduler>().cancelAndWait("other") }
            coVerify(exactly = 0) { accounts.getAllAccounts() }
        } finally {
            unmockkConstructor(MigrationScheduler::class)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun aPendingResetRefusesAPlanAndPreventsRecoveryFromRearmingItsWake() = runTest {
        val directory = Files.createTempDirectory("migration-reset-fixture")
        val context = mockk<Context> { every { noBackupFilesDir } returns directory.toFile() }
        val reset = ResetProgressStore(context, mockk(relaxed = true))
        val scheduler = MigrationScheduler(context)
        val key = "pending-reset-fixture"
        val version = MigrationScheduler.wakeVersion(key)
        mockkObject(WorkManager.Companion)
        try {
            reset.store(ResetStage.ERASE_SDK)
            every { WorkManager.getInstance(context) } throws AssertionError("WorkManager lookup during pending reset")

            assertFailsWith<IllegalStateException> {
                scheduler.scheduleAndAwait(key, Duration.ZERO)
            }
            scheduler.scheduleIfMissing(key, expectedVersion = version)
            scheduler.scheduleSuccessorAndAwait(key, Duration.ZERO, version)
            scheduler.reconcileScheduledWake(key, version)
            verify(exactly = 0) { WorkManager.getInstance(context) }
            assertEquals(version, MigrationScheduler.wakeVersion(key))
        } finally {
            unmockkObject(WorkManager.Companion)
            reset.store(null)
            Files.delete(directory)
        }
    }

    @Test
    fun restoringProgressUsesTheNoticeOwnerWithoutChangingSelectionOrOpeningAfterReset() = runTest {
        val uuid = AccountUuid.new(ByteArray(16))
        val ownerKey = uuid.toStorageKeyId()
        val target = mockk<WalletAccount> { every { sdkAccount } returns Account.new(uuid) }
        val accounts = mockk<AccountDataSource>(relaxed = true)
        val launchNavigation = spyk(LaunchNavigationRequests())
        val pending = MutableStateFlow(false)
        val progress = mockk<ResetProgressStore> { every { isPending } returns pending }
        val writeAccess = WalletWriteAccess(progress)
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), launchNavigation, accounts, mockk(), mockk(), progress,
            mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true), writeAccess,
            appAccess = notificationAccess(), navigationRouter = resumedRouter(),
        )
        val command = NavigationCommand.ReplaceAll(listOf(MigrationProgressArgs(ownerKey)))
        coEvery { accounts.getAllAccounts() } throws IllegalStateException("fixture lookup failed")
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), null, any()) }
        verify(exactly = 0) { launchNavigation.finishMigrationProgress(any(), command, any()) }

        coEvery { accounts.getAllAccounts() } returns listOf(target)
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        coVerify(exactly = 0) { accounts.selectAccount(target) }
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), command, any()) }

        val released = CompletableDeferred<List<WalletAccount>>()
        coEvery { accounts.getAllAccounts() } coAnswers { released.await() }
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        pending.value = true
        writeAccess.beginReset()
        released.complete(listOf(target))
        runCurrent()
        coVerify(exactly = 0) { accounts.selectAccount(target) }
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), command, any()) }
    }

    @Test
    fun restoredNoticeWaitsForAnOriginalLiveGrantAndOpensStatusWithoutScheduling() = runTest {
        val uuid = AccountUuid.new(ByteArray(16))
        val ownerKey = uuid.toStorageKeyId()
        val target = mockk<WalletAccount> { every { sdkAccount } returns Account.new(uuid) }
        val accounts = mockk<AccountDataSource>(relaxed = true)
        val releaseLookup = CompletableDeferred<List<WalletAccount>>()
        coEvery { accounts.getAllAccounts() } coAnswers { releaseLookup.await() }
        val navigation = spyk(LaunchNavigationRequests())
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val grants = MutableSharedFlow<() -> Boolean>(replay = 1)
        var originalGrant = true
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), navigation, accounts, mockk(), mockk(), reset,
            mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true), WalletWriteAccess(reset),
            appAccess = notificationAccess(grants), navigationRouter = resumedRouter(),
        )
        mockkObject(WorkManager.Companion)
        try {
            hooks.restoreNotificationProgress(ownerKey, this)
            runCurrent()
            assertEquals(ownerKey, navigation.resolvingMigrationNotice?.ownerKey)
            coVerify(exactly = 0) { accounts.getAllAccounts() }

            grants.emit { originalGrant }
            runCurrent()
            coVerify(exactly = 1) { accounts.getAllAccounts() }
            originalGrant = false
            releaseLookup.complete(listOf(target))
            runCurrent()
            coVerify(exactly = 0) { accounts.selectAccount(target) }

            hooks.restoreNotificationProgress(ownerKey, this)
            grants.emit { true }
            runCurrent()
            coVerify(exactly = 0) { accounts.selectAccount(target) }
            val progress = NavigationCommand.ReplaceAll(listOf(MigrationProgressArgs(ownerKey)))
            verify(exactly = 1) { navigation.finishMigrationProgress(any(), progress, any()) }
            verify(exactly = 0) { WorkManager.getInstance(any()) }
        } finally {
            unmockkObject(WorkManager.Companion)
        }
    }

    private fun notificationAccess(grants: Flow<() -> Boolean> = flowOf({ true })) =
        mockk<AppAccessSession> { every { authorizedGrants() } returns grants }

    private fun resumedRouter() = mockk<NavigationRouter> {
        every { activeEntry } returns MutableStateFlow(ActiveNavigationEntry("home", null))
    }

    private fun retirementContext(commitSucceeds: Boolean = true): Context {
        val recorded = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val editor = mockk<SharedPreferences.Editor> {
            every { putBoolean(any(), true) } answers {
                recorded.add(firstArg())
                this@mockk
            }
            every { remove(any()) } answers {
                recorded.remove(firstArg())
                this@mockk
            }
            every { commit() } returns commitSucceeds
        }
        val preferences = mockk<SharedPreferences> {
            every { getBoolean(any(), false) } answers { firstArg<String>() in recorded }
            every { edit() } returns editor
        }
        return mockk<Context> {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns preferences
        }
    }

    private fun pendingReset() = mockk<ResetProgressStore> {
        every { isPending } returns MutableStateFlow(true)
    }
}
