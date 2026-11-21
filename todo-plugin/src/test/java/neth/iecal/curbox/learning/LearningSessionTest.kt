package neth.iecal.curbox.learning

import org.junit.Assert.*
import org.junit.Test

class LearningSessionTest {
    @Test fun `legacy session has no email and new session restores it`() {
        assertNull(decodeLearningSession("""{"accountId":"id","token":"token"}""").email)
        assertEquals("person@example.test", decodeLearningSession("""{"accountId":"id","token":"token","email":"person@example.test"}""").email)
    }
}
