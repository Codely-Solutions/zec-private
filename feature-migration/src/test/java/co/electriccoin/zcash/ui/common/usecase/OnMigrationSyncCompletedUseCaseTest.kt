package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.work.MigrationDriveOnce
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OnMigrationSyncCompletedUseCaseTest {
    private fun useCase(
        sdk: OrchardMigrationSdk,
        migrationNotifier: MigrationNotifier = mockk(relaxed = true),
        migrationScheduler: MigrationScheduler = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
        drive: MigrationDriveOnce = migrationDrive(),
    ) = OnMigrationSyncCompletedUseCase(
        getOrchardMigrationSdk =
            mockk<GetOrchardMigrationSdkUseCase> {
                coEvery { fromHydratedAccounts(any()) } returns sdk
            },
        migrationNotifier = migrationNotifier,
        migrationScheduler = migrationScheduler,
        context = context,
        drive = drive,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSyncCannotReconcileWhileTheDriverOwnsTheSdkTransfer() = runTest {
        val drive = migrationDrive()
        val sdk = mockk<OrchardMigrationSdk>(relaxed = true)
        val release = CompletableDeferred<Unit>()
        val activeDrive = launch { drive.withIdleDrive { release.await() } }
        runCurrent()

        val reconcile = launch { useCase(sdk, drive = drive)("original-account") }
        runCurrent()
        coVerify(exactly = 0) { sdk.finalizeReadyTransfers() }

        release.complete(Unit)
        activeDrive.join()
        reconcile.join()
        coVerify(exactly = 1) { sdk.finalizeReadyTransfers() }
        coVerify(exactly = 1) { sdk.reconcileInvalidations() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun withdrawnAccountSnapshotDoesNotBlockTheWalletReset() = runTest {
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow<List<WalletAccount>?>(null)
            coEvery { getAllAccounts() } coAnswers { awaitCancellation() }
        }
        val access = WalletWriteAccess()
        val resolver = GetOrchardMigrationSdkUseCase(mockk(), mockk(), mockk(), accounts, mockk())
        val reconcile = OnMigrationSyncCompletedUseCase(
            getOrchardMigrationSdk = resolver,
            migrationNotifier = mockk(),
            migrationScheduler = mockk(),
            context = mockk(),
            drive = migrationDrive(),
            walletWriteAccess = access,
        )

        val oldSync = launch { reconcile("original-account") }
        runCurrent()
        val reset = launch { access.beginReset() }
        runCurrent()

        assertTrue(oldSync.isCompleted)
        assertTrue(reset.isCompleted)
        coVerify(exactly = 0) { accounts.getAllAccounts() }
    }

    private fun migrationDrive() = MigrationDriveOnce(
        applicationContext = mockk(),
        migrationNotifier = mockk(),
        isMigrationTorEnabledStorageProvider = mockk(),
        pendingMigrationTorFailureStorageProvider = mockk(),
        synchronizerProvider = mockk(),
        lastNetworkActivity = mockk(),
        migrationUpgradeNotice = mockk(),
        resetProgress = mockk { every { isPending } returns MutableStateFlow(false) },
    )

    @Test
    fun finalizesProofBeforeReconciling() =
        runTest {
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { finalizeReadyTransfers() } returns 0
                    coEvery { reconcileInvalidations() } returns false
                }
            val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
            val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)

            useCase(
                sdk = sdk,
                migrationNotifier = migrationNotifier,
                migrationScheduler = migrationScheduler,
            ).invoke("account-key-1")

            coVerifyOrder {
                sdk.finalizeReadyTransfers()
                sdk.reconcileInvalidations()
            }
            coVerify(exactly = 0) { migrationNotifier.notifyMigrationPlanInvalid(any()) }
            verify(exactly = 0) { migrationScheduler.cancel(any()) }
        }

    @Test
    fun invalidationPathNotifiesAndCancelsBothLanes() =
        runTest {
            val sdk =
                mockk<OrchardMigrationSdk>(relaxed = true) {
                    coEvery { finalizeReadyTransfers() } returns 0
                    coEvery { reconcileInvalidations() } returns true
                }
            val migrationNotifier = mockk<MigrationNotifier>(relaxed = true)
            val migrationScheduler = mockk<MigrationScheduler>(relaxed = true)

            useCase(
                sdk = sdk,
                migrationNotifier = migrationNotifier,
                migrationScheduler = migrationScheduler,
            ).invoke("account-key-2")

            coVerify(exactly = 1) { migrationNotifier.notifyMigrationPlanInvalid("account-key-2") }
            verify(exactly = 1) { migrationScheduler.cancel("account-key-2") }
        }

    @Test
    fun failedRevivalRemainsObservableToTheCaller() =
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
            val scheduler = mockk<MigrationScheduler> {
                coEvery {
                    scheduleIfMissing(owner, 60.seconds, MigrationScheduler.wakeVersion(owner))
                } throws IOException("Cannot persist migration wake")
            }
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(context) } returns manager
                val reconcile = useCase(sdk, migrationScheduler = scheduler, context = context)
                assertFailsWith<IOException> { reconcile(owner) }
                coVerifyOrder {
                    sdk.reconcileInvalidations()
                    scheduler.scheduleIfMissing(owner, 60.seconds, MigrationScheduler.wakeVersion(owner))
                }
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }
}
