package neth.iecal.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TodoAwardLookupTest {
    @Test fun `fractional seconds are added before rounding`() {
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "m", Subject.ENGLISH to "e")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) =
                DailyReport(0, mapOf("course" to java.math.BigDecimal("14.99")))
        }
        assertEquals(0L, TodoAwardLookup(api).minutes(TodoSession("a", "token"), "Child", "20261105"))
    }

    @Test fun `missing duration fails instead of returning zero`() {
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "m")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        assertThrows(TodoApiException::class.java) {
            TodoAwardLookup(api).minutes(TodoSession("a", "token"), "Child", "20261105")
        }
    }

    @Test fun `sums all subscribed subjects before rounding and excludes etc`() {
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = error("unused")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "m", Subject.ENGLISH to "e")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                assertEquals("20261105", date)
                return DailyReport(0, if (subject == Subject.MATH) mapOf("study" to java.math.BigDecimal("15"), "etc" to java.math.BigDecimal("500")) else mapOf("study" to java.math.BigDecimal("15")))
            }
        }
        assertEquals(1L, TodoAwardLookup(api).minutes(TodoSession("a", "token"), "Child", "20261105"))
    }
}



