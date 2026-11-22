package co.electriccoin.zcash.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Operation
import androidx.work.WorkManager
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.work.MigrationWorker
import co.electriccoin.zcash.work.MigrationScheduler
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        val hooks = MigrationAppHooksImpl(mockk(), mockk(), mockk(), accounts, mockk(), context)
        mockkStatic(WorkManager::class)
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
        } finally {
            cancellation.cancel(false)
            unmockkStatic(WorkManager::class)
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
        val hooks = MigrationAppHooksImpl(mockk(), mockk(), mockk(), accounts, notifier, context)
        mockkStatic(WorkManager::class)
        mockkConstructor(MigrationScheduler::class)
        try {
            every { WorkManager.getInstance(context) } returns workManager
            every { anyConstructed<MigrationScheduler>().cancel(any()) } returns Unit

            hooks.cancelMigrationWork(null)

            verify(exactly = 1) { anyConstructed<MigrationScheduler>().cancel("fixture") }
            verify(exactly = 1) { notifier.cancel("fixture") }
            verify(exactly = 0) { anyConstructed<MigrationScheduler>().cancel("other") }
            coVerify(exactly = 0) { accounts.getAllAccounts() }
        } finally {
            unmockkConstructor(MigrationScheduler::class)
            unmockkStatic(WorkManager::class)
        }
    }
}
