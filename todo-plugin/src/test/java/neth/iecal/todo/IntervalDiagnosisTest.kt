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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class IntervalDiagnosisTest {
    @Test fun oneSecondUpdatesIncompleteForegroundButStopsAfterCompletion() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "fake")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(accountId = "account", children = listOf(child)), "session"))
        store.updateMonitor { it.monitor.copy(accountId = "account", childName = "Sample", registrations = mapOf("Sample" to 1), intervalSeconds = "1") }
        val calls = AtomicInteger()
        val wall = AtomicLong(1_700_000_000_000)
        val elapsed = AtomicLong(0)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                calls.incrementAndGet()
                return DailyReport(1, 1)
            }
        }
        val host = object : CurboxHost {
            override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
            override suspend fun refreshObservation() = observations.value
        }
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> }, now = wall::get, elapsed = elapsed::get).run(host) }
        try {
            withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value != null } }
            assertEquals(1, calls.get())
            elapsed.set(999)
            host.observations.value = host.observations.value.copy(screen = Screen.UNLOCKED, foreground = Foreground.Confirmed("com.enuma.todomath"))
            delay(150)
            assertEquals(1, calls.get())
            for (n in 1..2) {
                wall.addAndGet(1000)
                elapsed.set(n * 1000L)
                host.observations.value = host.observations.value.copy(observedAtMillis = n.toLong())
                withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.checkedAt == wall.get() } }
                assertEquals(n + 1, calls.get())
            }
            val current = store.claim()
            val completed = current.state.copy(goalsByAccount = mapOf("account" to mapOf(("Sample" to Subject.MATH) to 1)))
            assertTrue(store.commit(current.revision, completed, "session", "session"))
            delay(150)
            wall.addAndGet(1000)
            elapsed.set(3000)
            host.observations.value = host.observations.value.copy(observedAtMillis = 3)
            delay(250)
            assertEquals(3, calls.get())
        } finally { job.cancelAndJoin() }
    }
}
