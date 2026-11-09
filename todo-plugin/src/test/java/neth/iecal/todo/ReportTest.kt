package neth.iecal.todo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTest {
    @Test fun `seconds preserve fractional categories and exclude etc`() {
        val report = TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{"daily":[{}]},"learningSecondsByCategory":{"study":20.25,"review":"19.75","etc":900}}""")
        assertEquals(DailyReport(1, 1, 40.0), report)
        val legacy = com.google.gson.Gson().fromJson("""{"count":1,"minutes":2}""", DailyReport::class.java)
        assertEquals(null, legacy.seconds)
    }

    @Test fun malformedReportIsNotZero() {
        org.junit.Assert.assertThrows(Exception::class.java) {
            TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{},"learningSecondsByCategory":{}}""")
        }
        org.junit.Assert.assertThrows(Exception::class.java) {
            TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{"daily":[]},"learningSecondsByCategory":{"study":"bad"}}""")
        }
    }
    @Test fun validEmptyActivityIsZero() {
        val report = TodoReportParser.parse(Subject.MATH, """{"contentsHistory":{"daily":[]},"learningSecondsByCategory":{}}""")
        assertEquals(0, report.count)
        assertEquals(0, report.minutes)
    }
}

