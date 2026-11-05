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
    @Test fun onlyARegularCrossingRequestsAnAchievement() = runBlocking {
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
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(if (calls.incrementAndGet() == 1) 2 else 5, 1)
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val effects = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Subject>>())
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, childName, subject, _ -> effects.add(childName to subject) }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
            assertTrue(effects.isEmpty())
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { while (effects.isEmpty()) delay(10) }
            assertEquals(listOf("Sample" to Subject.MATH), effects.toList())
            clock.set(121000)
            host.observations.value = host.observations.value.copy(callbackReceived = true)
            delay(150)
            assertEquals(2, calls.get())
            assertEquals(1, effects.size)
        } finally { job.cancelAndJoin() }
    }

    @Test fun changedGoalDoesNotTurnExistingProgressIntoAnAchievement() = runBlocking {
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
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(2, 1).also { calls.incrementAndGet() }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val effects = java.util.Collections.synchronizedList(mutableListOf<Subject>())
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, _, subject, _ -> effects.add(subject) }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
            assertTrue(screen.saveGoal("Sample", Subject.MATH, "2"))
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            delay(150)
            assertEquals(1, calls.get())
            assertTrue(effects.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun newLearningCanReachAChangedGoal() = runBlocking {
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
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(if (calls.incrementAndGet() == 1) 0 else 1, 1)
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val effects = java.util.Collections.synchronizedList(mutableListOf<Subject>())
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, _, subject, _ -> effects.add(subject) }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 0 } }
            assertTrue(screen.saveGoal("Sample", Subject.MATH, "1"))
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { while (effects.isEmpty()) delay(10) }
            assertEquals(listOf(Subject.MATH), effects.toList())
        } finally { job.cancelAndJoin() }
    }

    @Test fun completedBaselineIsSilentAndOtherChildCanAchieveAfterFailure() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "one")), TodoChild("Two", mapOf(Subject.MATH to "two")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (userId == "one") return DailyReport(5, 1)
                return when (calls.incrementAndGet()) {
                    1 -> DailyReport(2, 1)
                    2 -> throw TodoApiException(kind = LearningFailure.NETWORK)
                    else -> DailyReport(5, 1)
                }
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val effects = java.util.Collections.synchronizedList(mutableListOf<String>())
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, child, _, _ -> effects.add(child) }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports.size == 2 } }
            assertTrue(effects.isEmpty())
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { store.changes.first { it.monitor.reports["Two" to Subject.MATH]?.failed == true } }
            assertTrue(effects.isEmpty())
            clock.set(121000)
            host.observations.value = host.observations.value.copy(callbackReceived = true)
            withTimeout(5000) { while (effects.isEmpty()) delay(10) }
            assertEquals(listOf("Two"), effects.toList())
        } finally { job.cancelAndJoin() }
    }

    @Test fun acceptedAchievementRemainsAuthorizedAfterLeavingTheApp() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val calls = AtomicInteger()
        val enteredReport = java.util.concurrent.CountDownLatch(1)
        val releaseReport = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (calls.incrementAndGet() == 1) return DailyReport(1, 1)
                enteredReport.countDown()
                check(releaseReport.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return DailyReport(5, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val authorized = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, _, _, valid ->
            authorized.complete(valid())
        }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 1 } }
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { while (enteredReport.count != 0L) delay(10) }
            host.observations.value = host.observations.value.copy(foreground = neth.iecal.plugin.Foreground.Confirmed("home"))
            releaseReport.countDown()
            assertEquals(true, withTimeout(5000) { authorized.await() })
            clock.set(121000)
            delay(150)
            assertEquals(2, calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun deregistrationRevokesQueuedAchievement() = runBlocking {
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
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(if (calls.incrementAndGet() == 1) 1 else 5, 1)
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val authorized = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get, achievement = { _, _, _, _, valid ->
            entered.complete(Unit)
            release.await()
            authorized.complete(valid())
        }).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 1 } }
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { entered.await() }
            screen.toggleMonitor("Sample")
            release.complete(Unit)
            assertEquals(false, withTimeout(5000) { authorized.await() })
        } finally { job.cancelAndJoin() }
    }

    @Test fun registeredChildrenShareOneRegularCycle() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "one")), TodoChild("Two", mapOf(Subject.MATH to "two")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                calls.add(userId)
                return DailyReport(1, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, elapsed = clock::get).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports.size == 2 } }
            calls.clear()
            clock.set(61000)
            host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"))
            withTimeout(5000) { while (calls.size < 2) delay(10) }
            assertEquals(setOf("one", "two"), calls.toSet())
            assertEquals(61000L, store.read().monitor.cycleStartedElapsedMillis)
        } finally { job.cancelAndJoin() }
    }

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
        host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.OFF)
        delay(150)
        assertEquals(1, calls.get())
        host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED, foreground = neth.iecal.plugin.Foreground.Confirmed("other.app"))
        delay(150)
        assertEquals(1, calls.get())
        host.observations.value = host.observations.value.copy(foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todomath"), callbackReceived = true)
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

    @Test fun registeringAnotherChildPreservesBothRegistrations() {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(1, 1)
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        assertEquals(setOf("One", "Two"), store.read().monitor.registrations.keys)
    }

    @Test fun registeringSecondChildDuringSlowFirstRequestKeepsBothResults() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (userId == "1") { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                return DailyReport(userId.toInt(), 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        screen.toggleMonitor("Two")
        release.countDown()
        withTimeout(5000) { store.changes.first { it.monitor.reports["Two" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(1, store.read().monitor.reports["One" to Subject.MATH]?.value?.count)
        job.cancelAndJoin()
    }

    @Test fun reregisteringQueuedChildDiscardsOldGeneration() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (userId == "1") { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                else calls.incrementAndGet()
                return DailyReport(userId.toInt(), 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        screen.toggleMonitor("Two")
        screen.toggleMonitor("Two")
        screen.toggleMonitor("Two")
        release.countDown()
        withTimeout(5000) { store.changes.first { it.monitor.reports["Two" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(1, calls.get())
        assertEquals(1, store.read().monitor.reports["One" to Subject.MATH]?.value?.count)
        job.cancelAndJoin()
    }

    @Test fun metadataFailureForOneChildDoesNotStopAnother() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession): List<TodoChild> {
                if (calls.incrementAndGet() == 1) throw TodoApiException(kind = LearningFailure.NETWORK)
                return children
            }
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(userId.toInt(), 1)
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.catalogFailure == LearningFailure.NETWORK } }
        screen.toggleMonitor("Two")
        withTimeout(5000) { store.changes.first { it.monitor.reports["Two" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(LearningFailure.NETWORK, store.read().monitor.catalogFailures["One"])
        assertEquals(null, store.read().monitor.catalogFailures["Two"])
        screen.toggleMonitor("One")
        screen.toggleMonitor("One")
        delay(150)
        assertEquals(2, calls.get())
        screen.retryMonitor("One", null)
        withTimeout(5000) { store.changes.first { it.monitor.reports["One" to Subject.MATH]?.value?.count == 1 } }
        assertEquals(2, store.read().monitor.reports["Two" to Subject.MATH]?.value?.count)
        assertEquals(3, calls.get())
        job.cancelAndJoin()
    }

    @Test fun retriesForTwoChildrenSurviveUntilTheWorkerStarts() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(userId)
                return DailyReport(userId.toInt(), 1)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        store.updateMonitor { it.monitor.copy(reports = children.associate { child ->
            (child.name to Subject.MATH) to ReportState(failed = true, failure = LearningFailure.NETWORK)
        }, reportFailureSessions = children.associate { (it.name to Subject.MATH) to "session" }) }
        screen.retryMonitor("One", Subject.MATH)
        screen.retryMonitor("Two", Subject.MATH)
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports.values.count { report -> report.value != null } == 2 } }
        assertEquals(listOf("1", "2"), requested.toList())
        job.cancelAndJoin()
    }

    @Test fun catalogAndReportRetriesSurviveTogglingAnotherChild() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")), TodoChild("Three", mapOf(Subject.MATH to "3")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(userId.toInt(), 1)
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        store.updateMonitor { it.monitor.copy(catalogFailures = mapOf("One" to LearningFailure.NETWORK), catalogFailureSessions = mapOf("One" to "session"),
            reports = mapOf(("Two" to Subject.MATH) to ReportState(failed = true, failure = LearningFailure.NETWORK)),
            reportFailureSessions = mapOf(("Two" to Subject.MATH) to "session")) }
        screen.retryMonitor("One", null)
        screen.retryMonitor("Two", Subject.MATH)
        screen.toggleMonitor("Three")
        screen.toggleMonitor("Three")
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["One" to Subject.MATH]?.value?.count == 1 && it.monitor.reports["Two" to Subject.MATH]?.value?.count == 2 } }
        job.cancelAndJoin()
    }

    @Test fun unregisteringChildCancelsOnlyItsOwnQueuedRetry() = runBlocking {
        val store = MemoryLearningStore()
        val children = listOf(TodoChild("One", mapOf(Subject.MATH to "1")), TodoChild("Two", mapOf(Subject.MATH to "2")))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = children, accountId = "account"), "session"))
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = children
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(userId)
                return DailyReport(userId.toInt(), 1)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        screen.toggleMonitor("Two")
        store.updateMonitor { it.monitor.copy(reports = children.associate { child ->
            (child.name to Subject.MATH) to ReportState(failed = true, failure = LearningFailure.NETWORK)
        }, reportFailureSessions = children.associate { (it.name to Subject.MATH) to "session" }) }
        screen.retryMonitor("One", Subject.MATH)
        screen.retryMonitor("Two", Subject.MATH)
        screen.toggleMonitor("Two")
        screen.toggleMonitor("Two")
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["One" to Subject.MATH]?.value?.count == 1 } }
        delay(150)
        assertEquals(listOf("1"), requested.toList())
        assertTrue(store.read().monitor.reports.getValue("Two" to Subject.MATH).failed)
        job.cancelAndJoin()
    }

    @Test fun retryRequestedDuringAnOlderRetryIsNotConsumedByItsResult() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("One", mapOf(Subject.MATH to "1"))
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
                val count = calls.incrementAndGet()
                if (count == 1) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                return DailyReport(count, 1)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("One")
        store.updateMonitor { it.monitor.copy(reports = mapOf(("One" to Subject.MATH) to ReportState(failed = true, failure = LearningFailure.NETWORK)),
            reportFailureSessions = mapOf(("One" to Subject.MATH) to "session")) }
        screen.retryMonitor("One", Subject.MATH)
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }).run(host) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        screen.retryMonitor("One", Subject.MATH)
        release.countDown()
        withTimeout(5000) { store.changes.first { it.monitor.reports["One" to Subject.MATH]?.value?.count == 2 } }
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
