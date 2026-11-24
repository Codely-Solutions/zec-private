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
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import co.electriccoin.zcash.work.MigrationDriveOnce
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationAppHooksResetTest {
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
            mockk(), mockk(), writeAccess,
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
            mockk(), mockk(), WalletWriteAccess(reset),
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
