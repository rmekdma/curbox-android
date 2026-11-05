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

class LearningScreenRaceTest {
    @Test fun `older report cannot replace a newer manual check`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            var calls = 0
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "math")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                val call = synchronized(this) { ++calls }
                if (call == 2) { started.countDown(); release.await() }
                return DailyReport(call, 0)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("sample", "sample", "20261105")
        val older = Thread { screen.refresh("20261105") }
        older.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        screen.refresh("20261105")
        release.countDown()
        older.join(5000)
        assertEquals(DailyReport(3, 0), screen.state.reports["Child" to Subject.MATH]?.value)
    }
}
