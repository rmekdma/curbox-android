package neth.iecal.todo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
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
    private val now: () -> Long = System::currentTimeMillis
) : CurboxPlugin {
    private companion object { val cycleMutex = Mutex() }
    private data class Control(val owner: Long, val version: Long, val manualRevision: Long, val registration: Long, val account: String?, val child: String?, val registrations: Map<String, Long>, val ref: String?, val goals: Map<Pair<String, Subject>, Int>, val connected: Boolean, val permission: NotificationPermission)

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
                        doc.state.accountId, doc.monitor.childName, doc.monitor.registeredChildren(), doc.sessionRef,
                        doc.state.goalsByAccount[doc.state.accountId].orEmpty(),
                        observation.connection == Connection.CONNECTED && observation.accessibilityEnabled, notification)
                }.distinctUntilChanged().collect { controls.send(it) }
            }
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
                }
                val control = current
                if (running == null && pending && control != null) {
                    pending = false
                    if (control.connected && control.permission.granted && freshPermission() && control.account != null && control.registrations.isNotEmpty() && control.ref != null) {
                        running = launch(Dispatchers.IO) { cycleMutex.withLock { for ((child, registration) in control.registrations) initialize(control.copy(child = child, registration = registration), host) } }.also { job ->
                            job.invokeOnCompletion { finished.trySend(Unit) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun initialize(control: Control, host: CurboxHost) {
        val date = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE)
        fun valid(doc: StoredLearning): Boolean = doc.monitor.ownerEpoch == control.owner &&
            doc.revision == control.manualRevision && doc.monitor.registeredChildren()[control.child] == control.registration &&
            doc.state.accountId == control.account && doc.monitor.accountId == control.account && doc.sessionRef == control.ref &&
            doc.state.goalsByAccount[control.account].orEmpty() == control.goals && permission.value == control.permission && control.permission.granted && freshPermission() &&
            host.observations.value.connection == Connection.CONNECTED && host.observations.value.accessibilityEnabled &&
            LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE) == date
        var doc = store.read()
        if (!valid(doc)) return
        val session = store.readSession(requireNotNull(control.ref))
        if (session.accountId != control.account) return
        val childName = requireNotNull(control.child)
        fun failure(monitor: MonitorState) = monitor.catalogFailureFor(childName)
        fun failureSession(monitor: MonitorState) = monitor.catalogFailureSessionFor(childName)
        fun retry(monitor: MonitorState) = monitor.catalogRetryRequested && (monitor.catalogRetryChild == null || monitor.catalogRetryChild == childName)
        val knownChild = doc.monitor.children.firstOrNull { it.name == control.child }
        val initialized = knownChild != null && knownChild.userIds.keys.all { subject ->
            val key = knownChild.name to subject
            val goal = control.goals[key] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val report = doc.monitor.reports[key]
            goal == 0 || (report?.failed == true && (doc.monitor.reportFailureSessions[key] == null || doc.monitor.reportFailureSessions[key] == control.ref) && doc.monitor.reportRetryRequested != key) ||
                (report?.failed != true && report?.date == date && doc.monitor.baselineEpochs[key] == control.owner &&
                    doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref &&
                    doc.monitor.baselinePermissions[key] == control.permission.revision)
        }
        if (initialized && failure(doc.monitor) == null && !retry(doc.monitor) &&
            (doc.monitor.catalogRegistrations[childName] ?: doc.monitor.catalogRegistration) == control.registration) return
        if (doc.monitor.reports.any { (key, report) -> report.failure == LearningFailure.AUTHENTICATION && doc.monitor.reportFailureSessions[key] == control.ref }) return
        doc = store.updateMonitor { current ->
            if (!valid(current)) null else current.monitor.copy(cycleOwner = control.owner, cycleStartedElapsedMillis = System.nanoTime() / 1_000_000)
        } ?: return
        if (retry(doc.monitor) || (failure(doc.monitor) != null && failureSession(doc.monitor) != null && failureSession(doc.monitor) != control.ref) || (failure(doc.monitor) == null &&
                (!doc.monitor.catalogAttempted || doc.monitor.catalogOwner != control.owner || (doc.monitor.catalogRegistrations[childName] ?: doc.monitor.catalogRegistration) != control.registration || doc.monitor.catalogSession != control.ref || doc.monitor.catalogPermissionRevision != control.permission.revision))) {
            val children = try { api.catalog(session) } catch (e: TodoApiException) {
                currentCoroutineContext().ensureActive()
                val failed = store.updateMonitor { if (valid(it)) it.monitor.copy(catalogAttempted = true, catalogFailure = e.kind, catalogFailureSession = control.ref, catalogFailures = it.monitor.catalogFailures + (childName to e.kind), catalogFailureSessions = it.monitor.catalogFailureSessions + (childName to requireNotNull(control.ref)), catalogRetryRequested = if (retry(it.monitor)) false else it.monitor.catalogRetryRequested, catalogRetryChild = if (retry(it.monitor)) null else it.monitor.catalogRetryChild) else null }
                if (failed != null) publish(failed, control.permission)
                return
            }
            currentCoroutineContext().ensureActive()
            doc = store.updateMonitor { current ->
                if (!valid(current)) null else current.monitor.copy(catalogAttempted = true, catalogFailure = null, catalogFailureSession = null, catalogFailures = current.monitor.catalogFailures - childName, catalogFailureSessions = current.monitor.catalogFailureSessions - childName, catalogRetryRequested = if (retry(current.monitor)) false else current.monitor.catalogRetryRequested, catalogRetryChild = if (retry(current.monitor)) null else current.monitor.catalogRetryChild,
                    catalogRegistrations = current.monitor.catalogRegistrations + (childName to control.registration), catalogOwner = control.owner, catalogRegistration = control.registration, catalogSession = control.ref,
                    catalogPermissionRevision = control.permission.revision, children = children)
            } ?: return
            publish(doc, control.permission)
        }
        if (failure(doc.monitor) != null) return
        val child = doc.monitor.children.firstOrNull { it.name == control.child } ?: return
        for ((subject, id) in child.userIds) {
            doc = store.read()
            if (!valid(doc)) return
            val key = child.name to subject
            val goal = control.goals[key] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val previous = doc.monitor.reports[key]
            if (goal == 0 || (previous?.failed == true && doc.monitor.reportRetryRequested != key && (doc.monitor.reportFailureSessions[key] == null || doc.monitor.reportFailureSessions[key] == control.ref)) || (previous?.failed != true && previous?.date == date && doc.monitor.baselineEpochs[key] == control.owner && doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref &&
                    doc.monitor.baselinePermissions[key] == control.permission.revision)) continue
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
