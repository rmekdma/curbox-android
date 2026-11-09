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
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.HostObservation
import neth.iecal.plugin.Screen
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LearningExitCheckTest {
    private class Fixture(private val failFirst: Boolean = false, private val firstCount: Int = 5, intervalSeconds: String? = null, goal: Int? = null) {
        val store = MemoryLearningStore()
        val child = TodoChild("Child", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "fake")
        val calls = AtomicInteger()
        val achievements = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        @Volatile var blockExit = false
        @Volatile var refreshResult: HostObservation? = null
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                val call = calls.incrementAndGet()
                val active = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { maxOf(it, active) }
                try {
                    if (failFirst && call == 1) throw TodoApiException(kind = LearningFailure.NETWORK)
                    if (blockExit && call == 2) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    return DailyReport(if (call == 1) firstCount else 5, 1)
                } finally { inFlight.decrementAndGet() }
            }
        }
        val observationState = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true,
            screen = Screen.UNLOCKED, foreground = Foreground.Confirmed("com.enuma.todomath")))
        val host = object : CurboxHost {
            override val observations = observationState
            override suspend fun refreshObservation() = refreshResult ?: observations.value
        }
        init {
            store.writeSession("session", session)
            val claim = store.claim()
            val goals = if (goal == null) emptyMap() else mapOf("account" to mapOf(("Child" to Subject.MATH) to goal))
            assertTrue(store.commit(claim.revision, LearningState(accountId = "account", children = listOf(child), goalsByAccount = goals), "session"))
            val screen = testLearningScreen(api, store)
            screen.restore()
            screen.toggleMonitor("Child")
            if (intervalSeconds != null) store.updateMonitor { it.monitor.copy(intervalSeconds = intervalSeconds) }
        }
        fun monitor() = InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> },
            achievement = { _, _, _, _, _ -> achievements.incrementAndGet(); Unit })
        suspend fun baseline() { withTimeout(5000) { store.changes.first { it.monitor.reports["Child" to Subject.MATH]?.value != null } } }
        fun foreground(value: Foreground, stamp: Long = 1) { observationState.value = observationState.value.copy(foreground = value, observedAtMillis = stamp) }
    }

    @Test fun completedExitWaitsThenRefreshesOnceSilentlyEvenWhenUnknownRepeats() = runBlocking {
        val f = Fixture()
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            f.foreground(Foreground.Unknown)
            delay(400)
            assertEquals(1, f.calls.get())
            withTimeout(4000) { while (f.calls.get() < 2) delay(10) }
            assertEquals(0, f.achievements.get())
            f.foreground(Foreground.Unknown, 2)
            delay(1200)
            assertEquals(2, f.calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun returningOrScreenOffCancelsPendingExit() = runBlocking {
        val f = Fixture()
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            f.foreground(Foreground.Unknown)
            delay(150)
            f.foreground(Foreground.Confirmed("com.enuma.todomath"), 2)
            delay(1200)
            assertEquals(1, f.calls.get())
            f.foreground(Foreground.Unknown, 3)
            delay(100)
            f.observationState.value = f.observationState.value.copy(screen = Screen.OFF, foreground = Foreground.Unknown)
            delay(1200)
            assertEquals(1, f.calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun recheckReturningSameAppCancelsUnknownExit() = runBlocking {
        val f = Fixture()
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            f.refreshResult = f.observationState.value
            f.foreground(Foreground.Unknown)
            delay(1300)
            assertEquals(1, f.calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun zeroGoalAlsoRefreshesAfterConfirmedOtherApp() = runBlocking {
        val f = Fixture(goal = 0)
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            f.foreground(Foreground.Confirmed("another.app"))
            withTimeout(4000) { while (f.calls.get() < 2) delay(10) }
            assertEquals(0, f.achievements.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun replacingSessionWhileExitWaitsRejectsOldWork() = runBlocking {
        val f = Fixture()
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            f.foreground(Foreground.Unknown)
            delay(100)
            val claim = f.store.claim()
            assertTrue(f.store.commit(claim.revision, claim.state, null, "session"))
            delay(1300)
            assertEquals(1, f.calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun initialFailureIsNotRetriedByExit() = runBlocking {
        val f = Fixture(failFirst = true)
        val job = launch { f.monitor().run(f.host) }
        try {
            withTimeout(5000) { f.store.changes.first { it.monitor.reports["Child" to Subject.MATH]?.failed == true } }
            f.foreground(Foreground.Unknown)
            delay(1300)
            assertEquals(1, f.calls.get())
        } finally { job.cancelAndJoin() }
    }

    @Test fun returnDuringExitRequestRejectsItsLateResult() = runBlocking {
        val f = Fixture()
        val job = launch { f.monitor().run(f.host) }
        try {
            f.baseline()
            val version = f.store.read().monitor.reportVersions["Child" to Subject.MATH]
            f.blockExit = true
            f.foreground(Foreground.Unknown)
            withTimeout(5000) { while (f.entered.count != 0L) delay(10) }
            f.foreground(Foreground.Confirmed("com.enuma.todomath"), 2)
            delay(100)
            f.release.countDown()
            delay(200)
            assertEquals(version, f.store.read().monitor.reportVersions["Child" to Subject.MATH])
            assertEquals(0, f.achievements.get())
        } finally { f.release.countDown(); job.cancelAndJoin() }
    }

    @Test fun exitWaitsForRunningCycleAndDoesNotOverlapIt() = runBlocking {
        val f = Fixture(firstCount = 1, intervalSeconds = "1")
        val job = launch { f.monitor().run(f.host) }
        try {
            f.blockExit = true
            f.baseline()
            withTimeout(5000) { while (f.entered.count != 0L) delay(10) }
            f.foreground(Foreground.Unknown)
            delay(1300)
            assertEquals(2, f.calls.get())
            f.release.countDown()
            withTimeout(5000) { while (f.calls.get() < 3) delay(10) }
            assertEquals(1, f.maxInFlight.get())
        } finally { f.release.countDown(); job.cancelAndJoin() }
    }
}
