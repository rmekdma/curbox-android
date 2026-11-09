package neth.iecal.todo

import java.util.UUID

data class ReportState(val value: DailyReport? = null, val checkedAt: Long? = null, val date: String? = null, val failed: Boolean = false, val failure: LearningFailure? = null, val initialFailure: Boolean? = null)
data class LearningState(val children: List<TodoChild> = emptyList(), val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(), val error: Boolean = false, val loginRequired: Boolean = false, val catalogCheckedAt: Long? = null, val failure: LearningFailure? = null, val accountId: String? = null, val goalsByAccount: Map<String, Map<Pair<String, Subject>, Int>> = emptyMap())

enum class GoalStatus { UNKNOWN, INCOMPLETE, COMPLETE }

/** Account and subject identities captured when goal settings are opened. */
data class LearningGoalScope(val accountId: String, val sessionRef: String, val childName: String, val subjects: Map<Subject, String>)

/** Failure scope authorized by one explicit dashboard refresh. */
data class LearningRetryScope(val accountId: String, val sessionRef: String, val monitor: MonitorState)

/** Synchronous screen operations; callers run them off the main thread. */
class LearningScreen(private val api: TodoApi, private val store: LearningStore = MemoryLearningStore(), private val isCurrentDate: ((String) -> Boolean)? = null, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var state = LearningState()
        private set
    @Volatile var monitor = MonitorState()
        private set
    private fun dateIsCurrent(date: String): Boolean = isCurrentDate?.invoke(date)
        ?: (date == java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))

    private val lock = Any()
    private var generation = 0L
    private var session: TodoSession? = null
    private var sessionRef: String? = null

    fun goal(childName: String, subject: Subject): Int = state.goalsByAccount[state.accountId]?.get(childName to subject) ?: when (subject) {
        Subject.KOREAN -> 6
        Subject.MATH -> 5
        Subject.ENGLISH -> 8
    }

    fun goalStatus(childName: String, subject: Subject, date: String): GoalStatus {
        val target = goal(childName, subject)
        if (target == 0) return GoalStatus.COMPLETE
        val report = state.reports[childName to subject]
        val count = report?.value?.takeIf { report.date == date }?.count ?: return GoalStatus.UNKNOWN
        return if (count >= target) GoalStatus.COMPLETE else GoalStatus.INCOMPLETE
    }

    fun monitorGoalStatus(childName: String, subject: Subject, date: String): GoalStatus {
        val target = goal(childName, subject)
        if (target == 0) return GoalStatus.COMPLETE
        val report = monitor.reports[childName to subject]
        val count = report?.value?.takeIf { report.date == date }?.count ?: return GoalStatus.UNKNOWN
        return if (count >= target) GoalStatus.COMPLETE else GoalStatus.INCOMPLETE
    }

    fun saveInterval(input: String): Boolean {
        val value = input.trim()
        if (!value.matches(Regex("[0-9]+")) || value.all { it == '0' }) return false
        val normalized = value.trimStart('0')
        val updated = store.updateMonitor { it.monitor.copy(intervalMinutes = normalized) } ?: return false
        monitor = updated.monitor
        return true
    }

    fun saveLearningSettings(seconds: String, alerts: Boolean, sound: String?): Boolean {
        val value = seconds.trim()
        if (!value.matches(Regex("[0-9]+")) || value.all { it == '0' }) return false
        val updated = store.updateMonitor {
            val current = it.monitor
            val revision = if ((current.completionAlerts ?: true) != alerts || current.alertSound != sound) Math.addExact(current.alertSettingsRevision ?: 0, 1) else current.alertSettingsRevision
            current.copy(intervalSeconds = value.trimStart('0'), completionAlerts = alerts, alertSound = sound, alertSettingsRevision = revision)
        } ?: return false
        monitor = updated.monitor
        return true
    }

    fun visibleSubjects(childName: String): Set<Subject> {
        return learningSubjects(state, monitor, childName).keys
    }

    fun saveGoal(childName: String, subject: Subject, input: String): Boolean {
        val trimmed = input.trim()
        if (!trimmed.matches(Regex("[0-9]+"))) return false
        val value = trimmed.toIntOrNull()?.takeIf { it in 0..99 } ?: return false
        synchronized(lock) {
            if (state.accountId == null || subject !in visibleSubjects(childName)) return false
            generation++
            val claim = store.claim()
            if (claim.state.accountId != state.accountId || claim.sessionRef != sessionRef) return false
            val account = requireNotNull(claim.state.accountId)
            val goals = claim.state.goalsByAccount[account].orEmpty() + ((childName to subject) to value)
            val next = claim.state.copy(goalsByAccount = claim.state.goalsByAccount + (account to goals))
            if (!store.commit(claim.revision, next, claim.sessionRef)) return false
            state = next
            return true
        }
    }

    private fun subjectIds(state: LearningState, monitor: MonitorState, childName: String): Map<Subject, String> {
        return learningSubjects(state, monitor, childName)
    }

    fun goalScope(childName: String): LearningGoalScope? = synchronized(lock) {
        val account = state.accountId ?: return@synchronized null
        val ref = sessionRef ?: return@synchronized null
        if (session == null || loginRequired) return@synchronized null
        val subjects = subjectIds(state, monitor, childName)
        if (learningCatalog(state, monitor).none { it.name == childName }) return@synchronized null
        LearningGoalScope(account, ref, childName, subjects)
    }

    /** Saves every goal together only if the original account, session and subjects still match. */
    fun saveGoals(scope: LearningGoalScope, inputs: Map<Subject, String>): Boolean {
        if (inputs.keys != scope.subjects.keys) return false
        val values = inputs.mapValues { (_, input) ->
            val value = input.trim()
            if (!value.matches(Regex("[0-9]+"))) return false
            value.toIntOrNull()?.takeIf { it in 0..99 } ?: return false
        }
        synchronized(lock) {
            if (sessionRef != scope.sessionRef || state.accountId != scope.accountId || session == null || loginRequired) return false
            val claim = store.claim()
            if (claim.sessionRef != scope.sessionRef || claim.state.accountId != scope.accountId ||
                learningCatalog(claim.state, claim.monitor).none { it.name == scope.childName } ||
                subjectIds(claim.state, claim.monitor, scope.childName) != scope.subjects ||
                (claim.monitor.accountId == scope.accountId && claim.monitor.authenticationRequired(scope.sessionRef))) return false
            val goals = claim.state.goalsByAccount[scope.accountId].orEmpty() + values.mapKeys { scope.childName to it.key }
            val next = claim.state.copy(goalsByAccount = claim.state.goalsByAccount + (scope.accountId to goals))
            generation++
            if (!store.commit(claim.revision, next, scope.sessionRef, scope.sessionRef)) return false
            state = next
            return true
        }
    }

    val monitorChanges get() = store.changes
    fun acceptMonitor(value: MonitorState) { monitor = value }

    val loginRequired: Boolean get() = state.loginRequired || (monitor.accountId == state.accountId && sessionRef != null && monitor.authenticationRequired(sessionRef))
    internal val signedIn: Boolean get() = synchronized(lock) { session != null && sessionRef != null && !loginRequired }

    fun canRetryMonitor(childName: String, subject: Subject?): Boolean {
        if (loginRequired) return false
        val failure = if (subject == null) monitor.catalogFailureFor(childName) else monitor.reports[childName to subject]?.failure
        val failedRef = if (subject == null) monitor.catalogFailureSessionFor(childName) else monitor.reportFailureSessions[childName to subject]
        return failure != LearningFailure.AUTHENTICATION || (sessionRef != null && sessionRef != failedRef)
    }

    fun retryScope(): LearningRetryScope? = synchronized(lock) {
        val account = state.accountId ?: return@synchronized null
        val ref = sessionRef ?: return@synchronized null
        if (monitor.accountId != account || loginRequired) return@synchronized LearningRetryScope(account, ref, MonitorState())
        LearningRetryScope(account, ref, monitor)
    }

    fun retryFailures(scope: LearningRetryScope) {
        val snapshot = scope.monitor
        for ((child, registration) in snapshot.registeredChildren()) {
            if (snapshot.catalogFailureFor(child) != null) retryMonitor(child, null, scope, registration)
            snapshot.reports.forEach { (key, report) ->
                if (key.first == child && report.failed) retryMonitor(child, key.second, scope, registration)
            }
        }
    }

    fun retryMonitor(childName: String, subject: Subject?, scope: LearningRetryScope? = null, registration: Long? = null) {
        val updated = store.updateMonitor { doc ->
            if (scope != null && (doc.state.accountId != scope.accountId || doc.sessionRef != scope.sessionRef ||
                    doc.monitor.registeredChildren()[childName] != registration || doc.monitor.accountId != scope.accountId)) return@updateMonitor null
            if (scope != null) {
                val old = scope.monitor
                val sameFailure = if (subject == null) old.catalogFailureFor(childName) == doc.monitor.catalogFailureFor(childName) &&
                    old.catalogFailureSessionFor(childName) == doc.monitor.catalogFailureSessionFor(childName)
                else old.reports[childName to subject] == doc.monitor.reports[childName to subject] &&
                    old.reportVersions[childName to subject] == doc.monitor.reportVersions[childName to subject] &&
                    old.reportFailureSessions[childName to subject] == doc.monitor.reportFailureSessions[childName to subject]
                if (!sameFailure) return@updateMonitor null
            }
            val failure = if (subject == null) doc.monitor.catalogFailureFor(childName) else doc.monitor.reports[childName to subject]?.failure
            val failedRef = if (subject == null) doc.monitor.catalogFailureSessionFor(childName) else doc.monitor.reportFailureSessions[childName to subject]
            if (doc.monitor.authenticationRequired(doc.sessionRef) || doc.sessionRef == null || doc.monitor.accountId != doc.state.accountId || childName !in doc.monitor.registeredChildren() ||
                (failure == LearningFailure.AUTHENTICATION && doc.sessionRef == failedRef)) null
            else {
                val old = doc.monitor.migrateRetries(doc.sessionRef)
                val key = subject?.let { childName to it }
                if (key == null && old.catalogFailureFor(childName) == null || key != null && old.reports[key]?.failed != true) null
                else {
                    val revision = Math.addExact(old.retryRevision, 1)
                    val intent = RetryIntent(old.registeredChildren().getValue(childName), revision, requireNotNull(doc.sessionRef))
                    old.copy(
                    catalogRetries = if (key == null) old.catalogRetries + (childName to intent) else old.catalogRetries,
                    reportRetries = if (key != null) old.reportRetries + (key to intent) else old.reportRetries,
                    retryRevision = revision,
                    controlRevision = Math.addExact(old.controlRevision, 1)
                )
                }
            }
        }
        if (updated != null) monitor = updated.monitor
    }

    fun toggleMonitor(childName: String) {
        val updated = store.updateMonitor { doc ->
            val account = doc.state.accountId
            if (account == null || doc.sessionRef == null || learningCatalog(doc.state, doc.monitor).none { it.name == childName }) null
            else {
                val savedMonitor = doc.monitor.migrateRetries(doc.sessionRef)
                val legacyChild = savedMonitor.childName
                val legacyFailure = savedMonitor.catalogFailure
                val old = if (legacyChild != null && legacyFailure != null && savedMonitor.catalogFailures.isEmpty()) {
                    savedMonitor.copy(catalogFailures = mapOf(legacyChild to legacyFailure),
                        catalogFailureSessions = savedMonitor.catalogFailureSession?.let { mapOf(legacyChild to it) }.orEmpty())
                } else savedMonitor
                val accounts = old.accountId?.let { old.accounts + (it to MonitorAccountSnapshot(old.catalogFailure, old.catalogFailureSession, old.catalogAttempted, old.children, old.reports, old.reportFailureSessions, old.registeredChildren(), old.catalogFailures, old.catalogFailureSessions, old.catalogRegistrations, old.catalogCheckedAt, old.manualCatalogOrder, old.monitorCatalogOrder)) } ?: old.accounts
                val previous = if (old.accountId == account) old else {
                    val saved = accounts[account] ?: MonitorAccountSnapshot()
                    old.copy(accountId = account, childName = null, registrations = saved.registrations, catalogFailures = saved.catalogFailures, catalogFailureSessions = saved.catalogFailureSessions, catalogRegistrations = saved.catalogRegistrations, catalogFailure = saved.catalogFailure, catalogFailureSession = saved.catalogFailureSession,
                        catalogAttempted = saved.catalogAttempted, catalogCheckedAt = saved.catalogCheckedAt, manualCatalogOrder = saved.manualCatalogOrder, monitorCatalogOrder = saved.monitorCatalogOrder, children = saved.children, reports = saved.reports, reportFailureSessions = saved.reportFailureSessions,
                        baselineEpochs = emptyMap(), baselineRegistrations = emptyMap(), baselineSessions = emptyMap(), baselinePermissions = emptyMap(),
                        catalogRetryRequested = false, catalogRetryChild = null, reportRetryRequested = null,
                        catalogRetries = emptyMap(), reportRetries = emptyMap())
                }
                val epoch = Math.addExact(old.registrationEpoch, 1)
                val registrations = previous.registeredChildren().let { if (childName in it) it - childName else it + (childName to epoch) }
                previous.copy(accounts = accounts, accountId = account, childName = registrations.keys.firstOrNull(), registrations = registrations,
                    latestAchievements = previous.latestAchievements - (account to childName),
                    children = if (previous.children.isEmpty()) doc.state.children else previous.children,
                    catalogRetries = previous.catalogRetries - childName,
                    reportRetries = previous.reportRetries.filterKeys { it.first != childName },
                    registrationEpoch = epoch, controlRevision = Math.addExact(old.controlRevision, 1))
            }
        }
        if (updated != null) monitor = updated.monitor
    }

    fun storageFailure() { synchronized(lock) { session = null; sessionRef = null; generation++; state = state.copy(error = true, failure = LearningFailure.STORAGE) } }

    fun restore() {
        try {
            val saved = store.read()
            val savedSession = saved.sessionRef?.let { store.readSession(it) }
            synchronized(lock) {
                val scoped = !saved.state.accountId.isNullOrBlank() && (savedSession == null || savedSession.accountId == saved.state.accountId)
                state = if (scoped) saved.state else LearningState(loginRequired = true)
                monitor = saved.monitor
                session = if (scoped) savedSession else null
                sessionRef = if (scoped) saved.sessionRef else null
            }
        } catch (e: Exception) { storageFailure() }
    }

    val accountEmail: String? get() = synchronized(lock) { session?.email }

    private fun today(): String = java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    fun signIn(email: String, password: String) {
        val date = today()
        val local = synchronized(lock) { session = null; sessionRef = null; ++generation }
        val claim = store.claim()
        try {
            val authenticated = api.signIn(email, password).copy(email = email)
            val ref = UUID.randomUUID().toString()
            store.writeSession(ref, authenticated)
            synchronized(lock) {
                if (local != generation) return
                val previous = if (!claim.state.accountId.isNullOrBlank() && claim.state.accountId == authenticated.accountId) claim.state else LearningState(goalsByAccount = claim.state.goalsByAccount)
                val next = previous.copy(error = false, loginRequired = false, failure = null, accountId = authenticated.accountId)
                if (!store.commit(claim.revision, next, ref)) return
                monitor = store.read().monitor
                state = next; session = authenticated; sessionRef = ref
            }
            refreshForRequest(date, authenticated, local, claim.revision, ref, true)
        } catch (e: TodoApiException) {
            synchronized(lock) {
                if (local != generation) return
                val previous = if (claim.state.accountId.isNullOrBlank()) LearningState() else claim.state
                val next = previous.copy(error = true, loginRequired = e.authenticationRequired, failure = e.kind)
                if (store.commit(claim.revision, next, null)) state = next
            }
        }
    }

    fun refresh(includeFailed: Boolean = false, expectedSessionRef: String? = null) {
        val date = today()
        val local: Long
        val current: TodoSession
        val ref: String
        synchronized(lock) { if (expectedSessionRef != null && sessionRef != expectedSessionRef) return; current = session ?: return; ref = sessionRef ?: return; local = ++generation }
        val claim = store.claim()
        if (claim.sessionRef != ref) { restore(); return }
        if (claim.monitor.accountId == claim.state.accountId && claim.monitor.authenticationRequired(ref)) { monitor = claim.monitor; return }
        refreshForRequest(date, current, local, claim.revision, ref, includeFailed)
    }

    private fun refreshForRequest(date: String, current: TodoSession, local: Long, revision: Long, ref: String, includeFailed: Boolean) {
        val children = try { api.catalog(current) } catch (e: TodoApiException) {
            synchronized(lock) {
                if (local != generation) return
                val next = state.copy(error = true, loginRequired = e.authenticationRequired, failure = e.kind)
                val nextRef = if (e.authenticationRequired) null else ref
                if (store.commit(revision, next, nextRef, ref)) { state = next; if (nextRef == null) { session = null; sessionRef = null } }
            }
            return
        }
        synchronized(lock) {
            if (local != generation) return
            val next = state.copy(children = children, error = false, loginRequired = false, catalogCheckedAt = now(), failure = null)
            if (!store.commit(revision, next, ref, ref, catalogSuccess = true)) return
            state = next
        }
        for (child in children) for ((subject, id) in child.userIds) {
            val key = child.name to subject
            if (!includeFailed && state.reports[key]?.failed == true) continue
            val latest = store.read()
            if (learningSubjects(latest.state, latest.monitor, child.name)[subject] != id) continue
            if (!dateIsCurrent(date)) return
            var authFailure = false
            val result = try { ReportState(api.report(current, subject, id, date), now(), date) }
            catch (e: TodoApiException) {
                authFailure = e.authenticationRequired
                synchronized(lock) {
                    if (local != generation) return
                    (state.reports[key] ?: ReportState(date = date)).copy(failed = true, failure = e.kind)
                }
            }
            synchronized(lock) {
                if (local != generation || !dateIsCurrent(date)) return
                val next = state.copy(reports = state.reports + (key to result), loginRequired = authFailure)
                val nextRef = if (authFailure) null else ref
                if (!store.commit(revision, next, nextRef, ref)) return
                state = next
                if (authFailure) { session = null; sessionRef = null; return }
            }
        }
    }
}





