package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.global.WalletMigrationSyncGate
import co.electriccoin.zcash.ui.common.migration.MigrationSyncedHook
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SynchronizerProviderMigrationDiagnosticsTest {
    @Test
    fun aNewSyncRemainsUnrecordedUntilItsWalletActivityIsStamped() = runTest {
        val status = MutableStateFlow(Synchronizer.Status.SYNCING)
        val synchronizer = mockk<Synchronizer>(relaxed = true) {
            every { this@mockk.status } returns status
        }
        val coordinator = mockk<WalletCoordinator> {
            every { this@mockk.synchronizer } returns MutableStateFlow<Synchronizer?>(synchronizer)
            every { isSeedMismatch } returns MutableStateFlow(false)
        }
        val runningStamp = CompletableDeferred<Unit>()
        val allowStamp = CompletableDeferred<Unit>()
        val handoff = CompletableDeferred<Unit>()
        val finishReconciliation = CompletableDeferred<Unit>()
        val activity = mockk<LastNetworkActivityStorageProvider> {
            coEvery { stampNow() } coAnswers {
                runningStamp.complete(Unit)
                allowStamp.await()
            }
        }
        val hook = mockk<MigrationSyncedHook> {
            coEvery { onSynced() } coAnswers {
                handoff.complete(Unit)
                finishReconciliation.await()
            }
        }
        val provider = SynchronizerProviderImpl(
            coordinator, mockk(), lazyOf(hook), WalletMigrationSyncGate(), activity, backgroundScope,
        )
        runCurrent()

        status.value = Synchronizer.Status.SYNCED
        runningStamp.await()
        assertFalse(provider.hasRecordedSync(synchronizer))
        allowStamp.complete(Unit)
        handoff.await()
        assertTrue(provider.hasRecordedSync(synchronizer))

        status.value = Synchronizer.Status.SYNCING
        runCurrent()
        assertFalse(provider.hasRecordedSync(synchronizer))
        status.value = Synchronizer.Status.SYNCED
        runCurrent()
        assertTrue(provider.hasRecordedSync(synchronizer))
        finishReconciliation.complete(Unit)
    }

    @Test
    fun syncedHookFailureKeepsRawDetailsPrivateAndNextSyncRetries() = runTest {
        val status = MutableStateFlow(Synchronizer.Status.SYNCING)
        val synchronizer = mockk<Synchronizer>(relaxed = true) {
            every { this@mockk.status } returns status
        }
        val coordinator = mockk<WalletCoordinator> {
            every { this@mockk.synchronizer } returns MutableStateFlow<Synchronizer?>(synchronizer)
            every { isSeedMismatch } returns MutableStateFlow(false)
        }
        val detail = "synthetic wallet database detail"
        val hook = mockk<MigrationSyncedHook> {
            coEvery { onSynced() } throws IllegalStateException(detail)
        }
        ShadowLog.clear()
        val activity = mockk<LastNetworkActivityStorageProvider>(relaxed = true)
        SynchronizerProviderImpl(
            coordinator, mockk(), lazyOf(hook), WalletMigrationSyncGate(), activity, backgroundScope,
        )
        runCurrent()

        status.value = Synchronizer.Status.SYNCED
        runCurrent()
        coVerify(exactly = 1) { hook.onSynced() }
        coVerifyOrder {
            activity.stampNow()
            hook.onSynced()
        }
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { "Migration sync hook failed (IllegalStateException)" in it.msg })
        assertTrue(logs.none { detail in it.msg || detail in it.throwable?.stackTraceToString().orEmpty() })

        coEvery { hook.onSynced() } returns Unit
        status.value = Synchronizer.Status.SYNCING
        runCurrent()
        status.value = Synchronizer.Status.SYNCED
        runCurrent()
        coVerify(exactly = 2) { hook.onSynced() }
        coVerify(exactly = 2) { activity.stampNow() }
    }
}
