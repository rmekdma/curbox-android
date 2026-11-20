package neth.iecal.curbox.learning

import neth.iecal.todo.LearningState
import neth.iecal.todo.MonitorState
import neth.iecal.todo.StoredLearning
import neth.iecal.todo.TodoChild
import neth.iecal.todo.Subject
import org.junit.Assert.*
import org.junit.Test

class TodoRegistrationScopeTest {
    @Test fun `unregister and session replacement invalidate an earlier scope`() {
        val document = StoredLearning(state = LearningState(accountId = "account"), sessionRef = "session",
            monitor = MonitorState(accountId = "account", registrations = mapOf("Child" to 7L),
                children = listOf(TodoChild("Child", mapOf(Subject.MATH to "m")))))
        val scope = TodoRegistrationScope.capture(document, "Child")!!
        assertTrue(scope.isCurrent(document))
        assertFalse(scope.isCurrent(document.copy(sessionRef = "other")))
        assertFalse(scope.isCurrent(document.copy(monitor = document.monitor.copy(registrations = emptyMap(), childName = null))))
        assertFalse(scope.isCurrent(document.copy(monitor = document.monitor.copy(children = emptyList()))))
    }
}
