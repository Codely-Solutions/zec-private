package co.electriccoin.zcash.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MigrationWorkerOwnershipTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theCurrentWorkerWaitsForItsSuccessorAndRetriesIfTheAppendFails() = runTest {
        val owner = "successor-fixture-owner"
        val workId = MigrationScheduler.workId(owner)
        val params = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.Builder().putString(MigrationScheduler.KEY_ACCOUNT_KEY_ID, owner).build()
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns prefs
            every { getSystemService(Context.ALARM_SERVICE) } returns null
        }
        val failed = SettableFuture.create<Operation.State.SUCCESS>()
        val acknowledged = SettableFuture.create<Operation.State.SUCCESS>()
        val manager = mockk<WorkManager> {
            every { getWorkInfosForUniqueWork(workId) } returns Futures.immediateFuture(emptyList<WorkInfo>())
            every { enqueueUniqueWork(workId, ExistingWorkPolicy.APPEND, any<OneTimeWorkRequest>()) } returnsMany
                listOf(futureOperation(failed), futureOperation(acknowledged))
        }
        val sdk = mockk<OrchardMigrationSdk>()
        startKoin {
            modules(module {
                single { mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) } }
                single { mockk<MigrationNotifier>(relaxed = true) }
                single {
                    mockk<GetOrchardMigrationSdkUseCase> {
                        coEvery { lookup(owner) } returns MigrationSdkLookup.Ready(sdk)
                    }
                }
                single {
                    mockk<MigrationDriveOnce> {
                        coEvery { run(sdk, owner) } returnsMany listOf(
                            DriveOnceResult.LockBusy(30.seconds),
                            DriveOnceResult.NextWake(30.seconds),
                            DriveOnceResult.NextWake(30.seconds)
                        )
                    }
                }
            })
        }
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            val worker = MigrationWorker(context, params)
            val first = async { worker.doWork() }
            runCurrent()
            assertFalse(first.isCompleted)
            verify(exactly = 0) { prefs.edit() }
            failed.setException(IOException("Cannot persist the next wake"))
            assertEquals(Result.retry(), first.await())
            verify(exactly = 0) { prefs.edit() }

            val second = async { worker.doWork() }
            runCurrent()
            assertFalse(second.isCompleted)
            acknowledged.set(Operation.SUCCESS)
            assertEquals(Result.success(), second.await())
            verify(exactly = 1) { prefs.edit() }
            verify(exactly = 2) {
                manager.enqueueUniqueWork(workId, ExistingWorkPolicy.APPEND, any<OneTimeWorkRequest>())
            }

            val retained = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.BLOCKED
                every { initialDelayMillis } returns 75_000L
            }
            every { manager.getWorkInfosForUniqueWork(workId) } returns Futures.immediateFuture(listOf(retained))
            val restartedAt = System.currentTimeMillis()
            assertEquals(Result.success(), worker.doWork())
            verify(exactly = 2) { prefs.edit() }
            verify { prefs.edit().putLong("next_wake_at_$owner", more(restartedAt + 74_999)) }
            verify(exactly = 2) {
                manager.enqueueUniqueWork(workId, ExistingWorkPolicy.APPEND, any<OneTimeWorkRequest>())
            }
        } finally {
            failed.cancel(false)
            acknowledged.cancel(false)
            unmockkObject(WorkManager.Companion)
            stopKoin()
        }
    }

    @Test
    fun anExistingWakeKeepsItsOriginalFallback() = runTest {
        val owner = "kept-fixture-owner"
        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns prefs
        }
        val workId = MigrationScheduler.workId(owner)
        val manager = mockk<WorkManager> {
            every { enqueueUniqueWork(workId, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } returns
                mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
            every { getWorkInfoById(any()) } returns Futures.immediateFuture(null)
        }
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            MigrationScheduler(context).scheduleIfMissing(owner)
            verify(exactly = 1) { manager.getWorkInfoById(any()) }
            verify(exactly = 0) { prefs.edit() }
        } finally {
            unmockkObject(WorkManager.Companion)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aLiveHeartbeatDoesNotReplaceAConfirmedDelayedWake() = runTest {
        val owner = "delayed-fixture-owner"
        val workId = MigrationScheduler.workId(owner)
        var liveRunAt = -1L
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putLong("last_run_at_$owner", any()) } answers {
            liveRunAt = secondArg()
            editor
        }
        val prefs = mockk<SharedPreferences> {
            every { edit() } returns editor
            every { getLong("last_run_at_$owner", -1L) } answers { liveRunAt }
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences("migration_worker_heartbeat", Context.MODE_PRIVATE) } returns prefs
            every { getSystemService(Context.ALARM_SERVICE) } returns null
        }
        val acknowledged = SettableFuture.create<Operation.State.SUCCESS>()
        val request = slot<OneTimeWorkRequest>()
        val pending = mockk<WorkInfo> { every { state } returns WorkInfo.State.ENQUEUED }
        val manager = mockk<WorkManager> {
            every { enqueueUniqueWork(workId, ExistingWorkPolicy.KEEP, capture(request)) } returns
                futureOperation(acknowledged)
            every { getWorkInfoById(any()) } returns Futures.immediateFuture(pending)
        }
        mockkObject(WorkManager.Companion)
        try {
            every { WorkManager.getInstance(context) } returns manager
            val scheduling = async { MigrationScheduler(context).scheduleIfMissing(owner, 60.seconds) }
            runCurrent()
            assertFalse(scheduling.isCompleted)
            MigrationWorkerHeartbeat.stampRun(context, owner)
            assertTrue(liveRunAt > 0)
            acknowledged.set(Operation.SUCCESS)
            scheduling.await()
            verify { manager.getWorkInfoById(request.captured.id) }
            verify { editor.putLong("next_wake_at_$owner", more(liveRunAt + 59_999)) }
        } finally {
            acknowledged.cancel(false)
            unmockkObject(WorkManager.Companion)
        }
    }

    private fun futureOperation(result: SettableFuture<Operation.State.SUCCESS>): Operation =
        mockk { every { this@mockk.result } returns result }

    @Test
    fun workerRetriesAnUnavailableSdkAndRechecksEngineStateAfterAnInterruptedDrive() = runTest {
        val owner = "recovering-worker-owner"
        val params = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.Builder().putString(MigrationScheduler.KEY_ACCOUNT_KEY_ID, owner).build()
        }
        val sdk = mockk<OrchardMigrationSdk>()
        val resolver = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { lookup(owner) } returnsMany listOf(
                MigrationSdkLookup.NotReady, MigrationSdkLookup.Ready(sdk), MigrationSdkLookup.Ready(sdk),
            )
        }
        val drive = mockk<MigrationDriveOnce> {
            coEvery { run(sdk, owner) } throws IOException("SDK state temporarily unavailable") andThen
                DriveOnceResult.Terminal
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val notifier = mockk<MigrationNotifier>(relaxed = true)
        startKoin {
            modules(module {
                single { resolver }
                single { drive }
                single { reset }
                single { notifier }
            })
        }
        try {
            val worker = MigrationWorker(mockk<Context>(relaxed = true), params)
            assertEquals(Result.retry(), worker.doWork())
            coVerify(exactly = 0) { drive.run(any(), any(), any()) }
            assertEquals(Result.retry(), worker.doWork())
            assertEquals(Result.success(), worker.doWork())
            coVerify(exactly = 3) { resolver.lookup(owner) }
            coVerify(exactly = 2) { drive.run(sdk, owner) }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun resolverDistinguishesFailedStorageReadConfirmedAbsenceAndCancellation() = runTest {
        val wallet = mockk<PersistableWalletProvider> {
            coEvery { getPersistableWallet() } throws IOException("Wallet store temporarily unavailable") andThen null
        }
        val resolver = GetOrchardMigrationSdkUseCase(mockk(), wallet, mockk(), mockk(), mockk())
        val owner = "temporarily-unavailable-owner"

        assertEquals(MigrationSdkLookup.NotReady, resolver.lookup(owner))
        assertEquals(MigrationSdkLookup.Gone, resolver.lookup(owner))
        coEvery { wallet.getPersistableWallet() } throws CancellationException("reset started")
        assertFailsWith<CancellationException> { resolver.lookup(owner) }
    }

    @Test
    fun ownerlessLegacyWorkStopsBeforeResolvingAnAccount() = runTest {
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.EMPTY
        }

        val worker = MigrationWorker(mockk<Context>(), parameters)
        assertEquals(Result.success(), worker.doWork())
    }

    @Test
    fun scheduledWorkerRetriesUntilItsOwnerRemovalCommits() = runTest {
        val key = "worker-retirement-owner"
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns Data.Builder().putString(MigrationScheduler.KEY_ACCOUNT_KEY_ID, key).build()
        }
        val reset = mockk<ResetProgressStore> { every { isPending } returns MutableStateFlow(false) }
        val resolver = mockk<GetOrchardMigrationSdkUseCase> {
            coEvery { lookup(key) } returns MigrationSdkLookup.RemovalManaged
        }
        startKoin {
            modules(module {
                single { reset }
                single { resolver }
            })
        }
        MigrationAccountRetirement.begin(key)
        try {
            val worker = MigrationWorker(mockk<Context>(), parameters)
            assertEquals(Result.retry(), worker.doWork())
            MigrationAccountRetirement.markRemoved(key)
            assertEquals(Result.success(), worker.doWork())
        } finally {
            MigrationAccountRetirement.restore(key)
            stopKoin()
        }
    }
}
