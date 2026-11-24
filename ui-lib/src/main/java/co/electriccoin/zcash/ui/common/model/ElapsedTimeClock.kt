package co.electriccoin.zcash.ui.common.model

import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Keeps an observed deadline from reopening after a backward device clock correction. */
internal class ElapsedTimeClock(
    private val wallClock: Clock = Clock.System,
    private val elapsedTime: TimeSource = AndroidElapsedTimeSource,
) : Clock {
    private var anchorTime = wallClock.now()
    private var anchorMark = elapsedTime.markNow()

    @Synchronized
    override fun now(): kotlin.time.Instant {
        val projected = anchorTime + anchorMark.elapsedNow()
        val current = wallClock.now()
        if (current > projected) {
            anchorTime = current
            anchorMark = elapsedTime.markNow()
        }
        return maxOf(projected, current)
    }
}

internal object AndroidElapsedTimeSource : AbstractLongTimeSource(DurationUnit.MILLISECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtime()
}
