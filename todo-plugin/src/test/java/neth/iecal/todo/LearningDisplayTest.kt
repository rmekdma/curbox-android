package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class LearningDisplayTest {
    @Test fun `sound list centers selection and clamps both ends`() {
        assertEquals(0, soundListScroll(0, 10, 48, 240))
        assertEquals(144, soundListScroll(5, 10, 48, 240))
        assertEquals(240, soundListScroll(9, 10, 48, 240))
        assertEquals(0, soundListScroll(1, 2, 48, 240))
    }
    @Test fun `latest successful catalog wins without reviving removed subjects`() {
        val math = TodoChild("Child", mapOf(Subject.MATH to "m"))
        val english = TodoChild("Child", mapOf(Subject.ENGLISH to "e"))
        val state = LearningState(accountId = "A", children = listOf(math), catalogCheckedAt = 500)
        val monitor = MonitorState(accountId = "A", children = listOf(english), manualCatalogOrder = 1, monitorCatalogOrder = 2, catalogCheckedAt = 100)
        assertEquals(setOf(Subject.ENGLISH), learningSubjects(state, monitor, "Child").keys)
        assertEquals(setOf(Subject.MATH), learningSubjects(state, monitor.copy(manualCatalogOrder = 3), "Child").keys)
        assertEquals(setOf(Subject.MATH), learningSubjects(state, monitor.copy(accountId = "B"), "Child").keys)
        assertEquals(setOf(Subject.MATH), learningSubjects(state, monitor.copy(monitorCatalogOrder = null, manualCatalogOrder = null), "Child").keys)
    }

    @Test fun `projection selects one current report per subject and fences other accounts`() {
        val key = "Child" to Subject.MATH
        val old = ReportState(DailyReport(1), 1, "today")
        val newer = ReportState(DailyReport(5), 2, "today")
        val state = LearningState(accountId = "A", reports = mapOf(key to old))
        val monitor = MonitorState(accountId = "A", reports = mapOf(key to newer))
        val subjects = setOf(Subject.MATH)
        assertEquals(newer, learningDisplayReports(state, monitor, "Child", subjects, "today")[Subject.MATH])
        assertEquals(old, learningDisplayReports(state, monitor.copy(accountId = "B"), "Child", subjects, "today")[Subject.MATH])
        assertNull(learningDisplayReports(state, monitor, "Child", subjects, "tomorrow")[Subject.MATH])
    }

    @Test fun `latest today report wins and older reports are not displayed`() {
        val manual = ReportState(DailyReport(2), 100, "today")
        val monitor = ReportState(DailyReport(5), 200, "today", failed = true)
        assertEquals(monitor, latestDisplayReport(manual, monitor, "today"))
        assertEquals(manual, latestDisplayReport(manual, monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(null, monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(manual.copy(date = "yesterday"), monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(ReportState(failed = true), null, "today"))
    }
}
