package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class ReportTest {
    @Test fun `count ignores legacy and malformed learning time`() {
        val json = """{"contentsHistory":{"daily":[{}]},"learningSecondsByCategory":{"study":"bad"}}"""
        assertEquals(DailyReport(1), TodoReportParser.parse(Subject.MATH, json))
        assertEquals(DailyReport(1), com.google.gson.Gson().fromJson("""{"count":1,"minutes":2,"seconds":120}""", DailyReport::class.java))
    }
    @Test fun `legacy saved document keeps goal report and monitor settings`() {
        val gson = com.google.gson.GsonBuilder().enableComplexMapKeySerialization().create()
        val key = "Child" to Subject.MATH
        val saved = StoredLearning(state = LearningState(accountId = "account", children = listOf(TodoChild("Child", mapOf(Subject.MATH to "id"))), reports = mapOf(key to ReportState(DailyReport(3), 123, "20261105")), goalsByAccount = mapOf("account" to mapOf(key to 5))), monitor = MonitorState(accountId = "account", registrations = mapOf("Child" to 1), intervalMinutes = "2"), sessionRef = "session")
        val legacy = gson.toJson(saved).replace("\"count\":3", "\"count\":3,\"minutes\":4,\"seconds\":240")
        val restored = gson.fromJson(legacy, StoredLearning::class.java)
        assertEquals(saved, restored)
    }
    @Test fun `zero goal clears queued retry but preserves failure latch`() {
        val key = "Child" to Subject.MATH
        val failure = ReportState(failed = true, failure = LearningFailure.NETWORK, initialFailure = true)
        val state = LearningState(accountId = "account", goalsByAccount = mapOf("account" to mapOf(key to 5)))
        val saved = StoredLearning(state = state, sessionRef = "session", monitor = MonitorState(accountId = "account", registrations = mapOf("Child" to 1), reports = mapOf(key to failure), reportRetries = mapOf(key to RetryIntent(1, 1, "session"))))
        val changed = saved.withManualState(state.copy(goalsByAccount = mapOf("account" to mapOf(key to 0))), "session")
        assertTrue(changed.monitor.reportRetries.isEmpty())
        assertEquals(failure, changed.monitor.reports[key])
    }
    @Test fun malformedReportIsNotZero() {
        assertThrows(Exception::class.java) { TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{}}""") }
    }
    @Test fun validEmptyActivityIsZero() {
        assertEquals(DailyReport(0), TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{"daily":[]}}"""))
    }
}
