package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class LearningDisplayTest {
    @Test fun `elapsed time clamps future and handles hour boundaries`() {
        val now = 10_000_000L
        assertEquals(0L, elapsedMinutes(now, now + 1))
        assertEquals(0L, elapsedMinutes(now, now - 59_999))
        assertEquals(59L, elapsedMinutes(now, now - 59 * 60_000))
        assertEquals(60L, elapsedMinutes(now, now - 60 * 60_000))
        assertEquals(83L, elapsedMinutes(now, now - 83 * 60_000))
        assertTrue(elapsedMinutes(Long.MAX_VALUE, Long.MIN_VALUE) > 0)
    }

    @Test fun `latest today report wins and older report is fallback`() {
        val manual = ReportState(DailyReport(2, 3), 100, "today")
        val monitor = ReportState(DailyReport(5, 6), 200, "today", failed = true)
        assertEquals(monitor, latestDisplayReport(manual, monitor, "today"))
        assertEquals(manual, latestDisplayReport(manual, monitor.copy(date = "yesterday"), "today"))
        assertEquals(monitor.copy(date = "yesterday"), latestDisplayReport(null, monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(ReportState(failed = true), null, "today"))
    }
}
