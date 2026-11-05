package neth.iecal.todo

data class ReportState(val value: DailyReport? = null, val checkedAt: Long? = null, val date: String? = null, val failed: Boolean = false)
data class LearningState(val children: List<TodoChild> = emptyList(), val reports: Map<Pair<String, Subject>, ReportState> = emptyMap(), val error: Boolean = false, val loginRequired: Boolean = false)

/** Synchronous screen operations; callers run them off the main thread. */
class LearningScreen(private val api: TodoApi, private val now: () -> Long = System::currentTimeMillis) {
    var state = LearningState()
        private set
    private var session: TodoSession? = null

    fun signIn(email: String, password: String, date: String) {
        try {
            session = api.signIn(email, password)
            refresh(date)
        } catch (e: TodoApiException) {
            state = state.copy(error = true, loginRequired = e.authenticationRequired)
        }
    }

    fun refresh(date: String) {
        val current = session ?: return
        val children = try { api.catalog(current) } catch (e: TodoApiException) {
            state = state.copy(error = true, loginRequired = e.authenticationRequired)
            return
        }
        state = state.copy(children = children, error = false, loginRequired = false)
        for (child in children) for ((subject, id) in child.userIds) {
            val key = child.name to subject
            val previous = state.reports[key]?.takeIf { it.date == date } ?: ReportState(date = date)
            val result = try { ReportState(api.report(current, subject, id, date), now(), date) }
            catch (e: TodoApiException) {
                if (e.authenticationRequired) state = state.copy(loginRequired = true)
                previous.copy(failed = true)
            }
            state = state.copy(reports = state.reports + (key to result))
        }
    }
}
