package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationStateRepositoryTest {
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
