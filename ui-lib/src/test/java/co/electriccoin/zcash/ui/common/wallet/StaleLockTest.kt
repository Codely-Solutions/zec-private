package co.electriccoin.zcash.ui.common.wallet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StaleLockTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshesWhenCollectionResumesWithAnExpiredRate() =
        runTest {
            val timestamp = MutableStateFlow<Instant?>(Clock.System.now())
            var refreshes = 0
            val lock = StaleLock(timestamp, 15.minutes) { refreshes++ }
            val foreground = launch { lock.state.collect {} }
            runCurrent()
            foreground.cancelAndJoin()

            // The flow was unobserved when the rate expired.
            timestamp.value = Clock.System.now() - 16.minutes
            assertTrue(lock.state.first())
            assertEquals(1, refreshes)
        }

    @Test
    fun refreshesOnceWhenTheRateExpiresDuringCollection() =
        runTest {
            var refreshes = 0
            val lock = StaleLock(flowOf(Clock.System.now() - 14.minutes), 15.minutes) { refreshes++ }

            assertEquals(listOf(false, true), lock.state.toList())
            assertEquals(1, refreshes)
        }

    @Test
    fun doesNotRefreshWhenNoRateHasLoaded() =
        runTest {
            var refreshes = 0
            val lock = StaleLock(flowOf(null), 15.minutes) { refreshes++ }

            assertEquals(listOf(false), lock.state.toList())
            assertEquals(0, refreshes)
        }
}
