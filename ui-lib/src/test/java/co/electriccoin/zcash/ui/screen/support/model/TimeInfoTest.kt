package co.electriccoin.zcash.ui.screen.support.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TimeInfoTest {
    @Test
    fun support_report_includes_only_calendar_date() {
        val report = TimeInfo(Instant.parse("2026-11-10T12:34:56.789Z")).toSupportString()
        assertEquals("Report date (UTC): 2026-11-10\n", report)
    }
}
