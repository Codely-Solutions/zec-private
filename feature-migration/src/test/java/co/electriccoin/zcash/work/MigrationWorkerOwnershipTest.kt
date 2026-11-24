package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationWorkerOwnershipTest {
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
