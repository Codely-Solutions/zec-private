package co.electriccoin.zcash.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Operation
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.MigrationTransferStateRepository
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.work.MigrationDriveOnce
import co.electriccoin.zcash.work.MigrationAccountRetirement
import co.electriccoin.zcash.work.MigrationLiveDriver
import co.electriccoin.zcash.work.MigrationWorker
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationAppHooksResetTest {
    @Test
    fun hardwareRetirementClosesItsOwnerAfterTheEnteredDriveLeaves() = runTest {
        val key = "retirement-owner"
        val drain = CompletableDeferred<Unit>()
        val cancel = CompletableDeferred<Unit>()
        val removed = CompletableDeferred<Unit>()
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
            mockk(), mockk(), LaunchNavigationRequests(), mockk(), mockk(relaxed = true), mockk(), reset, driver, drive,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            every { anyConstructed<MigrationScheduler>().nextWakeAt(key) } returns null
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(key) } coAnswers { cancel.await() }
            val retirement = launch { hooks.retireHardwareAccount(key) { removed.complete(Unit) } }
            runCurrent()

            assertFalse(MigrationAccountRetirement.blocks(key))
            assertFalse(MigrationAccountRetirement.blocks("other-owner"))
            assertFalse(removed.isCompleted)
            coVerify(exactly = 0) { driver.stopAndJoin(any()) }

            drain.complete(Unit)
            runCurrent()
            assertTrue(MigrationAccountRetirement.blocks(key))
            assertFalse(MigrationAccountRetirement.blocks("other-owner"))
            coVerify(exactly = 1) { driver.stopAndJoin(key) }
            assertFalse(removed.isCompleted)

            cancel.complete(Unit)
            runCurrent()
            assertTrue(retirement.isCompleted && removed.isCompleted)
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
        val driver = mockk<MigrationLiveDriver>(relaxed = true)
        val drive = mockk<MigrationDriveOnce>(relaxed = true) {
            coEvery { withIdleDrive<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>().invoke() }
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), reset, driver, drive,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            val originalWake = 1795560000000L
            val sdk = GetOrchardMigrationSdkUseCase(mockk(), mockk(), mockk(), mockk(), mockk())
            every { anyConstructed<MigrationScheduler>().nextWakeAt(key) } returns originalWake
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(key) } coAnswers {
                assertEquals(MigrationSdkLookup.RemovalManaged, sdk.lookup(key))
            }
            coEvery { anyConstructed<MigrationScheduler>().restoreWake(key, originalWake) } coAnswers {
                assertFalse(MigrationAccountRetirement.blocks(key))
            }

            assertFailsWith<IllegalStateException> {
                hooks.retireHardwareAccount(key) { error("Synthetic account deletion failed") }
            }
            assertFalse(MigrationAccountRetirement.blocks(key))
            coVerifyOrder {
                anyConstructed<MigrationScheduler>().cancelAndWait(key)
                anyConstructed<MigrationScheduler>().restoreWake(key, originalWake)
                driver.startIfNotRunning(key)
            }
            coVerify(exactly = 0) { anyConstructed<MigrationScheduler>().restoreWake(neq(key), any()) }
        } finally {
            MigrationAccountRetirement.restore(key)
            unmockkConstructor(MigrationScheduler::class)
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
            mockk(), mockk(), navigation, accounts, mockk(relaxed = true), mockk(), reset,
            mockk(relaxed = true), drive, mockk(relaxed = true), mockk(relaxed = true), readouts,
        )
        mockkConstructor(MigrationScheduler::class)
        try {
            every { anyConstructed<MigrationScheduler>().nextWakeAt(key) } returns null
            coEvery { anyConstructed<MigrationScheduler>().cancelAndWait(key) } returns Unit

            hooks.restoreNotificationProgress(key, this, runStepPending = true)
            runCurrent()
            assertTrue(navigation.resolvingMigrationNotice?.ownerKey == key)
            hooks.retireHardwareAccount(key) {}
            lookup.complete(listOf(target))
            runCurrent()

            assertTrue(navigation.resolvingMigrationNotice == null)
            coVerify(exactly = 0) { accounts.selectAccount(target) }
            coVerify(exactly = 0) { anyConstructed<MigrationScheduler>().runNotificationStep(any()) }
            verify(exactly = 1) { readouts.clear(key) }
            verify(exactly = 1) { navigation.finishMigrationProgress(any(), null) }
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
        val driveOnce = mockk<MigrationDriveOnce>(relaxed = true)
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), mockk(relaxed = true), accounts, mockk(), context, pendingReset(), driver, driveOnce,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        )
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns workManager
            val reset = launch { hooks.cancelMigrationWork(null) }
            runCurrent()

            coVerify(exactly = 0) { accounts.getAllAccounts() }
            verify(exactly = 1) { workManager.cancelAllWorkByTag(MigrationWorker::class.java.name) }
            assertFalse(reset.isCompleted)

            cancellation.set(Operation.SUCCESS)
            runCurrent()
            assertTrue(reset.isCompleted)
            coVerifyOrder {
                workManager.cancelAllWorkByTag(MigrationWorker::class.java.name)
                driver.stopAll()
                driveOnce.awaitIdle()
            }
        } finally {
            cancellation.cancel(false)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun resetCancelsOwnedFallbackAlarmWithoutAnAccountSnapshot() = runTest {
        val preferences = mockk<SharedPreferences> {
            every { all } returns mapOf("next_wake_at_fixture" to 100L, "last_run_at_fixture" to 5L, "other" to 1L)
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
        )
        mockkObject(WorkManager.Companion)
        mockkConstructor(MigrationScheduler::class)
        try {
            every { WorkManager.getInstance(context) } returns workManager
            every { anyConstructed<MigrationScheduler>().cancel(any()) } returns Unit

            hooks.cancelMigrationWork(null)

            verify(exactly = 1) { anyConstructed<MigrationScheduler>().cancel("fixture") }
            verify(exactly = 1) { notifier.cancel("fixture") }
            verify(exactly = 1) { notifier.cancelStepDue("fixture") }
            verify(exactly = 0) { anyConstructed<MigrationScheduler>().cancel("other") }
            coVerify(exactly = 0) { accounts.getAllAccounts() }
        } finally {
            unmockkConstructor(MigrationScheduler::class)
            unmockkObject(WorkManager.Companion)
        }
    }

    @Test
    fun restoringProgressKeepsTheAccountAndRefusesSelectionAfterReset() = runTest {
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
        )
        val command = NavigationCommand.ReplaceAll(listOf(HomeArgs, MigrationProgressArgs(ownerKey)))
        coEvery { accounts.getAllAccounts() } returns listOf(target)
        coEvery { accounts.selectAccount(target) } throws IllegalStateException("fixture selection failed")
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), null) }
        verify(exactly = 0) { launchNavigation.finishMigrationProgress(any(), command) }

        coEvery { accounts.selectAccount(target) } returns Unit
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        coVerify(exactly = 2) { accounts.selectAccount(target) }
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), command) }

        val released = CompletableDeferred<List<WalletAccount>>()
        coEvery { accounts.getAllAccounts() } coAnswers { released.await() }
        hooks.restoreNotificationProgress(ownerKey, this)
        runCurrent()
        pending.value = true
        writeAccess.beginReset()
        released.complete(listOf(target))
        runCurrent()
        coVerify(exactly = 2) { accounts.selectAccount(target) }
        verify(exactly = 1) { launchNavigation.finishMigrationProgress(any(), command) }
    }

    @Test
    fun dueNoticeResumesItsKickAndWaitsForSchedulerAcknowledgement() = runTest {
        val uuid = AccountUuid.new(ByteArray(16))
        val ownerKey = uuid.toStorageKeyId()
        val target = mockk<WalletAccount> { every { sdkAccount } returns Account.new(uuid) }
        val accounts = mockk<AccountDataSource>(relaxed = true)
        val releaseLookup = CompletableDeferred<List<WalletAccount>>()
        coEvery { accounts.getAllAccounts() } coAnswers { releaseLookup.await() }
        val navigation = spyk(LaunchNavigationRequests())
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val hooks = MigrationAppHooksImpl(
            mockk(), mockk(), navigation, accounts, mockk(), mockk(), reset,
            mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true), WalletWriteAccess(reset),
        )
        val acknowledged = CompletableDeferred<Unit>()
        mockkConstructor(MigrationScheduler::class)
        try {
            coEvery { anyConstructed<MigrationScheduler>().runNotificationStep(ownerKey) } coAnswers {
                acknowledged.await()
            }
            val oldActivity = launch { hooks.restoreNotificationProgress(ownerKey, this, runStepPending = true) }
            runCurrent()
            val saved = checkNotNull(navigation.resolvingMigrationNotice)
            assertTrue(saved.runStepPending)
            oldActivity.cancel()

            hooks.restoreNotificationProgress(saved.ownerKey, this, saved.runStepPending)
            releaseLookup.complete(listOf(target))
            runCurrent()
            verify(exactly = 0) { navigation.finishMigrationProgress(any(), any()) }
            acknowledged.complete(Unit)
            runCurrent()

            coVerify(exactly = 1) { anyConstructed<MigrationScheduler>().runNotificationStep(ownerKey) }
            val progress = NavigationCommand.ReplaceAll(listOf(HomeArgs, MigrationProgressArgs(ownerKey)))
            verify(exactly = 1) { navigation.finishMigrationProgress(any(), progress) }
        } finally {
            acknowledged.complete(Unit)
            unmockkConstructor(MigrationScheduler::class)
        }
    }

    private fun pendingReset() = mockk<ResetProgressStore> {
        every { isPending } returns MutableStateFlow(true)
    }
}
