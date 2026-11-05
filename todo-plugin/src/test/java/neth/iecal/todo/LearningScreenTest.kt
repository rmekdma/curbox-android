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

class LearningAuthenticationTest {
    @Test fun `authentication failure stops remaining reports and asks for login`() {
        val requested = mutableListOf<Subject>()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", linkedMapOf(Subject.MATH to "math", Subject.ENGLISH to "english")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(subject)
                throw TodoApiException(authenticationRequired = true)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("sample", "sample", "20261105")
        assertTrue(screen.state.loginRequired)
        assertEquals(listOf(Subject.MATH), requested)
        screen.refresh("20261105")
        assertEquals(listOf(Subject.MATH), requested)
    }
}

class LearningDateFailureTest {
    @Test fun `failed check on a new date preserves the last valid report and its date`() {
        val api = object : TodoApi {
            var fail = false
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "math")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (fail) throw TodoApiException()
                return DailyReport(2, 4)
            }
        }
        val screen = LearningScreen(api) { 123L }
        screen.signIn("sample", "sample", "20261105")
        api.fail = true
        screen.refresh("20261106")
        val report = screen.state.reports["Child" to Subject.MATH]!!
        assertEquals(DailyReport(2, 4), report.value)
        assertEquals("20261105", report.date)
        assertEquals(123L, report.checkedAt)
        assertTrue(report.failed)
    }
}

class LearningRestoreTest {
    @Test fun `reopened screen restores last report without making a request`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            var calls = 0
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession): List<TodoChild> { calls++; return listOf(TodoChild("Child", mapOf(Subject.MATH to "math"))) }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(2, 4)
        }
        val first = LearningScreen(api, store = store)
        first.signIn("sample", "sample", "20261105")
        val reopened = LearningScreen(api, store = store)
        reopened.restore()
        assertEquals(DailyReport(2, 4), reopened.state.reports["Child" to Subject.MATH]?.value)
        assertEquals(1, api.calls)
    }
}

class LearningStoreRaceTest {
    @Test fun `old request from another screen cannot replace newer saved report`() {
        val store = MemoryLearningStore()
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
        val first = LearningScreen(api, store)
        first.signIn("sample", "sample", "20261105")
        val second = LearningScreen(api, store)
        second.restore()
        val older = Thread { first.refresh("20261105") }
        older.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        second.refresh("20261105")
        release.countDown()
        older.join(5000)
        val reopened = LearningScreen(api, store)
        reopened.restore()
        assertEquals(DailyReport(3, 0), reopened.state.reports["Child" to Subject.MATH]?.value)
    }
}

class LearningRetryTest {
    @Test fun `retry checks only the selected subject after restore`() {
        val store = MemoryLearningStore()
        val requested = mutableListOf<Subject>()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", linkedMapOf(Subject.MATH to "math", Subject.ENGLISH to "english")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(subject)
                if (subject == Subject.MATH) throw TodoApiException()
                return DailyReport(1, 2)
            }
        }
        LearningScreen(api, store).signIn("sample", "sample", "20261105")
        requested.clear()
        val reopened = LearningScreen(api, store)
        reopened.restore()
        assertTrue(reopened.state.reports["Child" to Subject.MATH]!!.failed)
        assertTrue(requested.isEmpty())
        reopened.retryReport("Child", Subject.MATH, "20261105")
        assertEquals(listOf(Subject.MATH), requested)
        assertTrue(reopened.state.reports["Child" to Subject.MATH]!!.failed)
    }
}

class LearningFailureLatchTest {
    @Test fun `catalog retry does not restart a failed subject`() {
        val requested = mutableListOf<Subject>()
        val api = object : TodoApi {
            var failCatalog = false
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (failCatalog) throw TodoApiException()
                return listOf(TodoChild("Child", mapOf(Subject.MATH to "math")))
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(subject)
                throw TodoApiException()
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("sample", "sample", "20261105")
        api.failCatalog = true
        screen.refresh("20261105")
        api.failCatalog = false
        screen.refresh("20261105")
        assertEquals(listOf(Subject.MATH), requested)
        assertTrue(screen.state.reports["Child" to Subject.MATH]!!.failed)
    }
}

class LearningFailureReasonTest {
    @Test fun `report failure reason survives restoration`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "math")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport =
                throw TodoApiException(kind = LearningFailure.NETWORK)
        }
        LearningScreen(api, store).signIn("sample", "sample", "20261105")
        val restored = LearningScreen(api, store)
        restored.restore()
        assertEquals(LearningFailure.NETWORK, restored.state.reports["Child" to Subject.MATH]?.failure)
    }
}
