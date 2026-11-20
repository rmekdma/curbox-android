package neth.iecal.curbox.learning

import neth.iecal.todo.StoredLearning
import neth.iecal.todo.Subject
import neth.iecal.todo.authenticationRequired
import neth.iecal.todo.registeredChildren

/** Identifies an active monitored child and the session that owns its registration. */
data class TodoRegistrationScope(
    val accountId: String,
    val childName: String,
    val sessionRef: String,
    val registration: Long,
    val subjects: Map<Subject, String>
) {
    fun isCurrent(document: StoredLearning): Boolean = capture(document, childName) == this

    companion object {
        fun capture(document: StoredLearning, childName: String): TodoRegistrationScope? {
            val account = document.state.accountId ?: return null
            val session = document.sessionRef ?: return null
            val monitor = document.monitor
            if (account.isBlank() || childName.isBlank() || session.isBlank() ||
                monitor.accountId != account || monitor.authenticationRequired(session)) return null
            val registration = monitor.registeredChildren()[childName] ?: return null
            val child = monitor.children.singleOrNull { it.name == childName } ?: return null
            if (child.userIds.isEmpty()) return null
            return TodoRegistrationScope(account, childName, session, registration, child.userIds.toMap())
        }
    }
}
