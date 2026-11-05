package neth.iecal.todo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.Screen
import java.math.BigInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import neth.iecal.plugin.Connection
import neth.iecal.plugin.CurboxHost
import neth.iecal.plugin.CurboxPlugin
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NotificationPermission(val granted: Boolean, val revision: Long = 0)

/** Runs initial reports in the service process; the publisher only sees committed state. */
class InitialMonitor(
    private val api: TodoApi,
    private val store: LearningStore,
    private val permission: StateFlow<NotificationPermission>,
    private val freshPermission: () -> Boolean,
    private val publish: suspend (StoredLearning, NotificationPermission) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) : CurboxPlugin {
    private companion object { val cycleMutex = Mutex() }
    private data class Control(val owner: Long, val version: Long, val manualRevision: Long, val registration: Long, val account: String?, val child: String?, val ref: String?, val goals: Map<Pair<String, Subject>, Int>, val connected: Boolean, val permission: NotificationPermission, val interval: String)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun run(host: CurboxHost) {
        val owner = withContext(Dispatchers.IO) {
            requireNotNull(store.updateMonitor { it.monitor.copy(ownerEpoch = Math.addExact(it.monitor.ownerEpoch, 1)) }).monitor.ownerEpoch
        }
        coroutineScope {
            val controls = Channel<Control>(Channel.CONFLATED)
            val finished = Channel<Unit>(Channel.CONFLATED)
            launch {
                combine(store.changes, host.observations, permission) { doc, observation, notification ->
                    Control(owner, doc.monitor.controlRevision, doc.revision, doc.monitor.registrationEpoch,
                        doc.state.accountId, doc.monitor.childName, doc.sessionRef,
                        doc.state.goalsByAccount[doc.state.accountId].orEmpty(),
                        observation.connection == Connection.CONNECTED && observation.accessibilityEnabled, notification, doc.monitor.intervalMinutes)
                }.distinctUntilChanged().collect { controls.send(it) }
            }
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch { host.observations.collect { wake.trySend(Unit) } }
            var current: Control? = null
            var running: Job? = null
            var pending = false
            while (isActive) {
                select<Unit> {
                    controls.onReceive { control ->
                        current = control
                        pending = true
                        launch {
                            val latest = withContext(Dispatchers.IO) { store.read() }
                            if (latest.monitor.ownerEpoch == owner) publish(latest, control.permission)
                        }
                    }
                    finished.onReceive { running = null }
                    wake.onReceive { }
                    onTimeout(1000) { }
                }
                val control = current
                if (running == null && control != null && control.connected && control.permission.granted && freshPermission() && control.account != null && control.child != null && control.ref != null) {
                    val initial = pending
                    pending = false
                    val observation = host.observations.value
                    val subject = if (observation.screen == Screen.UNLOCKED) when ((observation.foreground as? Foreground.Confirmed)?.packageName) {
                        "com.enuma.todohangeul" -> Subject.KOREAN
                        "com.enuma.todomath" -> Subject.MATH
                        "com.enuma.todoenglish" -> Subject.ENGLISH
                        else -> null
                    } else null
                    val doc = withContext(Dispatchers.IO) { store.read() }
                    val interval = runCatching { BigInteger(control.interval).multiply(BigInteger.valueOf(60_000)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong() }.getOrDefault(60_000)
                    val due = doc.monitor.cycleOwner != owner || elapsed() - doc.monitor.cycleStartedElapsedMillis >= interval
                    if (initial || (subject != null && due)) {
                        running = launch(Dispatchers.IO) { cycleMutex.withLock { initialize(control, host, if (initial) null else subject) } }.also { job ->
                            job.invokeOnCompletion { finished.trySend(Unit) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun initialize(control: Control, host: CurboxHost, regularSubject: Subject?) {
        fun today() = java.time.Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
        val date = today()
        fun valid(doc: StoredLearning): Boolean = doc.monitor.ownerEpoch == control.owner &&
            doc.monitor.controlRevision == control.version && doc.revision == control.manualRevision && doc.monitor.childName == control.child &&
            doc.state.accountId == control.account && doc.monitor.accountId == control.account && doc.sessionRef == control.ref &&
            doc.state.goalsByAccount[control.account].orEmpty() == control.goals && permission.value == control.permission && control.permission.granted && freshPermission() &&
            host.observations.value.connection == Connection.CONNECTED && host.observations.value.accessibilityEnabled &&
            today() == date
        var doc = store.read()
        if (!valid(doc)) return
        val session = store.readSession(requireNotNull(control.ref))
        if (session.accountId != control.account) return
        if (doc.monitor.reports.any { (key, report) -> report.failure == LearningFailure.AUTHENTICATION && doc.monitor.reportFailureSessions[key] == control.ref }) return
        val needsCatalog = doc.monitor.catalogRetryRequested || (doc.monitor.catalogFailure != null && doc.monitor.catalogFailureSession != null && doc.monitor.catalogFailureSession != control.ref) || (doc.monitor.catalogFailure == null &&
            (!doc.monitor.catalogAttempted || doc.monitor.catalogOwner != control.owner || doc.monitor.catalogRegistration != control.registration || doc.monitor.catalogSession != control.ref || doc.monitor.catalogPermissionRevision != control.permission.revision))
        val childBefore = doc.monitor.children.firstOrNull { it.name == control.child }
        fun goal(subject: Subject) = control.goals[requireNotNull(control.child) to subject] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
        fun needsInitial(subject: Subject): Boolean {
            val key = requireNotNull(control.child) to subject
            val previous = doc.monitor.reports[key]
            return goal(subject) > 0 && (previous?.failed != true || doc.monitor.reportRetryRequested == key || (doc.monitor.reportFailureSessions[key] != null && doc.monitor.reportFailureSessions[key] != control.ref)) &&
                (previous?.failed == true || previous?.date != date || doc.monitor.baselineEpochs[key] != control.owner || doc.monitor.baselineRegistrations[key] != control.registration || doc.monitor.baselineSessions[key] != control.ref || doc.monitor.baselinePermissions[key] != control.permission.revision)
        }
        if (regularSubject == null && !needsCatalog && (doc.monitor.catalogFailure != null || childBefore == null || childBefore.userIds.keys.none(::needsInitial))) return
        if (regularSubject != null) {
            val key = requireNotNull(control.child) to regularSubject
            val previous = doc.monitor.reports[key]
            val hasBaseline = previous?.value != null && doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref
            if (needsCatalog || doc.monitor.catalogFailure != null || childBefore?.userIds?.containsKey(regularSubject) != true || goal(regularSubject) == 0 ||
                (previous?.failed == true && !hasBaseline) || (previous != null && previous.date == date && previous.value != null && previous.value.count >= goal(regularSubject))) return
        }
        if (regularSubject != null) {
            val observation = host.refreshObservation()
            val expected = when (regularSubject) { Subject.KOREAN -> "com.enuma.todohangeul"; Subject.MATH -> "com.enuma.todomath"; Subject.ENGLISH -> "com.enuma.todoenglish" }
            if (observation.connection != Connection.CONNECTED || !observation.accessibilityEnabled || observation.screen != Screen.UNLOCKED || observation.foreground != Foreground.Confirmed(expected) || !valid(store.read())) return
        }
        doc = store.updateMonitor { current ->
            if (!valid(current)) null else current.monitor.copy(cycleOwner = control.owner, cycleStartedElapsedMillis = elapsed())
        } ?: return
        if (doc.monitor.catalogRetryRequested || (doc.monitor.catalogFailure != null && doc.monitor.catalogFailureSession != null && doc.monitor.catalogFailureSession != control.ref) || (doc.monitor.catalogFailure == null &&
                (!doc.monitor.catalogAttempted || doc.monitor.catalogOwner != control.owner || doc.monitor.catalogRegistration != control.registration || doc.monitor.catalogSession != control.ref || doc.monitor.catalogPermissionRevision != control.permission.revision))) {
            val children = try { api.catalog(session) } catch (e: TodoApiException) {
                currentCoroutineContext().ensureActive()
                val failed = store.updateMonitor { if (valid(it)) it.monitor.copy(catalogAttempted = true, catalogFailure = e.kind, catalogFailureSession = control.ref, catalogRetryRequested = false) else null }
                if (failed != null) publish(failed, control.permission)
                return
            }
            currentCoroutineContext().ensureActive()
            doc = store.updateMonitor { current ->
                if (!valid(current)) null else current.monitor.copy(catalogAttempted = true, catalogFailure = null, catalogFailureSession = null, catalogRetryRequested = false,
                    catalogOwner = control.owner, catalogRegistration = control.registration, catalogSession = control.ref,
                    catalogPermissionRevision = control.permission.revision, children = children)
            } ?: return
            publish(doc, control.permission)
        }
        if (doc.monitor.catalogFailure != null) return
        val child = doc.monitor.children.firstOrNull { it.name == control.child } ?: return
        for ((subject, id) in child.userIds) {
            if (regularSubject != null && subject != regularSubject) continue
            doc = store.read()
            if (!valid(doc)) return
            val key = child.name to subject
            val goal = control.goals[key] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val previous = doc.monitor.reports[key]
            if (regularSubject == null && (goal == 0 || (previous?.failed == true && doc.monitor.reportRetryRequested != key && (doc.monitor.reportFailureSessions[key] == null || doc.monitor.reportFailureSessions[key] == control.ref)) || (previous?.failed != true && previous?.date == date && doc.monitor.baselineEpochs[key] == control.owner && doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref &&
                    doc.monitor.baselinePermissions[key] == control.permission.revision))) continue
            if (regularSubject != null) {
                val observation = host.refreshObservation()
                val expected = when (subject) { Subject.KOREAN -> "com.enuma.todohangeul"; Subject.MATH -> "com.enuma.todomath"; Subject.ENGLISH -> "com.enuma.todoenglish" }
                if (observation.connection != Connection.CONNECTED || !observation.accessibilityEnabled || observation.screen != Screen.UNLOCKED || observation.foreground != Foreground.Confirmed(expected) || !valid(store.read())) return
            }
            val result = try { ReportState(api.report(session, subject, id, date), now(), date) }
                catch (e: TodoApiException) { (previous ?: ReportState(date = date)).copy(failed = true, failure = e.kind) }
            currentCoroutineContext().ensureActive()
            doc = store.updateMonitor { current ->
                if (!valid(current)) null else current.monitor.copy(reports = current.monitor.reports + (key to result),
                    reportRetryRequested = if (current.monitor.reportRetryRequested == key) null else current.monitor.reportRetryRequested,
                    reportFailureSessions = if (result.failed) current.monitor.reportFailureSessions + (key to requireNotNull(control.ref)) else current.monitor.reportFailureSessions - key,
                    baselineEpochs = if (result.failed) current.monitor.baselineEpochs else current.monitor.baselineEpochs + (key to control.owner),
                    baselineRegistrations = if (result.failed) current.monitor.baselineRegistrations else current.monitor.baselineRegistrations + (key to control.registration),
                    baselineSessions = if (result.failed) current.monitor.baselineSessions else current.monitor.baselineSessions + (key to requireNotNull(control.ref)),
                    baselinePermissions = if (result.failed) current.monitor.baselinePermissions else current.monitor.baselinePermissions + (key to control.permission.revision))
            } ?: return
            publish(doc, control.permission)
            if (result.failure == LearningFailure.AUTHENTICATION) return
        }
    }
}
