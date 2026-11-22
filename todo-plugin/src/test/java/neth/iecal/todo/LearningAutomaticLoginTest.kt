package neth.iecal.todo

import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class LearningAutomaticLoginTest {
    @Test fun `rejected dashboard catalog signs in again and repeats the lookup once`() {
        val store = MemoryLearningStore()
        val tokens = mutableListOf<String>()
        var signIns = 0
        var rejectOld = false
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                assertEquals("person@example.test", email)
                assertEquals("secret", password)
                signIns++
                return TodoSession("account", "token$signIns")
            }
            override fun catalog(session: TodoSession): List<TodoChild> {
                tokens.add(session.token)
                if (rejectOld && session.token == "token1") throw TodoApiException(authenticationRequired = true)
                return emptyList()
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("person@example.test", "secret", "20261105")
        val ref = requireNotNull(store.read().sessionRef)
        assertEquals("secret", store.readSession(ref).password)
        tokens.clear()
        rejectOld = true
        screen.refresh("20261105")
        assertEquals(2, signIns)
        assertEquals(listOf("token1", "token2"), tokens)
        assertTrue(screen.signedIn)
        assertEquals("token2", store.readSession(ref).token)
    }
}

class LearningAutomaticLoginFailureTest {
    @Test fun `rejected automatic login stays disabled after restore and retains password`() {
        val store = MemoryLearningStore()
        var signIns = 0
        var reject = false
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                signIns++
                if (reject) throw TodoApiException(authenticationRequired = true)
                return TodoSession("account", "token")
            }
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (reject) throw TodoApiException(authenticationRequired = true)
                return emptyList()
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("person@example.test", "secret", "20261105")
        val ref = requireNotNull(store.read().sessionRef)
        reject = true
        screen.refresh("20261105")
        val restored = testLearningScreen(api, store)
        restored.restore()
        restored.refresh("20261105", includeFailed = true)
        assertEquals(2, signIns)
        assertEquals("secret", store.readSession(ref).password)
        assertEquals(LearningFailure.AUTHENTICATION, store.read().authFailure)
        assertTrue(restored.loginRequired)
        restored.signIn("person@example.test", "wrong", "20261105")
        assertEquals(3, signIns)
        assertEquals("secret", store.readSession(ref).password)
        val reopened = testLearningScreen(api, store)
        reopened.restore()
        reopened.refresh("20261105", includeFailed = true)
        assertEquals(3, signIns)
        assertTrue(reopened.loginRequired)
    }

    @Test fun `rejected repeated lookup does not trigger another automatic login`() {
        val store = MemoryLearningStore()
        var signIns = 0
        var reject = false
        var catalogs = 0
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token${++signIns}")
            override fun catalog(session: TodoSession): List<TodoChild> {
                catalogs++
                if (reject) throw TodoApiException(authenticationRequired = true)
                return emptyList()
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("example", "secret", "20261105")
        reject = true
        screen.refresh("20261105")
        val restored = testLearningScreen(api, store)
        restored.restore()
        restored.refresh("20261105", includeFailed = true)
        assertEquals(2, signIns)
        assertEquals(3, catalogs)
        assertEquals(LearningFailure.AUTHENTICATION, store.read().authFailure)
    }

    @Test fun `logout fences a pending automatic login and preserves account data`() {
        val store = MemoryLearningStore()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var signIns = 0
        var reject = false
        val child = TodoChild("Child", mapOf(Subject.MATH to "math"))
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                if (++signIns == 2) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return TodoSession("account", "token$signIns")
            }
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (reject) throw TodoApiException(authenticationRequired = true)
                return listOf(child)
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(1)
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("example", "secret", "20261105")
        screen.toggleMonitor("Child")
        assertTrue(screen.saveGoal("Child", Subject.MATH, "7"))
        val ref = requireNotNull(store.read().sessionRef)
        reject = true
        val request = Thread { screen.refresh("20261105") }
        request.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            screen.signOut()
        } finally {
            release.countDown()
            request.join(5000)
        }
        assertFalse(request.isAlive)
        assertNull(store.read().sessionRef)
        assertTrue(runCatching { store.readSession(ref) }.isFailure)
        assertTrue("Child" in store.read().monitor.registeredChildren())
        assertEquals(7, store.read().state.goalsByAccount["account"]?.get("Child" to Subject.MATH))
    }
}

class LearningMonitorAutomaticLoginTest {
    @Test fun `monitor retries rejected report with renewed session`() = kotlinx.coroutines.runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Child", mapOf(Subject.MATH to "math"))
        val signIns = java.util.concurrent.atomic.AtomicInteger()
        val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
        val rejectOld = java.util.concurrent.atomic.AtomicBoolean(false)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = TodoSession("account", "token${signIns.incrementAndGet()}")
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                reports.add(session.token)
                if (rejectOld.get() && session.token == "token1") throw TodoApiException(authenticationRequired = true)
                return DailyReport(2)
            }
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("example", "secret", "20261105")
        screen.toggleMonitor("Child")
        reports.clear()
        rejectOld.set(true)
        val host = object : neth.iecal.plugin.CurboxHost {
            override val observations = kotlinx.coroutines.flow.MutableStateFlow(neth.iecal.plugin.HostObservation(connection = neth.iecal.plugin.Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch {
            InitialMonitor(api, store, kotlinx.coroutines.flow.MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host)
        }
        try {
            kotlinx.coroutines.withTimeout(5000) {
                while (reports.size < 2) kotlinx.coroutines.delay(10)
            }
            assertEquals(2, signIns.get())
            assertEquals(listOf("token1", "token2"), reports.toList())
        } finally {
            job.cancel()
            job.join()
        }
    }
}

class LearningAutomaticLoginTransientTest {
    @Test fun `temporary login failure permits another attempt after cooldown`() {
        val store = MemoryLearningStore()
        var signIns = 0
        var rejectOld = false
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                signIns++
                if (signIns == 2) throw TodoApiException(kind = LearningFailure.NETWORK)
                return TodoSession("account", "token$signIns")
            }
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (rejectOld && session.token == "token1") throw TodoApiException(authenticationRequired = true)
                return emptyList()
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        val screen = testLearningScreen(api, store)
        screen.signIn("example", "secret", "20261105")
        rejectOld = true
        screen.refresh("20261105")
        assertEquals(LearningFailure.NETWORK, store.read().authFailure)
        screen.refresh("20261105")
        assertEquals(2, signIns)
        store.authTransaction { it.copy(authRetryAfter = 0) to Unit }
        screen.refresh("20261105")
        assertEquals(3, signIns)
        assertNull(store.read().authFailure)
        assertTrue(screen.signedIn)
    }
}

class LearningMonitorTransientRecoveryTest {
    @Test fun `regular report retries after temporary automatic login failure`() = kotlinx.coroutines.runBlocking {
        val store = MemoryLearningStore()
        val wall = java.util.concurrent.atomic.AtomicLong(java.time.Instant.parse("2026-11-05T03:00:00Z").toEpochMilli())
        val elapsed = java.util.concurrent.atomic.AtomicLong(1000)
        val signIns = java.util.concurrent.atomic.AtomicInteger()
        val reports = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reject = java.util.concurrent.atomic.AtomicBoolean(false)
        val child = TodoChild("Child", mapOf(Subject.MATH to "math"))
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                val call = signIns.incrementAndGet()
                if (call == 2) throw TodoApiException(kind = LearningFailure.NETWORK)
                return TodoSession("account", "token$call")
            }
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                reports.add(session.token)
                if (reject.get() && session.token == "token1") throw TodoApiException(authenticationRequired = true)
                return DailyReport(if (session.token == "token1") 1 else 2)
            }
        }
        val screen = testLearningScreen(api, store, now = wall::get)
        screen.signIn("example", "secret", "20261105")
        screen.toggleMonitor("Child")
        val host = object : neth.iecal.plugin.CurboxHost {
            override val observations = kotlinx.coroutines.flow.MutableStateFlow(neth.iecal.plugin.HostObservation(connection = neth.iecal.plugin.Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, kotlinx.coroutines.flow.MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, now = wall::get, elapsed = elapsed::get).run(host) }
        try {
            kotlinx.coroutines.withTimeout(5000) {
                while (store.read().monitor.reports["Child" to Subject.MATH]?.value?.count != 1) kotlinx.coroutines.delay(10)
            }
            reports.clear()
            reject.set(true)
            elapsed.set(61_000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            kotlinx.coroutines.withTimeout(5000) {
                while (store.read().authFailure != LearningFailure.NETWORK) kotlinx.coroutines.delay(10)
            }
            wall.addAndGet(60_001)
            elapsed.set(121_001)
            host.observations.value = host.observations.value.copy(callbackReceived = true)
            kotlinx.coroutines.withTimeout(5000) {
                while (store.read().monitor.reports["Child" to Subject.MATH]?.value?.count != 2) kotlinx.coroutines.delay(10)
            }
            assertEquals(3, signIns.get())
            assertEquals(listOf("token1", "token1", "token3"), reports.toList())
            assertNull(store.read().authFailure)
        } finally {
            job.cancel()
            job.join()
        }
    }
}

class LearningConcurrentAutomaticLoginTest {
    @Test fun `two screens share one automatic login for the same expired session`() {
        val store = MemoryLearningStore()
        val signIns = java.util.concurrent.atomic.AtomicInteger()
        val rejected = java.util.concurrent.CountDownLatch(2)
        val loginStarted = java.util.concurrent.CountDownLatch(1)
        val releaseLogin = java.util.concurrent.CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val renewedCalls = java.util.concurrent.atomic.AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String): TodoSession {
                val call = signIns.incrementAndGet()
                if (call == 2) {
                    loginStarted.countDown()
                    check(releaseLogin.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return TodoSession("account", "token$call")
            }
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (expired.get() && session.token == "token1") {
                    rejected.countDown()
                    throw TodoApiException(authenticationRequired = true)
                }
                if (session.token == "token2") renewedCalls.incrementAndGet()
                return emptyList()
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(0)
        }
        val first = testLearningScreen(api, store)
        first.signIn("example", "secret", "20261105")
        val second = testLearningScreen(api, store)
        second.restore()
        expired.set(true)
        val a = Thread { first.refresh("20261105") }
        val b = Thread { second.refresh("20261105") }
        a.start()
        b.start()
        try {
            assertTrue(loginStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(rejected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            releaseLogin.countDown()
            a.join(5000)
            b.join(5000)
        }
        assertFalse(a.isAlive)
        assertFalse(b.isAlive)
        assertEquals(2, signIns.get())
        assertEquals(2, renewedCalls.get())
        assertEquals("token2", store.readSession(requireNotNull(store.read().sessionRef)).token)
    }
}
