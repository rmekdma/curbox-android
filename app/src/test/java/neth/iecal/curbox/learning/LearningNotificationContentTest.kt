package neth.iecal.curbox.learning

import neth.iecal.todo.*
import org.junit.Assert.*
import org.junit.Test

class LearningNotificationContentTest {
    @Test fun `notification values use only today's reports and retain zero goal semantics`() {
        fun value(report: ReportState?, goal: Int) = learningNotificationValue(report, goal, "20261108", "? / $goal", "check") { count, target, complete ->
            "$count/$target" + if (complete) " check" else ""
        }
        val today = ReportState(DailyReport(2, 4), 1, "20261108")
        assertEquals("2/5", value(today, 5))
        assertEquals("2/2 check", value(today, 2))
        assertEquals("2/2", value(today.copy(failed = true), 2))
        assertEquals("? / 5", value(today.copy(date = "20261107"), 5))
        assertEquals("? / 5", value(null, 5))
        assertEquals("check", value(null, 0))
    }

    @Test fun `known catalog subjects appear while monitor catalog is pending`() {
        val monitor = MonitorState(accountId = "A", registrations = mapOf("Child" to 1))
        val body = learningNotificationDetails(monitor, "Child", { it.name }, { "error" }, setOf(Subject.MATH), "pending") { "unknown" }
        assertEquals("MATH: unknown", body)
        assertEquals("pending", learningNotificationDetails(monitor, "Child", { it.name }, { "error" }, pending = "pending") { "unused" })
    }

    @Test fun `empty catalog shows its failure in notification body`() {
        val monitor = MonitorState(accountId = "A", registrations = mapOf("Child" to 1), catalogFailures = mapOf("Child" to LearningFailure.NETWORK))
        val body = learningNotificationDetails(monitor, "Child", { it.name }, { "network error" }) { "unused" }
        assertEquals("network error", body)
    }
}
