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
    val catalogRegistrations: Map<String, Long> = emptyMap()
)

data class MonitorState(
    val ownerEpoch: Long = 0,
    val controlRevision: Long = 0,
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
    val children: List<TodoChild> = emptyList(),
    val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(),
    val reportFailureSessions: Map<Pair<String, Subject>, String> = emptyMap(),
    val baselineEpochs: Map<Pair<String, Subject>, Long> = emptyMap(),
    val baselineRegistrations: Map<Pair<String, Subject>, Long> = emptyMap(),
    val baselineSessions: Map<Pair<String, Subject>, String> = emptyMap(),
    val baselinePermissions: Map<Pair<String, Subject>, Long> = emptyMap(),
    val baselineGoals: Map<Pair<String, Subject>, Int> = emptyMap(),
    val reportVersions: Map<Pair<String, Subject>, Long> = emptyMap(),
    val latestAchievements: Map<Pair<String, String>, Subject> = emptyMap(),
    val retryRevision: Long = 0,
    val catalogRetryRequested: Boolean = false,
    val reportRetryRequested: Pair<String, Subject>? = null
)

data class StoredLearning(val revision: Long = 0, val state: LearningState = LearningState(), val sessionRef: String? = null, val monitor: MonitorState = MonitorState())

/** Storage transactions reject effects owned by a superseded request. */
interface LearningStore {
    val changes: Flow<StoredLearning>
    fun read(): StoredLearning
    fun claim(): StoredLearning
    fun commit(revision: Long, state: LearningState, sessionRef: String?): Boolean
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
    @Synchronized override fun commit(revision: Long, state: LearningState, sessionRef: String?): Boolean {
        if (document.revision != revision) return false
        document = document.copy(state = state, sessionRef = sessionRef)
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
