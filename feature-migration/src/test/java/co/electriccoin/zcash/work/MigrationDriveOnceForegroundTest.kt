package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.MigrationAdvanceResult
import cash.z.ecc.android.sdk.MigrationAdvanceStep
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.provider.LastNetworkActivityStorageProvider
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationDriveOnceForegroundTest {
    @Test
    fun aFailedQuietWindowTimestampStillResumesThePausedSynchronizer() = runTest {
        val transport = mockk<CloseableSynchronizer>(relaxed = true) {
            every { status } returns MutableStateFlow(Synchronizer.Status.SYNCING)
        }
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { nextStep() } returnsMany listOf(
                MigrationAdvanceResult(MigrationAdvanceStep.Broadcast(7), null), null,
            )
            coEvery { getMigrationTransferStates() } returns null
            coEvery { estimatedChainTip() } returns 20L
            coEvery { privacySyncBufferDuration() } returns 15.seconds
        }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(relaxed = true),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk {
                every { synchronizer } returns MutableStateFlow(transport)
                coEvery { getSynchronizerOrNull() } returns transport
            },
            lastNetworkActivity = mockk {
                coEvery { get() } returns null
                coEvery { stampNow() } throws IOException("Timestamp store unavailable")
            },
            applicationStateProvider = mockk { every { isInForeground } returns flowOf(true) },
            migrationUpgradeNotice = mockk(),
            resetProgress = mockk { every { isPending } returns MutableStateFlow(false) },
        )

        assertFailsWith<IOException> { drive.run(sdk, "quiet-window-owner", allowForcedBroadcastWindow = true) }
        verifyOrder {
            transport.pause()
            transport.resume()
        }
        coVerify(exactly = 1) { sdk.nextStep() }
        assertEquals(DriveOnceResult.Terminal, drive.run(sdk, "quiet-window-owner", allowForcedBroadcastWindow = true))
        coVerify(exactly = 2) { sdk.nextStep() }
    }

    @Test
    fun walletSyncStartingDuringTheQuietPeriodReadBlocksManualCrossing() = runTest {
        val status = MutableStateFlow(Synchronizer.Status.SYNCED)
        val sync = mockk<Synchronizer> { every { this@mockk.status } returns status }
        val activity = mockk<LastNetworkActivityStorageProvider> {
            coEvery { get() } coAnswers {
                status.value = Synchronizer.Status.SYNCING
                null
            }
            every { latestObserved } returns null
        }
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { privacySyncBufferDuration() } returns 15.seconds
        }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk { every { synchronizer } returns MutableStateFlow(sync) },
            lastNetworkActivity = activity,
            applicationStateProvider = mockk(),
            migrationUpgradeNotice = mockk(),
            resetProgress = mockk(),
        )

        assertFalse(drive.mayBroadcastCrossing(sdk))
    }

    @Test
    fun aSyncRecordedDuringTheStatusReadKeepsManualCrossingInTheQuietWindow() = runTest {
        val started = CompletableDeferred<Unit>()
        val idle = CompletableDeferred<Unit>()
        val transport = mockk<Synchronizer> {
            every { status } returns flow {
                started.complete(Unit)
                idle.await()
                emit(Synchronizer.Status.SYNCED)
            }
        }
        var latest: Instant? = null
        val activity = mockk<LastNetworkActivityStorageProvider> {
            coEvery { get() } returns Instant.EPOCH
            every { latestObserved } answers { latest }
        }
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { privacySyncBufferDuration() } returns 15.seconds
        }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk {
                every { synchronizer } returns MutableStateFlow(transport)
                every { hasRecordedSync(transport) } returns true
            },
            lastNetworkActivity = activity,
            applicationStateProvider = mockk(),
            migrationUpgradeNotice = mockk(),
            resetProgress = mockk(),
        )
        val admitted = async { drive.mayBroadcastCrossing(sdk) }

        started.await()
        latest = Instant.now()
        idle.complete(Unit)

        assertFalse(admitted.await())
    }

    @Test
    fun aSyncedStatusWithoutItsObservationCannotAdmitACrossing() = runTest {
        val sync = mockk<Synchronizer> { every { status } returns MutableStateFlow(Synchronizer.Status.SYNCED) }
        val activity = mockk<LastNetworkActivityStorageProvider> {
            coEvery { get() } returns Instant.EPOCH
            every { latestObserved } returns null
        }
        var recorded = false
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { privacySyncBufferDuration() } returns 15.seconds
        }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk {
                every { synchronizer } returns MutableStateFlow(sync)
                every { hasRecordedSync(sync) } answers { recorded }
            },
            lastNetworkActivity = activity,
            applicationStateProvider = mockk(),
            migrationUpgradeNotice = mockk(),
            resetProgress = mockk(),
        )

        assertFalse(drive.mayBroadcastCrossing(sdk))
        recorded = true
        assertTrue(drive.mayBroadcastCrossing(sdk))
    }

    @Test
    fun aFailedNativeSyncBurstRecordsNetworkActivity() = runTest {
        val sync = mockk<Synchronizer> {
            coEvery { syncToTip(any()) } throws IOException("Synthetic sync failure")
        }
        val activity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
        val sdk = mockk<OrchardMigrationSdk> {
            coEvery { nextStep() } returns MigrationAdvanceResult(MigrationAdvanceStep.Reevaluate, null)
            coEvery { isSyncBlocked() } returns flowOf(false)
        }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk { coEvery { getSynchronizerOrNull() } returns sync },
            lastNetworkActivity = activity,
            applicationStateProvider = mockk(),
            migrationUpgradeNotice = mockk(),
            resetProgress = mockk { every { isPending } returns MutableStateFlow(false) },
        )

        assertFailsWith<IOException> { drive.run(sdk, "sync-burst-owner", allowForcedBroadcastWindow = false) }
        coVerify(exactly = 1) { activity.stampNow() }
        coVerify(exactly = 0) { sdk.finalizeReadyTransfers() }
    }

    @Test
    fun resetDrainsAnEnteredForegroundMutationAndRefusesNewWork() = runTest {
        val pending = MutableStateFlow(false)
        val reset = mockk<ResetProgressStore> { every { isPending } returns pending }
        val drive = MigrationDriveOnce(
            applicationContext = mockk(),
            migrationNotifier = mockk(),
            isMigrationTorEnabledStorageProvider = mockk(),
            pendingMigrationTorFailureStorageProvider = mockk(),
            synchronizerProvider = mockk(),
            lastNetworkActivity = mockk(),
            applicationStateProvider = mockk(),
            migrationUpgradeNotice = mockk(),
            resetProgress = reset,
        )
        val entered = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val operation = launch {
            drive.withExclusiveAccess("foreground-fixture") {
                entered.complete(Unit)
                returned.await()
            }
        }
        runCurrent()
        assertTrue(entered.isCompleted)

        pending.value = true
        val resetDrain = launch { drive.awaitIdle() }
        runCurrent()
        assertFalse(resetDrain.isCompleted)
        assertNull(drive.withExclusiveAccess<Unit>("foreground-fixture") { error("A second operation was admitted") })

        returned.complete(Unit)
        operation.join()
        resetDrain.join()
        assertNull(drive.withExclusiveAccess<Unit>("foreground-fixture") { error("Work began during pending reset") })
    }
}
