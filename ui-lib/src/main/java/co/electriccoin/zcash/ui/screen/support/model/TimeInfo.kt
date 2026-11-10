package co.electriccoin.zcash.ui.screen.support.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/** Report date helps correlate diagnostics without collecting installation or device uptime history. */
data class TimeInfo(val currentTime: Instant) {
    fun toSupportString(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "Report date (UTC): ${dateFormat.format(Date(currentTime.toEpochMilliseconds()))}\n"
    }

    companion object {
        fun new() = TimeInfo(Clock.System.now())
    }
}
