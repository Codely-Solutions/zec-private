package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.ui.common.migration.MigrationSyncedHook
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SynchronizerProviderMigrationDiagnosticsTest {
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
        SynchronizerProviderImpl(coordinator, mockk(), lazyOf(hook), backgroundScope)
        runCurrent()

        status.value = Synchronizer.Status.SYNCED
        runCurrent()
        coVerify(exactly = 1) { hook.onSynced() }
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { "Migration sync hook failed (IllegalStateException)" in it.msg })
        assertTrue(logs.none { detail in it.msg || detail in it.throwable?.stackTraceToString().orEmpty() })

        coEvery { hook.onSynced() } returns Unit
        status.value = Synchronizer.Status.SYNCING
        runCurrent()
        status.value = Synchronizer.Status.SYNCED
        runCurrent()
        coVerify(exactly = 2) { hook.onSynced() }
    }
}
