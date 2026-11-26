package co.electriccoin.zcash.ui.common.repository

import androidx.lifecycle.Lifecycle
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProviderImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationStateRepositoryTest {
    @Test
    fun androidStopAndReturnRelayToTheSdkAndForegroundConsumers() = runTest {
        val application = ApplicationStateProviderImpl()
        val calls = mutableListOf<String>()
        val synchronizer = mockk<Synchronizer> {
            every { onForeground() } answers { calls += "foreground" }
            every { onBackground() } answers { calls += "background" }
        }
        application.onApplicationLifecycleChanged(Lifecycle.Event.ON_START)
        backgroundScope.launch {
            relaySynchronizerLifecycle(
                flowOf(synchronizer), application.isInForeground, flowOf(false), flowOf(false)
            )
        }
        val returns = mutableListOf<Unit>()
        backgroundScope.launch { application.observeOnForeground().collect { returns += Unit } }
        runCurrent()
        assertEquals(0, returns.size)

        application.onApplicationLifecycleChanged(Lifecycle.Event.ON_STOP)
        runCurrent()
        assertEquals(listOf("foreground", "background"), calls)

        application.onApplicationLifecycleChanged(Lifecycle.Event.ON_START)
        runCurrent()
        assertEquals(listOf("foreground", "background", "foreground"), calls)
        assertEquals(1, returns.size)
    }

    @Test
    fun foregroundStartWaitsForMigrationStateAndStopsWhenItIsBlocked() = runTest {
        val lifecycleCalls = mutableListOf<String>()
        val synchronizer = mockk<Synchronizer> {
            every { onForeground() } answers { lifecycleCalls += "foreground" }
            every { onBackground() } answers { lifecycleCalls += "background" }
        }
        val current = MutableStateFlow<Synchronizer?>(synchronizer)
        val foreground = MutableStateFlow(true)
        val blocked = MutableStateFlow(true)
        val pending = MutableStateFlow(false)
        val suspended = MutableStateFlow(false)
        backgroundScope.launch { relaySynchronizerLifecycle(current, foreground, blocked, pending, suspended) }
        runCurrent()
        blocked.value = false
        runCurrent()
        blocked.value = true
        runCurrent()
        foreground.value = false
        runCurrent()
        blocked.value = false
        runCurrent()
        foreground.value = true
        runCurrent()

        assertEquals(listOf("background", "foreground", "background", "foreground"), lifecycleCalls)

        suspended.value = true
        runCurrent()
        assertEquals("background", lifecycleCalls.last())
        suspended.value = false
        runCurrent()
        assertEquals("foreground", lifecycleCalls.last())

        // Reset preparation can fail while the existing SDK is still loaded.
        pending.value = true
        runCurrent()
        foreground.value = false
        runCurrent()
        foreground.value = true
        runCurrent()
        assertEquals(
            listOf("background", "foreground", "background", "foreground", "background", "foreground", "background"),
            lifecycleCalls,
        )
    }
}
