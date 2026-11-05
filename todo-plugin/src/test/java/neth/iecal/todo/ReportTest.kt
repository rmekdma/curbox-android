package neth.iecal.todo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTest {
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

