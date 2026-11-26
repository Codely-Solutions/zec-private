package co.electriccoin.zcash.work

import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationDriveOnceForegroundTest {
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
