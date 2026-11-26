package co.electriccoin.zcash.work

import cash.z.ecc.android.sdk.Synchronizer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncWorkerTransportTest {
    private val currentStatus = MutableStateFlow(Synchronizer.Status.SYNCING)
    private val synchronizer = mockk<Synchronizer> { every { status } returns currentStatus }

    @Test
    fun workerStartingWithSuspendedSdkReturnsWithoutCompletingSync() = runTest {
        val sync = MutableStateFlow<Synchronizer?>(null)
        assertFalse(awaitBackgroundSync(sync, MutableStateFlow(true)))
    }

    @Test
    fun intentionalWithdrawalStopsRunningWorkerAndFreshProcessCanSync() = runTest {
        val suspended = MutableStateFlow(false)
        val sync = MutableStateFlow<Synchronizer?>(synchronizer)
        val waiting = async { awaitBackgroundSync(sync, suspended) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        suspended.value = true
        sync.value = null
        assertFalse(waiting.await())

        sync.value = synchronizer
        suspended.value = false
        currentStatus.value = Synchronizer.Status.SYNCED
        assertTrue(awaitBackgroundSync(sync, suspended))
    }
}
