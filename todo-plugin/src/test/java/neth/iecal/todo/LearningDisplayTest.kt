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
        val old = ReportState(DailyReport(1, 1, 10.0), 1, "today")
        val newer = ReportState(DailyReport(5, 1, 20.0), 2, "today")
        val state = LearningState(accountId = "A", reports = mapOf(key to old))
        val monitor = MonitorState(accountId = "A", reports = mapOf(key to newer))
        val subjects = setOf(Subject.MATH)
        assertEquals(newer, learningDisplayReports(state, monitor, "Child", subjects, "today")[Subject.MATH])
        assertEquals(old, learningDisplayReports(state, monitor.copy(accountId = "B"), "Child", subjects, "today")[Subject.MATH])
        assertNull(learningDisplayReports(state, monitor, "Child", subjects, "tomorrow")[Subject.MATH])
        assertEquals(1, totalLearningMinutes(learningDisplayReports(state, monitor, "Child", subjects, "today").values))
    }

    @Test fun `total rounds combined seconds once and requires every report`() {
        val first = ReportState(DailyReport(1, 1, 20.0), 1, "today")
        val second = ReportState(DailyReport(2, 1, 20.0), 2, "today", failed = true)
        assertEquals(1, totalLearningMinutes(listOf(first, second)))
        assertEquals(0, totalLearningMinutes(listOf(first.copy(value = DailyReport(0, 0, 0.0)))))
        assertNull(totalLearningMinutes(emptyList()))
        assertNull(totalLearningMinutes(listOf(first, null)))
        assertNull(totalLearningMinutes(listOf(first.copy(value = DailyReport(1, 1)))))
        assertNull(totalLearningMinutes(listOf(first.copy(value = DailyReport(1, 1, Double.NaN)))))
        assertNull(totalLearningMinutes(listOf(first.copy(value = DailyReport(1, 1, -1.0)))))
        assertNull(totalLearningMinutes(listOf(first.copy(value = DailyReport(1, 1, Int.MAX_VALUE.toDouble() * 60)), second)))
    }

    @Test fun `elapsed time clamps future and handles hour boundaries`() {
        val now = 10_000_000L
        assertEquals(0L, elapsedMinutes(now, now + 1))
        assertEquals(0L, elapsedMinutes(now, now - 59_999))
        assertEquals(59L, elapsedMinutes(now, now - 59 * 60_000))
        assertEquals(60L, elapsedMinutes(now, now - 60 * 60_000))
        assertEquals(83L, elapsedMinutes(now, now - 83 * 60_000))
        assertTrue(elapsedMinutes(Long.MAX_VALUE, Long.MIN_VALUE) > 0)
    }

    @Test fun `latest today report wins and older reports are not displayed`() {
        val manual = ReportState(DailyReport(2, 3), 100, "today")
        val monitor = ReportState(DailyReport(5, 6), 200, "today", failed = true)
        assertEquals(monitor, latestDisplayReport(manual, monitor, "today"))
        assertEquals(manual, latestDisplayReport(manual, monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(null, monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(manual.copy(date = "yesterday"), monitor.copy(date = "yesterday"), "today"))
        assertNull(latestDisplayReport(ReportState(failed = true), null, "today"))
    }
}
