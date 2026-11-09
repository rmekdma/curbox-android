package neth.iecal.curbox.learning

import neth.iecal.todo.*
import org.junit.Assert.*
import org.junit.Test

class LearningNotificationContentTest {
    @Test fun `disabled or superseded settings do not alert`() {
        val original = MonitorState()
        assertTrue(allowsLearningAlert(original, original))
        assertFalse(allowsLearningAlert(original, original.copy(completionAlerts = false, alertSettingsRevision = 1)))
        assertFalse(allowsLearningAlert(original, original.copy(alertSettingsRevision = 2)))
        assertTrue(allowsLearningAlert(original.copy(alertSound = "silent"), original.copy(alertSound = "silent")))
    }
    private fun line(row: LearningNotificationRow, expanded: Boolean) = learningNotificationLine(row, expanded, "✅",
        { "? / $it" }, { count, goal -> "$count/$goal" }, { "$it min" })

    @Test fun `compact and expanded distinguish completion unknown and zero goal`() {
        val incomplete = LearningNotificationRow("수학", 5, ReportState(DailyReport(2, 4), 1, "today"))
        val complete = LearningNotificationRow("수학", 5, ReportState(DailyReport(5, 4), 1, "today"))
        val failed = complete.copy(report = complete.report!!.copy(failed = true), errors = listOf("error"))
        val unknown = LearningNotificationRow("수학", 5, null)
        val zero = LearningNotificationRow("수학", 0, null)
        assertEquals("수학 2/5", line(incomplete, false))
        assertEquals("수학 2/5 4 min", line(incomplete, true))
        assertEquals("수학 ✅", line(complete, false))
        assertEquals("수학 ✅ 5/5 4 min", line(complete, true))
        assertEquals("수학 5/5 4 min error", line(failed, true))
        assertEquals("수학 ? / 5", line(unknown, false))
        assertEquals("수학 ✅", line(zero, true))
        assertEquals("수학 ✅ 4 min", line(zero.copy(report = complete.report), true))
    }
}
