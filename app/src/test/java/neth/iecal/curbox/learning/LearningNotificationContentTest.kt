package neth.iecal.curbox.learning

import neth.iecal.todo.*
import org.junit.Assert.*
import org.junit.Test

class LearningNotificationContentTest {
    @Test fun `empty catalog shows its failure in notification body`() {
        val monitor = MonitorState(accountId = "A", registrations = mapOf("Child" to 1), catalogFailures = mapOf("Child" to LearningFailure.NETWORK))
        val body = learningNotificationDetails(monitor, "Child", { it.name }, { "network error" }) { "unused" }
        assertEquals("network error", body)
    }
}
