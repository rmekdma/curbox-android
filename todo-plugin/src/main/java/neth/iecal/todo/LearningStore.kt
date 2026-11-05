package neth.iecal.todo

data class StoredLearning(val revision: Long = 0, val state: LearningState = LearningState(), val sessionRef: String? = null)

/** Storage transactions reject effects owned by a superseded request. */
interface LearningStore {
    fun read(): StoredLearning
    fun claim(): StoredLearning
    fun commit(revision: Long, state: LearningState, sessionRef: String?): Boolean
    fun readSession(ref: String): TodoSession
    fun writeSession(ref: String, session: TodoSession)
}

class MemoryLearningStore : LearningStore {
    private var document = StoredLearning()
    private val sessions = mutableMapOf<String, TodoSession>()
    @Synchronized override fun read() = document
    @Synchronized override fun claim(): StoredLearning { document = document.copy(revision = document.revision + 1); return document }
    @Synchronized override fun commit(revision: Long, state: LearningState, sessionRef: String?): Boolean {
        if (document.revision != revision) return false
        document = document.copy(state = state, sessionRef = sessionRef)
        return true
    }
    @Synchronized override fun readSession(ref: String) = requireNotNull(sessions[ref])
    @Synchronized override fun writeSession(ref: String, session: TodoSession) { sessions[ref] = session }
}
