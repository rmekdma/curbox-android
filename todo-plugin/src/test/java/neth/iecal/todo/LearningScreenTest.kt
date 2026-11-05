package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class LearningScreenTest {
    @Test fun `failed retry keeps last valid report and requires explicit retry`() {
        val api = object : TodoApi {
            var reports = 0
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "math")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                reports++
                if (reports > 1) throw TodoApiException()
                return DailyReport(2, 4)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("example", "example", "20261105")
        screen.refresh("20261105")
        val state = screen.state
        assertEquals(DailyReport(2, 4), state.reports["Child" to Subject.MATH]?.value)
        assertTrue(state.reports["Child" to Subject.MATH]?.failed == true)
        assertEquals(2, api.reports)
    }
}
