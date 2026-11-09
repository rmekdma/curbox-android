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
    private fun line(row: LearningNotificationRow) = learningNotificationLine(row, "✓",
        { "? / $it" }, { count, goal -> "$count/$goal" })

    @Test fun `summary distinguishes completion unknown and zero goal`() {
        val incomplete = LearningNotificationRow("수학", 5, ReportState(DailyReport(2), 1, "today"))
        val complete = LearningNotificationRow("수학", 5, ReportState(DailyReport(5), 1, "today"))
        assertEquals("수학 2/5", line(incomplete))
        assertEquals("수학 ✓", line(complete))
        assertEquals("수학 5/5", line(complete.copy(report = complete.report!!.copy(failed = true))))
        assertEquals("수학 ? / 5", line(LearningNotificationRow("수학", 5, null)))
        assertEquals("수학 ✓", line(LearningNotificationRow("수학", 0, null)))
    }
}
