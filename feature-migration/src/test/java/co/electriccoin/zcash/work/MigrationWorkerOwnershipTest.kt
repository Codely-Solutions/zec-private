package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import io.mockk.every
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
}
