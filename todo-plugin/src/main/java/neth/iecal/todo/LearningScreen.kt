package neth.iecal.todo

data class ReportState(val value: DailyReport? = null, val checkedAt: Long? = null, val date: String? = null, val failed: Boolean = false)
data class LearningState(val children: List<TodoChild> = emptyList(), val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(), val error: Boolean = false, val loginRequired: Boolean = false)

/** Synchronous screen operations; callers run them off the main thread. */
class LearningScreen(private val api: TodoApi, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile var state = LearningState()
        private set
    private val lock = Any()
    private var generation = 0L
    private var session: TodoSession? = null

    fun signIn(email: String, password: String, date: String) {
        val request = synchronized(lock) { ++generation }
        try {
            val authenticated = api.signIn(email, password)
            synchronized(lock) {
                if (request != generation) return
                session = authenticated
            }
            refreshForRequest(date, authenticated, request)
        } catch (e: TodoApiException) {
            synchronized(lock) {
                if (request == generation) state = state.copy(error = true, loginRequired = e.authenticationRequired)
            }
        }
    }

    fun refresh(date: String) {
        val request: Long
        val current: TodoSession
        synchronized(lock) {
            current = session ?: return
            request = ++generation
        }
        refreshForRequest(date, current, request)
    }

    private fun refreshForRequest(date: String, current: TodoSession, request: Long) {
        val children = try { api.catalog(current) } catch (e: TodoApiException) {
            synchronized(lock) {
                if (request == generation) state = state.copy(error = true, loginRequired = e.authenticationRequired)
            }
            return
        }
        synchronized(lock) {
            if (request != generation) return
            state = state.copy(children = children, error = false, loginRequired = false)
        }
        for (child in children) for ((subject, id) in child.userIds) {
            val key = child.name to subject
            val result = try { ReportState(api.report(current, subject, id, date), now(), date) }
            catch (e: TodoApiException) {
                synchronized(lock) {
                    if (request != generation) return
                    if (e.authenticationRequired) state = state.copy(loginRequired = true)
                    val previous = state.reports[key]?.takeIf { it.date == date } ?: ReportState(date = date)
                    previous.copy(failed = true)
                }
            }
            synchronized(lock) {
                if (request != generation) return
                state = state.copy(reports = state.reports + (key to result))
            }
        }
    }
}
