package neth.iecal.curbox.domain.apprules

import org.junit.Assert.*
import org.junit.Test

class AdditionalTimeOfferFormStateTest {
    @Test fun `partial contribution approval updates the direct total`() {
        val state = AdditionalTimeOfferFormState.initial(20).receiveOffer(0, 60).editOffer("40")
        assertEquals("0", state.manualText)
        assertEquals(60L, state.totalMinutes)
        assertEquals(0L to 40L, state.submission())
    }

    @Test fun `late report preserves edits and manual approval works while waiting`() {
        val edited = AdditionalTimeOfferFormState.initial(20).editManual("5").editOffer("0").receiveOffer(0, 60)
        assertEquals("0", edited.offeredText)
        assertEquals(25L, edited.totalMinutes)
        assertEquals(5L to 0L, edited.submission())
        assertNull(edited.editOffer("61").submission())
        assertNull(AdditionalTimeOfferFormState.initial(0).submission())
    }
    @Test fun `offer starts at proposed amount and enforces maximum`() {
        val state = AdditionalTimeOfferFormState.initial(10).receiveOffer(0, 60, 25)
        assertEquals(35L, state.totalMinutes)
        assertEquals(0L to 25L, state.submission())
        assertEquals(0L to 60L, state.editOffer("60").submission())
        assertNull(state.editOffer("61").submission())
    }
}
