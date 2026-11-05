package neth.iecal.todo

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neth.iecal.plugin.Connection
import neth.iecal.plugin.CurboxHost
import neth.iecal.plugin.HostObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InitialMonitorTest {
    @Test fun transientRegularFailureKeepsBaselineAndRetriesAtNextInterval() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport = when (calls.incrementAndGet()) {
                1 -> DailyReport(1, 1)
                2 -> throw TodoApiException(kind = LearningFailure.NETWORK)
                else -> DailyReport(3, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 1 } }
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            val failure = withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.failed == true } }
            assertEquals(1, failure.monitor.reports["Sample" to Subject.MATH]?.value?.count)
            clock.set(121000)
            host.observations.value = host.observations.value.copy(callbackReceived = true)
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 3 } }
            assertEquals(3, calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun slowRegularRequestDoesNotOverlapAnotherCycle() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val calls = AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (calls.incrementAndGet() == 2) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                return DailyReport(2, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value != null } }
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { while (entered.count != 0L) delay(10) }
            clock.set(181000)
            host.observations.value = host.observations.value.copy(callbackReceived = true)
            delay(150)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test fun newLoginRetriesNetworkFailureWithoutForeground() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("first", session)
        store.writeSession("second", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "first"))
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (calls.incrementAndGet() == 1) throw TodoApiException(kind = LearningFailure.NETWORK)
                return DailyReport(2, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.failed == true } }
        val login = store.claim()
        assertTrue(store.commit(login.revision, login.state, "second"))
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(2, calls.get())
        job.cancelAndJoin()
    }

    @Test fun regularChecksWaitForIntervalAndFreshMatchingForeground() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                calls.incrementAndGet()
                return DailyReport(2, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            var fresh = false
            override suspend fun refreshObservation() = if (fresh) observations.value else observations.value.copy(foreground = neth.iecal.plugin.Foreground.Unknown)
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value != null } }
        host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
        delay(100)
        assertEquals(1, calls.get())
        clock.set(61000)
        host.observations.value = host.observations.value.copy(callbackReceived = true)
        delay(200)
        assertEquals(1, calls.get())
        host.fresh = true
        host.observations.value = host.observations.value.copy(observedAtMillis = 1)
        withTimeout(5000) { while (calls.get() < 2) delay(10) }
        job.cancelAndJoin()
    }

    @Test fun registrationInitializesAllPositiveSubjectsAndFailureRequiresExplicitRetry() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.KOREAN to "k", Subject.MATH to "m", Subject.ENGLISH to "e"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account",
            goalsByAccount = mapOf("account" to mapOf(("Sample" to Subject.ENGLISH) to 0))), "session"))
        val mathCalls = AtomicInteger()
        val englishCalls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport = when (subject) {
                Subject.MATH -> if (mathCalls.incrementAndGet() == 1) throw TodoApiException(kind = LearningFailure.NETWORK) else DailyReport(5, 4)
                Subject.ENGLISH -> { englishCalls.incrementAndGet(); DailyReport(0, 0) }
                Subject.KOREAN -> DailyReport(6, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.failed == true } }
        assertEquals(1, mathCalls.get())
        host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
        assertEquals(0, englishCalls.get())
        assertEquals(6, store.read().monitor.reports["Sample" to Subject.KOREAN]?.value?.count)
        screen.toggleMonitor("Sample")
        screen.toggleMonitor("Sample")
        delay(100)
        assertEquals(1, mathCalls.get())
        val manual = store.claim()
        assertTrue(store.commit(manual.revision, manual.state.copy(error = true), manual.sessionRef))
        delay(100)
        assertEquals(1, mathCalls.get())
        screen.retryMonitor("Sample", Subject.MATH)
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 5 } }
        assertEquals(2, mathCalls.get())
        assertEquals(0, englishCalls.get())
        job.cancelAndJoin()
    }

    @Test fun manualCommitPreservesConcurrentMonitorState() {
        val store = MemoryLearningStore()
        val manual = store.claim()
        store.updateMonitor { it.monitor.copy(ownerEpoch = 8, childName = "Sample") }
        assertTrue(store.commit(manual.revision, manual.state.copy(error = true), null))
        assertEquals(8, store.read().monitor.ownerEpoch)
        assertEquals("Sample", store.read().monitor.childName)
    }
    @Test fun deregistrationDiscardsAnInFlightReport() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return DailyReport(5, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        screen.toggleMonitor("Sample")
        release.countDown()
        delay(100)
        assertEquals(null, store.read().monitor.reports["Sample" to Subject.MATH])
        job.cancelAndJoin()
    }

    @Test fun permissionGenerationInvalidatesAnInFlightReport() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return DailyReport(5, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val permission = MutableStateFlow(NotificationPermission(true))
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, permission, { permission.value.granted }, { _, _ -> }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        permission.value = NotificationPermission(false, 1)
        release.countDown()
        delay(100)
        assertEquals(null, store.read().monitor.reports["Sample" to Subject.MATH])
        job.cancelAndJoin()
    }

    @Test fun goalChangedAwayAndBackStillRejectsTheOldResponse() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return DailyReport(99, 4)
                }
                return DailyReport(2, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val published = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { doc, _ ->
            doc.monitor.reports["Sample" to Subject.MATH]?.value?.count?.let(published::add)
        }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        assertTrue(screen.saveGoal("Sample", Subject.MATH, "7"))
        assertTrue(screen.saveGoal("Sample", Subject.MATH, "5"))
        release.countDown()
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
        assertTrue(99 !in published)
        job.cancelAndJoin()
    }

    @Test fun catalogFailurePreservesTheCatalogAndRequiresExplicitRetry() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val catalogCalls = AtomicInteger()
        val reportCalls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (catalogCalls.incrementAndGet() == 1) throw TodoApiException(kind = LearningFailure.NETWORK)
                return listOf(child)
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                reportCalls.incrementAndGet()
                return DailyReport(2, 4)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.catalogFailure == LearningFailure.NETWORK } }
        assertEquals(listOf(child), store.read().state.children)
        assertEquals(listOf(child), store.read().monitor.children)
        val manual = store.claim()
        assertTrue(store.commit(manual.revision, manual.state, manual.sessionRef))
        delay(100)
        assertEquals(1, catalogCalls.get())
        assertEquals(0, reportCalls.get())
        screen.retryMonitor("Sample", null)
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(2, catalogCalls.get())
        assertEquals(1, reportCalls.get())
        job.cancelAndJoin()
    }

}
