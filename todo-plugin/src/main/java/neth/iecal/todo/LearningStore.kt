package neth.iecal.todo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class MonitorAccountSnapshot(
    val catalogFailure: LearningFailure? = null,
    val catalogFailureSession: String? = null,
    val catalogAttempted: Boolean = false,
    val children: List<TodoChild> = emptyList(),
    val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(),
    val reportFailureSessions: Map<Pair<String, Subject>, String> = emptyMap(),
    val registrations: Map<String, Long> = emptyMap(),
    val catalogFailures: Map<String, LearningFailure> = emptyMap(),
    val catalogFailureSessions: Map<String, String> = emptyMap(),
    val catalogRegistrations: Map<String, Long> = emptyMap(),
    val catalogCheckedAt: Long? = null,
    val manualCatalogOrder: Long? = null,
    val monitorCatalogOrder: Long? = null
)

data class RetryIntent(val registration: Long, val revision: Long, val sessionRef: String)

data class ZeroGoalInitial(val owner: Long, val registration: Long, val sessionRef: String, val permissionRevision: Long, val date: String, val dateEpoch: Long = 0)

data class MonitorState(
    val ownerEpoch: Long = 0,
    val controlRevision: Long = 0,
    val goalVersions: Map<String, Map<Pair<String, Subject>, Long>> = emptyMap(),
    val zeroGoalInitials: Map<Pair<String, Subject>, ZeroGoalInitial> = emptyMap(),
    val registrationEpoch: Long = 0,
    val registrations: Map<String, Long> = emptyMap(),
    val accounts: Map<String, MonitorAccountSnapshot> = emptyMap(),
    val intervalMinutes: String = "1",
    val cycleOwner: Long = 0,
    val cycleStartedElapsedMillis: Long = 0,
    val accountId: String? = null,
    val childName: String? = null,
    val catalogFailure: LearningFailure? = null,
    val catalogFailureSession: String? = null,
    val catalogFailures: Map<String, LearningFailure> = emptyMap(),
    val catalogFailureSessions: Map<String, String> = emptyMap(),
    val catalogRetryChild: String? = null,
    val catalogRegistrations: Map<String, Long> = emptyMap(),
    val catalogAttempted: Boolean = false,
    val catalogOwner: Long = 0,
    val catalogRegistration: Long = 0,
    val catalogSession: String? = null,
    val catalogPermissionRevision: Long = 0,
    val catalogCheckedAt: Long? = null,
    val manualCatalogOrder: Long? = null,
    val monitorCatalogOrder: Long? = null,
    val children: List<TodoChild> = emptyList(),
    val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(),
    val reportFailureSessions: Map<Pair<String, Subject>, String> = emptyMap(),
    val baselineEpochs: Map<Pair<String, Subject>, Long> = emptyMap(),
    val baselineRegistrations: Map<Pair<String, Subject>, Long> = emptyMap(),
    val baselineSessions: Map<Pair<String, Subject>, String> = emptyMap(),
    val baselinePermissions: Map<Pair<String, Subject>, Long> = emptyMap(),
    val reportVersions: Map<Pair<String, Subject>, Long> = emptyMap(),
    val latestAchievements: Map<Pair<String, String>, Subject> = emptyMap(),
    val catalogRetries: Map<String, RetryIntent> = emptyMap(),
    val reportRetries: Map<Pair<String, Subject>, RetryIntent> = emptyMap(),
    val retryRevision: Long = 0,
    val catalogRetryRequested: Boolean = false,
    val reportRetryRequested: Pair<String, Subject>? = null
)

/** Discards completion baselines without clearing historical reports or retry latches. */
fun MonitorState.resetLearningBaselines(): MonitorState = copy(
    cycleOwner = 0,
    baselineEpochs = emptyMap(),
    baselineRegistrations = emptyMap(),
    baselineSessions = emptyMap(),
    baselinePermissions = emptyMap(),
    latestAchievements = emptyMap()
)

data class StoredLearning(val revision: Long = 0, val state: LearningState = LearningState(), val sessionRef: String? = null, val monitor: MonitorState = MonitorState())

/** Storage transactions reject effects owned by a superseded request. */
interface LearningStore {
    val changes: Flow<StoredLearning>
    fun read(): StoredLearning
    fun claim(): StoredLearning
    /** Set catalogSuccess only for a successful full catalog; its ordering is stamped atomically. */
    fun commit(revision: Long, state: LearningState, sessionRef: String?, expectedSessionRef: String? = null, catalogSuccess: Boolean = false): Boolean
    /** Applies a monitor change atomically against the latest document. Null rejects the update. */
    fun updateMonitor(change: (StoredLearning) -> MonitorState?): StoredLearning?
    fun readSession(ref: String): TodoSession
    fun writeSession(ref: String, session: TodoSession)
}

class MemoryLearningStore : LearningStore {
    private var document = StoredLearning()
    private val updates = MutableStateFlow(document)
    override val changes: Flow<StoredLearning> = updates
    private val sessions = mutableMapOf<String, TodoSession>()
    @Synchronized override fun read() = document
    @Synchronized override fun claim(): StoredLearning { document = document.copy(revision = document.revision + 1); updates.value = document; return document }
    @Synchronized override fun commit(revision: Long, state: LearningState, sessionRef: String?, expectedSessionRef: String?, catalogSuccess: Boolean): Boolean {
        if (document.revision != revision || !document.acceptsApiResult(expectedSessionRef)) return false
        document = document.withManualState(state, sessionRef, catalogSuccess)
        updates.value = document
        return true
    }
    @Synchronized override fun updateMonitor(change: (StoredLearning) -> MonitorState?): StoredLearning? {
        val monitor = change(document) ?: return null
        document = document.copy(monitor = monitor)
        updates.value = document
        return document
    }
    @Synchronized override fun readSession(ref: String) = requireNotNull(sessions[ref])
    @Synchronized override fun writeSession(ref: String, session: TodoSession) { sessions[ref] = session }
}

/** Registrations persisted before multiple children were supported remain active. */
fun MonitorState.registeredChildren(): Map<String, Long> = if (registrations.isEmpty() && childName != null) mapOf(childName to registrationEpoch) else registrations

fun MonitorState.catalogFailureFor(child: String): LearningFailure? = catalogFailures[child] ?: if (catalogFailures.isEmpty() && childName == child) catalogFailure else null
fun MonitorState.catalogFailureSessionFor(child: String): String? = catalogFailureSessions[child] ?: if (catalogFailures.isEmpty() && childName == child) catalogFailureSession else null

/** Converts the former single retry slots once, before they can be canceled or replaced. */
fun MonitorState.migrateRetries(sessionRef: String?): MonitorState {
    if (!catalogRetryRequested && reportRetryRequested == null) return this
    val registered = registeredChildren()
    val child = catalogRetryChild ?: childName
    val catalog = if (catalogRetryRequested && child != null && sessionRef != null && registered[child] != null && child !in catalogRetries)
        catalogRetries + (child to RetryIntent(registered.getValue(child), retryRevision, sessionRef)) else catalogRetries
    val key = reportRetryRequested
    val reports = if (key != null && sessionRef != null && registered[key.first] != null && key !in reportRetries)
        reportRetries + (key to RetryIntent(registered.getValue(key.first), retryRevision, sessionRef)) else reportRetries
    return copy(catalogRetries = catalog, reportRetries = reports, catalogRetryRequested = false, catalogRetryChild = null, reportRetryRequested = null)
}

/** A rejected session pauses every registration until the user signs in again. */
fun MonitorState.authenticationRequired(sessionRef: String?): Boolean {
    if (sessionRef == null) return true
    if (catalogFailure == LearningFailure.AUTHENTICATION && (catalogFailureSession == null || catalogFailureSession == sessionRef)) return true
    if (catalogFailures.any { (child, failure) -> failure == LearningFailure.AUTHENTICATION && (catalogFailureSessions[child] == null || catalogFailureSessions[child] == sessionRef) }) return true
    return reports.any { (key, report) -> report.failure == LearningFailure.AUTHENTICATION && (reportFailureSessions[key] == null || reportFailureSessions[key] == sessionRef) }
}

/** Commits manual state while fencing only goals whose effective value changed. */
fun StoredLearning.withManualState(next: LearningState, ref: String?, catalogSuccess: Boolean = false): StoredLearning {
    var versions = monitor.goalVersions
    var retries = monitor.migrateRetries(sessionRef).reportRetries
    for (account in state.goalsByAccount.keys + next.goalsByAccount.keys) {
        val before = state.goalsByAccount[account].orEmpty()
        val after = next.goalsByAccount[account].orEmpty()
        var scoped = versions[account].orEmpty()
        for (key in before.keys + after.keys) {
            val default = when (key.second) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            if ((before[key] ?: default) != (after[key] ?: default)) {
                scoped = scoped + (key to Math.addExact(scoped[key] ?: 0, 1))
            }
        }
        versions = versions + (account to scoped)
    }
    val migrated = monitor.migrateRetries(sessionRef)
    var updated = migrated.copy(goalVersions = versions, reportRetries = retries)
    if (ref != null && ref != sessionRef && next.accountId != null) {
        if (updated.accountId != next.accountId) {
            val accounts = updated.accountId?.let { account ->
                updated.accounts + (account to MonitorAccountSnapshot(updated.catalogFailure, updated.catalogFailureSession, updated.catalogAttempted,
                    updated.children, updated.reports, updated.reportFailureSessions, updated.registeredChildren(), updated.catalogFailures,
                    updated.catalogFailureSessions, updated.catalogRegistrations, updated.catalogCheckedAt, updated.manualCatalogOrder, updated.monitorCatalogOrder))
            } ?: updated.accounts
            val saved = accounts[next.accountId] ?: MonitorAccountSnapshot()
            updated = updated.copy(accounts = accounts, accountId = next.accountId, childName = saved.registrations.keys.firstOrNull(),
                registrations = saved.registrations, catalogFailure = saved.catalogFailure, catalogFailureSession = saved.catalogFailureSession,
                catalogFailures = saved.catalogFailures, catalogFailureSessions = saved.catalogFailureSessions, catalogRegistrations = saved.catalogRegistrations,
                catalogAttempted = saved.catalogAttempted, catalogCheckedAt = saved.catalogCheckedAt, manualCatalogOrder = saved.manualCatalogOrder, monitorCatalogOrder = saved.monitorCatalogOrder, children = saved.children, reports = saved.reports,
                reportFailureSessions = saved.reportFailureSessions, zeroGoalInitials = emptyMap(), reportVersions = emptyMap())
        }
        updated = updated.resetLearningBaselines().copy(
            catalogFailure = null, catalogFailureSession = null, catalogFailures = emptyMap(), catalogFailureSessions = emptyMap(),
            reports = updated.reports.mapValues { (_, report) -> if (report.initialFailure != false || report.failure == LearningFailure.AUTHENTICATION) report.copy(failed = false, failure = null, initialFailure = null) else report },
            reportFailureSessions = emptyMap(), catalogRetries = emptyMap(), reportRetries = emptyMap(),
            catalogAttempted = false, catalogRetryRequested = false, catalogRetryChild = null, reportRetryRequested = null,
            zeroGoalInitials = emptyMap(), controlRevision = Math.addExact(updated.controlRevision, 1))
    }
    if (catalogSuccess) updated = updated.copy(manualCatalogOrder = Math.addExact(maxOf(updated.manualCatalogOrder ?: 0, updated.monitorCatalogOrder ?: 0), 1))
    return copy(state = next, sessionRef = ref, monitor = updated)
}

/** API results cannot commit after their session is replaced or rejected. */
fun StoredLearning.acceptsApiResult(expectedRef: String?): Boolean = expectedRef == null ||
    (sessionRef == expectedRef && (monitor.accountId != state.accountId || !monitor.authenticationRequired(expectedRef)))
