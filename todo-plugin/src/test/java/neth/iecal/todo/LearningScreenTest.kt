package neth.iecal.todo

import org.junit.Assert.*
import org.junit.Test

class LearningScreenTest {
    @Test fun `polling interval accepts positive minutes of any size`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = emptyList<TodoChild>()
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0, 0)
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        assertEquals("1", screen.monitor.intervalMinutes)
        assertFalse(screen.saveInterval("0"))
        assertFalse(screen.saveInterval("-2"))
        assertTrue(screen.saveInterval("999999999999999999999999"))
        assertEquals("999999999999999999999999", store.read().monitor.intervalMinutes)
    }

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

class LearningAccountIsolationTest {
    @Test fun `switching accounts with a failed catalog does not show the previous account`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession(email, "token")
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (session.accountId == "B") throw TodoApiException()
                return listOf(TodoChild("Child", mapOf(Subject.MATH to "A-id")))
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(2, 4)
        }
        val screen = LearningScreen(api, store) { 123L }
        screen.signIn("A", "password", "20261105")
        screen.signIn("B", "password", "20261105")
        assertTrue(screen.state.error)
        assertTrue(screen.state.children.isEmpty())
        assertTrue(screen.state.reports.isEmpty())
        assertNull(screen.state.catalogCheckedAt)
        val restored = LearningScreen(api, store)
        restored.restore()
        assertEquals(screen.state, restored.state)
    }
}

class LearningAccountScopeTest {
    @Test fun `same child name on another account never reuses prior report or identifier`() {
        val store = MemoryLearningStore()
        val ids = mutableListOf<String>()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession(email, "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "${session.accountId}-id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                ids.add(userId)
                if (session.accountId == "B") throw TodoApiException()
                return DailyReport(2, 4)
            }
        }
        val screen = LearningScreen(api, store)
        screen.signIn("A", "password", "20261105")
        screen.signIn("B", "password", "20261105")
        assertNull(screen.state.reports["Child" to Subject.MATH]?.value)
        val restored = LearningScreen(api, store)
        restored.restore()
        restored.retryReport("Child", Subject.MATH, "20261105")
        assertEquals(listOf("A-id", "B-id", "B-id"), ids)
        assertNull(restored.state.reports["Child" to Subject.MATH]?.value)
    }

    @Test fun `same account reauthentication retains own last value after authorization failure`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            var fail = false
            override fun signIn(email: String, password: String) = TodoSession("A", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (fail) throw TodoApiException(authenticationRequired = true)
                return DailyReport(2, 4)
            }
        }
        val screen = LearningScreen(api, store)
        screen.signIn("A", "password", "20261105")
        api.fail = true
        screen.refresh("20261105")
        val restored = LearningScreen(api, store)
        restored.restore()
        restored.signIn("A", "password", "20261105")
        assertEquals(DailyReport(2, 4), restored.state.reports["Child" to Subject.MATH]?.value)
        assertTrue(restored.state.loginRequired)
    }

    @Test fun `unknown saved scope never restores or carries data through failed login`() {
        val store = MemoryLearningStore()
        val claim = store.claim()
        store.commit(claim.revision, LearningState(children = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))), null)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession = throw TodoApiException(authenticationRequired = true)
            override fun catalog(session: TodoSession) = emptyList<TodoChild>()
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0, 0)
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        assertTrue(screen.state.children.isEmpty())
        screen.signIn("B", "password", "20261105")
        assertTrue(screen.state.children.isEmpty())
    }

    @Test fun `delayed old account report cannot replace new account data`() {
        val store = MemoryLearningStore()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession(email, "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to session.accountId)))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (session.accountId == "A") { started.countDown(); release.await() }
                return if (session.accountId == "A") DailyReport(1, 0) else DailyReport(2, 0)
            }
        }
        val screen = LearningScreen(api, store)
        val old = Thread { screen.signIn("A", "password", "20261105") }
        old.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        screen.signIn("B", "password", "20261105")
        release.countDown()
        old.join(5000)
        val restored = LearningScreen(api, store)
        restored.restore()
        assertEquals(DailyReport(2, 0), restored.state.reports["Child" to Subject.MATH]?.value)
    }
}

class LearningGoalTest {
    @Test fun `goal persists and zero completes without a report`() {
        val store = MemoryLearningStore()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport = throw TodoApiException()
        }
        val screen = LearningScreen(api, store)
        screen.signIn("sample", "sample", "20261105")
        assertTrue(screen.saveGoal("Child", Subject.MATH, "0"))
        val reopened = LearningScreen(api, store)
        reopened.restore()
        assertEquals(0, reopened.goal("Child", Subject.MATH))
        assertEquals(GoalStatus.COMPLETE, reopened.goalStatus("Child", Subject.MATH, "20261105"))
        assertNull(reopened.state.reports["Child" to Subject.MATH]?.value)
    }
}

class LearningHistoryGoalTest {
    private fun api() = object : TodoApi {
        override fun signIn(email: String, password: String) = TodoSession(email, "token")
        override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
        override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(if (date == "20261104") 4 else 7, 1)
    }

    @Test fun `selected date uses its own report and goal changes recalculate`() {
        val screen = LearningScreen(api())
        screen.signIn("A", "sample", "20261105")
        assertEquals(GoalStatus.COMPLETE, screen.goalStatus("Child", Subject.MATH, "20261105"))
        screen.selectDate("20261104")
        assertEquals(GoalStatus.UNKNOWN, screen.goalStatus("Child", Subject.MATH, "20261104"))
        screen.refresh("20261104", onlyIfSelected = true)
        assertEquals(GoalStatus.INCOMPLETE, screen.goalStatus("Child", Subject.MATH, "20261104"))
        assertTrue(screen.saveGoal("Child", Subject.MATH, " 4 "))
        assertEquals(GoalStatus.COMPLETE, screen.goalStatus("Child", Subject.MATH, "20261104"))
        assertFalse(screen.saveGoal("Child", Subject.MATH, "100"))
        assertFalse(screen.saveGoal("Child", Subject.MATH, "-1"))
    }

    @Test fun `new account does not inherit goals`() {
        val screen = LearningScreen(api())
        screen.signIn("A", "sample", "20261105")
        assertTrue(screen.saveGoal("Child", Subject.MATH, "9"))
        screen.signIn("B", "sample", "20261105")
        assertEquals(5, screen.goal("Child", Subject.MATH))
        screen.signIn("A", "sample", "20261105")
        assertEquals(9, screen.goal("Child", Subject.MATH))
    }

    @Test fun `late past date cannot replace current date`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("A", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (date == "20261104") { started.countDown(); release.await() }
                return DailyReport(if (date == "20261104") 1 else 7, 0)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("A", "sample", "20261105")
        screen.selectDate("20261104")
        val old = Thread { screen.refresh("20261104", onlyIfSelected = true) }
        old.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        screen.selectDate("20261105")
        screen.refresh("20261105", onlyIfSelected = true)
        release.countDown()
        old.join(5000)
        assertEquals(DailyReport(7, 0), screen.state.reports["Child" to Subject.MATH]?.value)
    }

    @Test fun `zero goal keeps last count and failure latch without retry`() {
        var calls = 0
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("A", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                calls++
                if (calls > 1) throw TodoApiException()
                return DailyReport(2, 1)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("A", "sample", "20261105")
        screen.refresh("20261105")
        screen.saveGoal("Child", Subject.MATH, "0")
        screen.retryReport("Child", Subject.MATH, "20261105")
        assertEquals(2, calls)
        assertEquals(DailyReport(2, 1), screen.state.reports["Child" to Subject.MATH]?.value)
        assertTrue(screen.state.reports["Child" to Subject.MATH]!!.failed)
        screen.saveGoal("Child", Subject.MATH, "5")
        screen.refresh("20261104")
        assertEquals(2, calls)
    }
}

class LearningGoalRaceTest {
    @Test fun `goal change fences an in flight report`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            var calls = 0
            override fun signIn(email: String, password: String) = TodoSession("A", "token")
            override fun catalog(session: TodoSession) = listOf(TodoChild("Child", mapOf(Subject.MATH to "id")))
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                calls++
                if (calls == 2) { started.countDown(); release.await() }
                return DailyReport(calls, 0)
            }
        }
        val screen = LearningScreen(api)
        screen.signIn("A", "sample", "20261105")
        val old = Thread { screen.refresh("20261105") }
        old.start()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(screen.saveGoal("Child", Subject.MATH, "0"))
        release.countDown()
        old.join(5000)
        assertEquals(DailyReport(1, 0), screen.state.reports["Child" to Subject.MATH]?.value)
        assertEquals(GoalStatus.COMPLETE, screen.goalStatus("Child", Subject.MATH, "20261105"))
    }
}
