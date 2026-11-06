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
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val achievement: suspend (StoredLearning, NotificationPermission, String, Subject, suspend () -> Boolean) -> Unit = { _, _, _, _, _ -> }
) : CurboxPlugin {
    private companion object { val cycleMutex = Mutex() }
    private data class Control(val owner: Long, val version: Long, val goalVersions: Map<Pair<String, Subject>, Long>, val registration: Long, val account: String?, val child: String?, val registrations: Map<String, Long>, val ref: String?, val goals: Map<Pair<String, Subject>, Int>, val connected: Boolean, val permission: NotificationPermission, val interval: String)
    private class RegularCycle(var started: Boolean = false)
    private fun today() = java.time.Instant.ofEpochMilli(now()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun run(host: CurboxHost) {
        val owner = withContext(Dispatchers.IO) {
            requireNotNull(store.updateMonitor { it.monitor.resetLearningBaselines().copy(ownerEpoch = Math.addExact(it.monitor.ownerEpoch, 1)) }).monitor.ownerEpoch
        }
        coroutineScope {
            val controls = Channel<Control>(Channel.CONFLATED)
            val finished = Channel<Unit>(Channel.CONFLATED)
            val connectionEpoch = java.util.concurrent.atomic.AtomicLong()
            var lastConnected: Boolean? = null
            launch {
                combine(store.changes, host.observations, permission) { doc, observation, notification ->
                    val connected = observation.connection == Connection.CONNECTED && observation.accessibilityEnabled
                    if (lastConnected != null && lastConnected != connected) connectionEpoch.incrementAndGet()
                    lastConnected = connected
                    Control(owner, doc.monitor.controlRevision, doc.monitor.goalVersions[doc.state.accountId].orEmpty(), doc.monitor.registrationEpoch,
                        doc.state.accountId, doc.monitor.childName, doc.monitor.registeredChildren(), doc.sessionRef,
                        doc.state.goalsByAccount[doc.state.accountId].orEmpty(),
                        observation.connection == Connection.CONNECTED && observation.accessibilityEnabled, notification, doc.monitor.intervalMinutes)
                }.distinctUntilChanged().collect { controls.send(it) }
            }
            val wake = Channel<Unit>(Channel.CONFLATED)
            launch { host.observations.collect { wake.trySend(Unit) } }
            var current: Control? = null
            var running: Job? = null
            var pending = false
            var restartInitial: java.util.concurrent.atomic.AtomicBoolean? = null
            var observedDate = today()
            val dateEpoch = java.util.concurrent.atomic.AtomicLong()
            while (isActive) {
                val waitMillis = current?.let { control ->
                    val doc = withContext(Dispatchers.IO) { store.read() }
                    val interval = runCatching { BigInteger(control.interval).multiply(BigInteger.valueOf(60_000)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong() }.getOrDefault(60_000)
                    val age = if (doc.monitor.cycleOwner == owner) (elapsed() - doc.monitor.cycleStartedElapsedMillis).coerceAtLeast(0) else interval
                    if (age >= interval) 60_000L else interval - age
                } ?: 60_000L
                val wall = now()
                val zone = ZoneId.of("Asia/Seoul")
                val nextMidnight = java.time.Instant.ofEpochMilli(wall).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val untilMidnight = (nextMidnight - wall).coerceAtLeast(1)
                select<Unit> {
                    controls.onReceive { control ->
                        val old = current
                        if (old?.connected == true && !control.connected) {
                            withContext(Dispatchers.IO) { store.updateMonitor { doc -> if (doc.monitor.ownerEpoch == owner) doc.monitor.resetLearningBaselines() else null } }
                        }
                        val goalOnly = old != null && old.copy(goals = control.goals, goalVersions = control.goalVersions) == control
                        pending = pending || !goalOnly
                        current = control
                        launch {
                            val latest = withContext(Dispatchers.IO) { store.read() }
                            if (latest.monitor.ownerEpoch == owner) publish(latest, control.permission)
                        }
                    }
                    finished.onReceive {
                        if (restartInitial?.get() == true) pending = true
                        running = null
                        restartInitial = null
                    }
                    wake.onReceive { }
                    onTimeout(minOf(waitMillis, untilMidnight, 60_000L)) { }
                }
                val date = today()
                if (date != observedDate) {
                    observedDate = date
                    dateEpoch.incrementAndGet()
                    pending = true
                    val reset = withContext(Dispatchers.IO) {
                        store.updateMonitor { doc ->
                            if (doc.monitor.ownerEpoch != owner) null else doc.monitor.resetLearningBaselines()
                        }
                    }
                    if (reset != null) current?.let { publish(reset, it.permission) }
                }
                val control = current
                if (running == null && control != null && control.connected && control.permission.granted && freshPermission() && control.account != null && control.registrations.isNotEmpty() && control.ref != null) {
                    val initial = pending
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
                    if ((initial || (subject != null && due)) && doc.monitor.goalVersions[control.account].orEmpty() == control.goalVersions) {
                        pending = false
                        val restart = java.util.concurrent.atomic.AtomicBoolean(false)
                        restartInitial = restart.takeIf { initial }
                        val requestDateEpoch = dateEpoch.get()
                        val requestConnectionEpoch = connectionEpoch.get()
                        running = launch(Dispatchers.IO) {
                            cycleMutex.withLock {
                                val cycle = if (initial) null else RegularCycle()
                                for ((child, registration) in control.registrations) {
                                    initialize(control.copy(child = child, registration = registration), host, if (initial) null else subject, cycle, restart, dateEpoch, requestDateEpoch, connectionEpoch, requestConnectionEpoch)
                                }
                            }
                        }.also { job -> job.invokeOnCompletion { finished.trySend(Unit) } }
                    }
                }
            }
        }
    }

    private suspend fun initialize(control: Control, host: CurboxHost, regularSubject: Subject?, cycle: RegularCycle?, restartInitial: java.util.concurrent.atomic.AtomicBoolean, dateEpoch: java.util.concurrent.atomic.AtomicLong, requestDateEpoch: Long, connectionEpoch: java.util.concurrent.atomic.AtomicLong, requestConnectionEpoch: Long) {
        val date = today()
        fun valid(doc: StoredLearning, subject: Subject? = null): Boolean = doc.monitor.ownerEpoch == control.owner &&
            doc.monitor.registeredChildren()[control.child] == control.registration &&
            doc.state.accountId == control.account && doc.monitor.accountId == control.account && doc.sessionRef == control.ref &&
            (subject == null || (doc.monitor.goalVersions[control.account].orEmpty()[control.child to subject] ?: 0) == (control.goalVersions[control.child to subject] ?: 0)) && permission.value == control.permission && control.permission.granted && freshPermission() &&
            host.observations.value.connection == Connection.CONNECTED && host.observations.value.accessibilityEnabled &&
            today() == date && dateEpoch.get() == requestDateEpoch && connectionEpoch.get() == requestConnectionEpoch
        var doc = store.read()
        if (!valid(doc) || doc.monitor.authenticationRequired(control.ref)) return
        if (doc.monitor.catalogRetryRequested || doc.monitor.reportRetryRequested != null) {
            doc = store.updateMonitor { if (valid(it)) it.monitor.migrateRetries(it.sessionRef) else null } ?: return
        }
        val session = store.readSession(requireNotNull(control.ref))
        if (session.accountId != control.account) return
        val childName = requireNotNull(control.child)
        fun failure(monitor: MonitorState) = monitor.catalogFailureFor(childName)
        fun failureSession(monitor: MonitorState) = monitor.catalogFailureSessionFor(childName)
        fun eligible(intent: RetryIntent?) = intent?.registration == control.registration && intent.sessionRef == control.ref
        fun retry(monitor: MonitorState) = eligible(monitor.catalogRetries[childName])
        fun reportRetry(monitor: MonitorState, key: Pair<String, Subject>) = eligible(monitor.reportRetries[key])
        val knownChild = doc.monitor.children.firstOrNull { it.name == control.child }
        val zeroInitial = ZeroGoalInitial(control.owner, control.registration, requireNotNull(control.ref), control.permission.revision, date, requestDateEpoch)
        fun wasZeroInitial(monitor: MonitorState, key: Pair<String, Subject>) = monitor.zeroGoalInitials[key] == zeroInitial
        fun recordZeros(current: StoredLearning, child: TodoChild): MonitorState? {
            if (!valid(current)) return null
            val keys = child.userIds.keys.map { child.name to it }.filter { key ->
                (control.goals[key] ?: when (key.second) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }) == 0 &&
                    (current.monitor.goalVersions[control.account].orEmpty()[key] ?: 0) == (control.goalVersions[key] ?: 0)
            }
            return current.monitor.copy(zeroGoalInitials = current.monitor.zeroGoalInitials + keys.associateWith { zeroInitial })
        }
        if (knownChild != null && doc.monitor.catalogOwner == control.owner &&
            doc.monitor.catalogRegistrations[childName] == control.registration && doc.monitor.catalogSession == control.ref &&
            doc.monitor.catalogPermissionRevision == control.permission.revision) {
            doc = store.updateMonitor { recordZeros(it, knownChild) } ?: return
        }
        val initialized = knownChild != null && knownChild.userIds.keys.all { subject ->
            val key = knownChild.name to subject
            val goal = control.goals[key] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val report = doc.monitor.reports[key]
            goal == 0 || (!reportRetry(doc.monitor, key) && (wasZeroInitial(doc.monitor, key) || (report?.failed == true && report.initialFailure != false && (doc.monitor.reportFailureSessions[key] == null || doc.monitor.reportFailureSessions[key] == control.ref) && !reportRetry(doc.monitor, key)) ||
                ((report?.failed != true || report.initialFailure == false) && report?.date == date && doc.monitor.baselineEpochs[key] == control.owner &&
                    doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref &&
                    doc.monitor.baselinePermissions[key] == control.permission.revision)))
        }
        if (regularSubject == null && initialized && failure(doc.monitor) == null && !retry(doc.monitor) &&
            (doc.monitor.catalogRegistrations[childName] ?: doc.monitor.catalogRegistration) == control.registration) return
        if (doc.monitor.reports.any { (key, report) -> report.failure == LearningFailure.AUTHENTICATION && doc.monitor.reportFailureSessions[key] == control.ref }) return
        val needsCatalog = retry(doc.monitor) || (failure(doc.monitor) != null && failureSession(doc.monitor) != null && failureSession(doc.monitor) != control.ref) || (failure(doc.monitor) == null &&
            (!doc.monitor.catalogAttempted || doc.monitor.catalogOwner != control.owner || (doc.monitor.catalogRegistrations[childName] ?: doc.monitor.catalogRegistration) != control.registration || doc.monitor.catalogSession != control.ref || doc.monitor.catalogPermissionRevision != control.permission.revision))
        val childBefore = doc.monitor.children.firstOrNull { it.name == control.child }
        fun goal(subject: Subject) = control.goals[requireNotNull(control.child) to subject] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
        fun needsInitial(subject: Subject): Boolean {
            val key = requireNotNull(control.child) to subject
            val previous = doc.monitor.reports[key]
            return goal(subject) > 0 && (!wasZeroInitial(doc.monitor, key) || reportRetry(doc.monitor, key)) && (previous?.failed != true || previous.initialFailure == false || reportRetry(doc.monitor, key) || (doc.monitor.reportFailureSessions[key] != null && doc.monitor.reportFailureSessions[key] != control.ref)) &&
                (reportRetry(doc.monitor, key) || previous?.date != date || doc.monitor.baselineEpochs[key] != control.owner || doc.monitor.baselineRegistrations[key] != control.registration || doc.monitor.baselineSessions[key] != control.ref || doc.monitor.baselinePermissions[key] != control.permission.revision)
        }
        if (regularSubject == null && !needsCatalog && (failure(doc.monitor) != null || childBefore == null || childBefore.userIds.keys.none(::needsInitial))) return
        if (regularSubject != null) {
            val key = requireNotNull(control.child) to regularSubject
            val previous = doc.monitor.reports[key]
            val hasBaseline = previous?.value != null && previous.date == date && doc.monitor.baselineEpochs[key] == control.owner && doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref && doc.monitor.baselinePermissions[key] == control.permission.revision
            if (needsCatalog || failure(doc.monitor) != null || childBefore?.userIds?.containsKey(regularSubject) != true || goal(regularSubject) == 0 ||
                (previous?.failed == true && (previous.initialFailure != false || !hasBaseline)) || (previous != null && previous.date == date && previous.value != null && previous.value.count >= goal(regularSubject))) return
        }
        if (regularSubject != null) {
            val observation = host.refreshObservation()
            val expected = when (regularSubject) { Subject.KOREAN -> "com.enuma.todohangeul"; Subject.MATH -> "com.enuma.todomath"; Subject.ENGLISH -> "com.enuma.todoenglish" }
            if (observation.connection != Connection.CONNECTED || !observation.accessibilityEnabled || observation.screen != Screen.UNLOCKED || observation.foreground != Foreground.Confirmed(expected) || !valid(store.read(), regularSubject)) return
        }
        if (cycle == null || !cycle.started) {
            doc = store.updateMonitor { current ->
                if (!valid(current, regularSubject)) null else current.monitor.copy(cycleOwner = control.owner, cycleStartedElapsedMillis = elapsed())
            } ?: return
            if (cycle != null) cycle.started = true
        }
        if (retry(doc.monitor) || (failure(doc.monitor) != null && failureSession(doc.monitor) != null && failureSession(doc.monitor) != control.ref) || (failure(doc.monitor) == null &&
                (!doc.monitor.catalogAttempted || doc.monitor.catalogOwner != control.owner || (doc.monitor.catalogRegistrations[childName] ?: doc.monitor.catalogRegistration) != control.registration || doc.monitor.catalogSession != control.ref || doc.monitor.catalogPermissionRevision != control.permission.revision))) {
            val catalogIntent = doc.monitor.catalogRetries[childName]?.takeIf(::eligible)
            val children = try { api.catalog(session) } catch (e: TodoApiException) {
                currentCoroutineContext().ensureActive()
                val failed = store.updateMonitor { if (valid(it)) it.monitor.copy(catalogAttempted = true, catalogFailure = e.kind, catalogFailureSession = control.ref, catalogFailures = it.monitor.catalogFailures + (childName to e.kind), catalogFailureSessions = it.monitor.catalogFailureSessions + (childName to requireNotNull(control.ref)), catalogRetries = if (catalogIntent != null && it.monitor.catalogRetries[childName] == catalogIntent) it.monitor.catalogRetries - childName else it.monitor.catalogRetries) else null }
                if (failed != null && valid(store.read())) publish(failed, control.permission)
                return
            }
            currentCoroutineContext().ensureActive()
            doc = store.updateMonitor { current ->
                if (!valid(current)) null else current.monitor.copy(catalogAttempted = true, catalogFailure = null, catalogFailureSession = null, catalogFailures = current.monitor.catalogFailures - childName, catalogFailureSessions = current.monitor.catalogFailureSessions - childName, catalogRetries = if (catalogIntent != null && current.monitor.catalogRetries[childName] == catalogIntent) current.monitor.catalogRetries - childName else current.monitor.catalogRetries,
                    catalogRegistrations = current.monitor.catalogRegistrations + (childName to control.registration), catalogOwner = control.owner, catalogRegistration = control.registration, catalogSession = control.ref,
                    catalogPermissionRevision = control.permission.revision, children = children)
            } ?: return
            if (valid(store.read())) publish(doc, control.permission)
        }
        if (failure(doc.monitor) != null) return
        val child = doc.monitor.children.firstOrNull { it.name == control.child } ?: return
        doc = store.updateMonitor { recordZeros(it, child) } ?: return
        for ((subject, id) in child.userIds) {
            if (regularSubject != null && subject != regularSubject) continue
            doc = store.read()
            if (doc.monitor.authenticationRequired(control.ref)) return
            val key = child.name to subject
            val goal = control.goals[key] ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            if (!valid(doc, subject)) {
                if (regularSubject == null && valid(doc)) {
                    if (goal == 0) continue
                    restartInitial.set(true)
                }
                return
            }
            val previous = doc.monitor.reports[key]
            if (regularSubject == null && (goal == 0 || (wasZeroInitial(doc.monitor, key) && !reportRetry(doc.monitor, key)) || (previous?.failed == true && previous.initialFailure != false && !reportRetry(doc.monitor, key) && (doc.monitor.reportFailureSessions[key] == null || doc.monitor.reportFailureSessions[key] == control.ref)) || (!reportRetry(doc.monitor, key) && (previous?.failed != true || previous.initialFailure == false) && previous?.date == date && doc.monitor.baselineEpochs[key] == control.owner && doc.monitor.baselineRegistrations[key] == control.registration && doc.monitor.baselineSessions[key] == control.ref &&
                    doc.monitor.baselinePermissions[key] == control.permission.revision))) continue
            val reportIntent = doc.monitor.reportRetries[key]?.takeIf(::eligible)
            val result = try { ReportState(api.report(session, subject, id, date), now(), date) }
                catch (e: TodoApiException) { (previous ?: ReportState(date = date)).copy(failed = true, failure = e.kind, initialFailure = regularSubject == null) }
            currentCoroutineContext().ensureActive()
            var achieved = false
            val committed = store.updateMonitor { current ->
                val old = current.monitor.reports[key]
                achieved = regularSubject != null && !result.failed && result.value != null &&
                    old?.value != null && old.date == date && old.value.count < goal && result.value.count >= goal &&
                    current.monitor.baselineEpochs[key] == control.owner && current.monitor.baselineRegistrations[key] == control.registration &&
                    current.monitor.baselineSessions[key] == control.ref && current.monitor.baselinePermissions[key] == control.permission.revision
                if (!valid(current, subject)) null else current.monitor.copy(reports = current.monitor.reports + (key to result),
                    reportVersions = current.monitor.reportVersions + (key to Math.addExact(current.monitor.reportVersions[key] ?: 0, 1)),
                    latestAchievements = if (achieved) current.monitor.latestAchievements + ((requireNotNull(control.account) to child.name) to subject) else current.monitor.latestAchievements,
                    reportRetries = if (reportIntent != null && current.monitor.reportRetries[key] == reportIntent) current.monitor.reportRetries - key else current.monitor.reportRetries,
                    reportFailureSessions = if (result.failed) current.monitor.reportFailureSessions + (key to requireNotNull(control.ref)) else current.monitor.reportFailureSessions - key,
                    baselineEpochs = if (result.failed) current.monitor.baselineEpochs else current.monitor.baselineEpochs + (key to control.owner),
                    baselineRegistrations = if (result.failed) current.monitor.baselineRegistrations else current.monitor.baselineRegistrations + (key to control.registration),
                    baselineSessions = if (result.failed) current.monitor.baselineSessions else current.monitor.baselineSessions + (key to requireNotNull(control.ref)),
                    baselinePermissions = if (result.failed) current.monitor.baselinePermissions else current.monitor.baselinePermissions + (key to control.permission.revision))
            }
            if (committed == null) {
                if (regularSubject == null && (store.read().monitor.goalVersions[control.account].orEmpty()[key] ?: 0) != (control.goalVersions[key] ?: 0)) restartInitial.set(true)
                return
            }
            doc = committed
            if (achieved) {
                currentCoroutineContext().ensureActive()
                val version = doc.monitor.reportVersions[key]
                achievement(doc, control.permission, child.name, subject) {
                    currentCoroutineContext().ensureActive()
                    val latest = store.read()
                    valid(latest, subject) && latest.monitor.reportVersions[key] == version
                }
            } else if (valid(store.read())) publish(doc, control.permission)
            if (result.failure == LearningFailure.AUTHENTICATION) return
        }
    }
}
