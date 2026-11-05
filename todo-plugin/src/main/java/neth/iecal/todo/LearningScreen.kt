package neth.iecal.todo

import java.util.UUID

data class ReportState(val value: DailyReport? = null, val checkedAt: Long? = null, val date: String? = null, val failed: Boolean = false, val failure: LearningFailure? = null)
data class LearningState(val children: List<TodoChild> = emptyList(), val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(), val error: Boolean = false, val loginRequired: Boolean = false, val catalogCheckedAt: Long? = null, val failure: LearningFailure? = null, val accountId: String? = null, val goalsByAccount: Map<String, Map<Pair<String, Subject>, Int>> = emptyMap())

enum class GoalStatus { UNKNOWN, INCOMPLETE, COMPLETE }

/** Synchronous screen operations; callers run them off the main thread. */
class LearningScreen(private val api: TodoApi, private val store: LearningStore = MemoryLearningStore(), private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var state = LearningState()
        private set
    @Volatile var selectedDate: String? = null
        private set
    private val lock = Any()
    private var generation = 0L
    private var session: TodoSession? = null
    private var sessionRef: String? = null

    fun selectDate(date: String) = synchronized(lock) { selectedDate = date; generation++ }

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

    fun saveGoal(childName: String, subject: Subject, input: String): Boolean {
        val trimmed = input.trim()
        if (!trimmed.matches(Regex("[0-9]+"))) return false
        val value = trimmed.toIntOrNull()?.takeIf { it in 0..99 } ?: return false
        synchronized(lock) {
            if (state.accountId == null || state.children.none { it.name == childName && subject in it.userIds }) return false
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

    fun storageFailure() { synchronized(lock) { session = null; sessionRef = null; generation++; state = state.copy(error = true, failure = LearningFailure.STORAGE) } }

    fun restore() {
        try {
            val saved = store.read()
            val savedSession = saved.sessionRef?.let { store.readSession(it) }
            synchronized(lock) {
                val scoped = !saved.state.accountId.isNullOrBlank() && (savedSession == null || savedSession.accountId == saved.state.accountId)
                state = if (scoped) saved.state else LearningState(loginRequired = true)
                session = if (scoped) savedSession else null
                sessionRef = if (scoped) saved.sessionRef else null
            }
        } catch (e: Exception) { storageFailure() }
    }

    fun signIn(email: String, password: String, date: String, onlyIfSelected: Boolean = false) {
        val local = synchronized(lock) { if (onlyIfSelected && selectedDate != date) return; selectedDate = date; session = null; sessionRef = null; ++generation }
        val claim = store.claim()
        try {
            val authenticated = api.signIn(email, password)
            val ref = UUID.randomUUID().toString()
            store.writeSession(ref, authenticated)
            synchronized(lock) {
                if (local != generation) return
                val previous = if (!claim.state.accountId.isNullOrBlank() && claim.state.accountId == authenticated.accountId) claim.state else LearningState(goalsByAccount = claim.state.goalsByAccount)
                val next = previous.copy(error = false, loginRequired = false, failure = null, accountId = authenticated.accountId)
                if (!store.commit(claim.revision, next, ref)) return
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

    fun refresh(date: String, onlyIfSelected: Boolean = false) {
        val local: Long
        val current: TodoSession
        val ref: String
        synchronized(lock) { if (onlyIfSelected && selectedDate != date) return; selectedDate = date; current = session ?: return; ref = sessionRef ?: return; local = ++generation }
        val claim = store.claim()
        if (claim.sessionRef != ref) { restore(); return }
        refreshForRequest(date, current, local, claim.revision, ref, false)
    }

    fun retryReport(childName: String, subject: Subject, date: String) {
        val local: Long
        val current: TodoSession
        val ref: String
        val userId: String
        synchronized(lock) {
            if (goal(childName, subject) == 0) return
            selectedDate = date
            current = session ?: return
            ref = sessionRef ?: return
            userId = state.children.firstOrNull { it.name == childName }?.userIds?.get(subject) ?: return
            local = ++generation
        }
        val claim = store.claim()
        if (claim.sessionRef != ref) { restore(); return }
        var authFailure = false
        val key = childName to subject
        val result = try { ReportState(api.report(current, subject, userId, date), now(), date) }
        catch (e: TodoApiException) {
            authFailure = e.authenticationRequired
            synchronized(lock) { (state.reports[key] ?: ReportState(date = date)).copy(failed = true, failure = e.kind) }
        }
        synchronized(lock) {
            if (local != generation) return
            val next = state.copy(reports = state.reports + (key to result), loginRequired = authFailure)
            val nextRef = if (authFailure) null else ref
            if (!store.commit(claim.revision, next, nextRef)) return
            state = next
            if (authFailure) { session = null; sessionRef = null }
        }
    }

    private fun refreshForRequest(date: String, current: TodoSession, local: Long, revision: Long, ref: String, includeFailed: Boolean) {
        val children = try { api.catalog(current) } catch (e: TodoApiException) {
            synchronized(lock) {
                if (local != generation) return
                val next = state.copy(error = true, loginRequired = e.authenticationRequired, failure = e.kind)
                val nextRef = if (e.authenticationRequired) null else ref
                if (store.commit(revision, next, nextRef)) { state = next; if (nextRef == null) { session = null; sessionRef = null } }
            }
            return
        }
        synchronized(lock) {
            if (local != generation) return
            val next = state.copy(children = children, error = false, loginRequired = false, catalogCheckedAt = now(), failure = null)
            if (!store.commit(revision, next, ref)) return
            state = next
        }
        for (child in children) for ((subject, id) in child.userIds) {
            val key = child.name to subject
            if (goal(child.name, subject) == 0 || (!includeFailed && state.reports[key]?.failed == true)) continue
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
                if (local != generation) return
                val next = state.copy(reports = state.reports + (key to result), loginRequired = authFailure)
                val nextRef = if (authFailure) null else ref
                if (!store.commit(revision, next, nextRef)) return
                state = next
                if (authFailure) { session = null; sessionRef = null; return }
            }
        }
    }
}





