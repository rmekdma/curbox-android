package neth.iecal.todo

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorOwnershipTest {
    private fun host() = object : CurboxHost {
        override val observations = MutableStateFlow(HostObservation(connection = Connection.CONNECTED, accessibilityEnabled = true))
        override suspend fun refreshObservation() = observations.value
    }

    @Test fun replacementOwnerRejectsOldResponseAndWaitsForOldNetworkCall() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return DailyReport(99, 1)
                }
                return DailyReport(2, 1)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val published = Collections.synchronizedList(mutableListOf<Int>())
        val permission = MutableStateFlow(NotificationPermission(true))
        val first = launch { InitialMonitor(api, store, permission, { true }, { doc, _ ->
            doc.monitor.reports["Sample" to Subject.MATH]?.value?.count?.let(published::add)
        }).run(host()) }
        withTimeout(5000) { while (entered.count != 0L) delay(10) }
        val second = launch { InitialMonitor(api, store, permission, { true }, { doc, _ ->
            doc.monitor.reports["Sample" to Subject.MATH]?.value?.count?.let(published::add)
        }).run(host()) }
        withTimeout(5000) { store.changes.first { it.monitor.ownerEpoch == 2L } }
        delay(100)
        assertEquals(1, calls.get())
        release.countDown()
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
        assertFalse(99 in published)
        assertEquals(2L, store.read().monitor.ownerEpoch)
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test fun orderedPublisherConvergesAfterSlowOldWriteAndRejectsStaleSnapshot() = runBlocking {
        val store = MemoryLearningStore()
        val old = requireNotNull(store.updateMonitor { it.monitor.copy(ownerEpoch = 1, controlRevision = 1, accountId = "a", childName = "old") })
        val permission = MutableStateFlow(NotificationPermission(true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var visible: String? = null
        val first = launch { OrderedLearningPublisher.publish(store, old, permission, permission.value) { doc ->
            entered.complete(Unit)
            release.await()
            visible = doc.monitor.childName
        } }
        withTimeout(5000) { entered.await() }
        val latest = requireNotNull(store.updateMonitor { it.monitor.copy(ownerEpoch = 2, controlRevision = 2, childName = null) })
        val next = launch { OrderedLearningPublisher.publish(store, latest, permission, permission.value) { visible = it.monitor.childName } }
        release.complete(Unit)
        first.join()
        next.join()
        assertEquals(null, visible)
        OrderedLearningPublisher.publish(store, old, permission, permission.value) { visible = it.monitor.childName }
        assertEquals(null, visible)
    }

    @Test fun metadataRetryRechecksRuntimeBaselinesButPreservesFailedSubjects() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.KOREAN to "k", Subject.MATH to "m", Subject.ENGLISH to "e"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val requested = Collections.synchronizedList(mutableListOf<Subject>())
        val koreanCalls = AtomicInteger()
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String): DailyReport {
                requested.add(subject)
                return DailyReport(if (subject == Subject.KOREAN && koreanCalls.incrementAndGet() > 1) 6 else 3, 2)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val date = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
        val success = "Sample" to Subject.KOREAN
        val failure = "Sample" to Subject.MATH
        val original = ReportState(DailyReport(6, 2), 123, date)
        store.updateMonitor { doc -> doc.monitor.copy(
            catalogAttempted = true, catalogFailure = LearningFailure.NETWORK,
            reports = mapOf(success to original, failure to ReportState(date = date, failed = true, failure = LearningFailure.SERVER)),
            baselineEpochs = mapOf(success to 1), baselineRegistrations = mapOf(success to doc.monitor.registrationEpoch),
            baselineSessions = mapOf(success to "session"), baselinePermissions = mapOf(success to 0)
        ) }
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val effects = Collections.synchronizedList(mutableListOf<Subject>())
        val host = host()
        val job = launch { InitialMonitor(api, store, MutableStateFlow(NotificationPermission(true)), { true }, { _, _ -> },
            elapsed = clock::get, achievement = { _, _, _, subject, _ -> effects.add(subject) }).run(host) }
        withTimeout(5000) { store.changes.first { it.monitor.ownerEpoch == 1L } }
        assertTrue(requested.isEmpty())
        screen.retryMonitor("Sample", null)
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.ENGLISH]?.value?.count == 3 } }
        val state = store.read().monitor
        assertEquals(listOf(Subject.KOREAN, Subject.ENGLISH), requested.toList())
        assertEquals(3, state.reports[success]?.value?.count)
        assertEquals(LearningFailure.SERVER, state.reports[failure]?.failure)
        assertTrue(state.reports[failure]?.failed == true)
        assertTrue(state.cycleStartedElapsedMillis > 0)
        assertTrue(effects.isEmpty())
        clock.set(61000)
        host.observations.value = host.observations.value.copy(screen = neth.iecal.plugin.Screen.UNLOCKED,
            foreground = neth.iecal.plugin.Foreground.Confirmed("com.enuma.todohangeul"))
        withTimeout(5000) { while (effects.isEmpty()) delay(10) }
        assertEquals(listOf(Subject.KOREAN), effects.toList())
        assertTrue(store.read().monitor.reports.getValue(failure).failed)
        job.cancelAndJoin()
    }
    @Test fun recreatedMonitorRestoresRegistrationWithSilentFreshBaseline() = runBlocking {
        val store = MemoryLearningStore()
        val child = TodoChild("Sample", mapOf(Subject.MATH to "m"))
        val session = TodoSession("account", "token")
        store.writeSession("session", session)
        val claim = store.claim()
        assertTrue(store.commit(claim.revision, LearningState(children = listOf(child), accountId = "account"), "session"))
        val count = AtomicInteger(1)
        val api = object : TodoApi {
            override fun signIn(email: String, password: String) = session
            override fun catalog(session: TodoSession) = listOf(child)
            override fun report(session: TodoSession, subject: Subject, userId: String, date: String) = DailyReport(count.get(), 1)
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val effects = Collections.synchronizedList(mutableListOf<Subject>())
        val permission = MutableStateFlow(NotificationPermission(true))
        fun monitor() = InitialMonitor(api, store, permission, { true }, { _, _ -> },
            achievement = { _, _, _, subject, _ -> effects.add(subject) })
        val first = launch { monitor().run(host()) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 1 } }
        first.cancelAndJoin()
        count.set(6)
        val restored = launch { monitor().run(host()) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 6 } }
        assertTrue(store.read().monitor.registeredChildren().containsKey("Sample"))
        assertTrue(effects.isEmpty())
        restored.cancelAndJoin()
    }

    @Test fun recreatedMonitorKeepsInitialFailurePausedUntilExplicitRetry() = runBlocking {
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
                if (calls.incrementAndGet() == 1) throw TodoApiException(kind = LearningFailure.NETWORK)
                return DailyReport(2, 1)
            }
        }
        val screen = LearningScreen(api, store)
        screen.restore()
        screen.toggleMonitor("Sample")
        val permission = MutableStateFlow(NotificationPermission(true))
        val restoredPublished = CompletableDeferred<Unit>()
        fun monitor() = InitialMonitor(api, store, permission, { true }, { doc, _ ->
            if (doc.monitor.ownerEpoch == 2L) restoredPublished.complete(Unit)
        })
        val first = launch { monitor().run(host()) }
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.failed == true } }
        first.cancelAndJoin()
        val restored = launch { monitor().run(host()) }
        withTimeout(5000) { restoredPublished.await() }
        delay(200)
        assertEquals(1, calls.get())
        assertEquals(LearningFailure.NETWORK, store.read().monitor.reports["Sample" to Subject.MATH]?.failure)
        screen.retryMonitor("Sample", Subject.MATH)
        withTimeout(5000) { store.changes.first { it.monitor.reports["Sample" to Subject.MATH]?.value?.count == 2 } }
        assertEquals(2, calls.get())
        restored.cancelAndJoin()
    }

}
