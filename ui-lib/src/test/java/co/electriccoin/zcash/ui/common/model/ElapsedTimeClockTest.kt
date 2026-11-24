package co.electriccoin.zcash.ui.common.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ElapsedTimeClockTest {
    @Test
    fun carriesForwardElapsedTimeAfterWallCorrections() = runTest {
        val origin = Instant.parse("2026-11-24T00:00:00Z")
        var wall = origin
        val clock = ElapsedTimeClock(object : Clock { override fun now() = wall }, testScheduler.timeSource)
        advanceTimeBy(3_000)
        wall = origin - 10.seconds
        assertEquals(origin + 3.seconds, clock.now())
        wall = origin + 8.seconds
        assertEquals(wall, clock.now())
        advanceTimeBy(4_000)
        wall = origin
        assertEquals(origin + 12.seconds, clock.now())
    }
}
