package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.work.MigrationScheduler
import com.google.common.util.concurrent.Futures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class OnMigrationSyncCompletedUseCaseTest {
    private fun useCase(
        sdk: OrchardMigrationSdk,
        lastNetworkActivity: LastNetworkActivityStorageProvider = mockk(relaxed = true),
        migrationNotifier: MigrationNotifier = mockk(relaxed = true),
        migrationScheduler: MigrationScheduler = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
    ) = OnMigrationSyncCompletedUseCase(
        getOrchardMigrationSdk =
            mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { this@mockk(any()) } returns sdk
            },
        lastNetworkActivity = lastNetworkActivity,
        migrationNotifier = migrationNotifier,
        migrationScheduler = migrationScheduler,
        context = context,
    )

    @Test
    fun happyPathCallsFinalizeThenReconcileThenStamp() =
        runTest {
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { finalizeReadyTransfers() } returns 0
                    coEvery { reconcileInvalidations() } returns false
                }
            val lastNetworkActivity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
            val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
            val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)

            useCase(
                sdk = sdk,
                lastNetworkActivity = lastNetworkActivity,
                migrationNotifier = migrationNotifier,
                migrationScheduler = migrationScheduler,
            ).invoke("account-key-1")

            coVerifyOrder {
                sdk.finalizeReadyTransfers()
                sdk.reconcileInvalidations()
                lastNetworkActivity.stampNow()
            }
            coVerify(exactly = 0) { migrationNotifier.notifyMigrationPlanInvalid(any()) }
            verify(exactly = 0) { migrationScheduler.cancel(any()) }
        }

    @Test
    fun invalidationPathNotifiesCancelsBothLanesAndStillStamps() =
        runTest {
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { finalizeReadyTransfers() } returns 0
                    coEvery { reconcileInvalidations() } returns true
                }
            val lastNetworkActivity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
            val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
            val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)

            useCase(
                sdk = sdk,
                lastNetworkActivity = lastNetworkActivity,
                migrationNotifier = migrationNotifier,
                migrationScheduler = migrationScheduler,
            ).invoke("account-key-2")

            coVerify(exactly = 1) { migrationNotifier.notifyMigrationPlanInvalid("account-key-2") }
            verify(exactly = 1) { migrationScheduler.cancel("account-key-2") }
            coVerify(exactly = 1) { lastNetworkActivity.stampNow() }
        }

    @Test
    fun failedRevivalPreservesTheObservedSyncTime() =
        runTest {
            val owner = "synced-fixture-owner"
            val sdk = mockk<OrchardMigrationSdk> {
                coEvery { finalizeReadyTransfers() } returns 0
                coEvery { reconcileInvalidations() } returns false
                coEvery { getMigrationStateUnreconciled() } returns MigrationState.InProgress(mockk(relaxed = true))
            }
            val context = mockk<Context>()
            val manager = mockk<WorkManager> {
                every { getWorkInfosForUniqueWork(MigrationScheduler.workId(owner)) } returns
                    Futures.immediateFuture(emptyList<WorkInfo>())
            }
            val lastNetworkActivity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
            val scheduler = mockk<MigrationScheduler> {
                coEvery { scheduleIfMissing(owner, 60.seconds) } throws IOException("Cannot persist migration wake")
            }
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(context) } returns manager
                val reconcile = useCase(sdk, lastNetworkActivity, migrationScheduler = scheduler, context = context)
                assertFailsWith<IOException> { reconcile(owner) }
                coVerifyOrder {
                    lastNetworkActivity.stampNow()
                    scheduler.scheduleIfMissing(owner, 60.seconds)
                }
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }

}
