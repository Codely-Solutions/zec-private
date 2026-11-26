package co.electriccoin.zcash.work

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.MigrationSdkLookup
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationWorkerOwnershipTest {
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
            coVerify(exactly = 0) { drive.run(any(), any(), any(), any()) }
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
