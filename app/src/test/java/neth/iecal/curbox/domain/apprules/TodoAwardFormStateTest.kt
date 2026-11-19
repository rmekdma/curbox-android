package neth.iecal.curbox.domain.apprules

import org.junit.Assert.*
import org.junit.Test

class TodoAwardFormStateTest {
    @Test fun `partial Todo approval updates the direct total`() {
        val state = TodoAwardFormState.initial(20).receiveOffer(0, 60).editTodo("40")
        assertEquals("0", state.manualText)
        assertEquals(60L, state.totalMinutes)
        assertEquals(0L to 40L, state.submission())
    }

    @Test fun `late report preserves edits and manual approval works while waiting`() {
        val edited = TodoAwardFormState.initial(20).editManual("5").editTodo("0").receiveOffer(0, 60)
        assertEquals("0", edited.todoText)
        assertEquals(25L, edited.totalMinutes)
        assertEquals(5L to 0L, edited.submission())
        assertNull(edited.editTodo("61").submission())
        assertNull(TodoAwardFormState.initial(0).submission())
    }
}
