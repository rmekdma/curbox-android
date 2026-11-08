package neth.iecal.curbox.learning

import neth.iecal.todo.*
import org.junit.Assert.*
import org.junit.Test

class LearningNotificationContentTest {
    @Test fun `notification values use only today's reports and retain zero goal semantics`() {
        fun value(report: ReportState?, goal: Int) = learningNotificationValue(report, goal, "20261108", "unknown", "check") { count, target, complete ->
            "$count/$target" + if (complete) " check" else ""
        }
        val today = ReportState(DailyReport(2, 4), 1, "20261108")
        assertEquals("2/5", value(today, 5))
        assertEquals("2/2 check", value(today, 2))
        assertEquals("unknown", value(today.copy(date = "20261107"), 5))
        assertEquals("unknown", value(null, 5))
        assertEquals("check", value(null, 0))
    }

    @Test fun `empty catalog shows its failure in notification body`() {
        val monitor = MonitorState(accountId = "A", registrations = mapOf("Child" to 1), catalogFailures = mapOf("Child" to LearningFailure.NETWORK))
        val body = learningNotificationDetails(monitor, "Child", { it.name }, { "network error" }) { "unused" }
        assertEquals("network error", body)
    }
    @Test fun `only checks in completion values are marked for color`() {
        val monitor = MonitorState(children = listOf(TodoChild("Child", mapOf(Subject.MATH to "m", Subject.KOREAN to "k", Subject.ENGLISH to "e"))))
        val ranges = mutableListOf<IntRange>()
        val body = learningNotificationDetails(monitor, "Child", { if (it == Subject.MATH) "Math ✓" else it.name }, { "error ✓" }, { start, end -> ranges.add(start until end) }) {
            when (it) { Subject.MATH -> "5/5 ✓"; Subject.KOREAN -> "✓"; Subject.ENGLISH -> "unknown" }
        }
        assertEquals(2, ranges.size)
        assertEquals(listOf("✓", "✓"), ranges.map { body.substring(it.first, it.last + 1) })
        assertTrue(ranges.none { it.first == body.indexOf('✓') })
    }
}
