package neth.iecal.curbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UseDayTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun beforeFourAmBelongsToPreviousUseDay() {
        val id = UseDay.idAt(
            Instant.parse("2026-08-17T18:59:59Z").toEpochMilli(),
            zone
        )

        assertEquals("2026-08-17", id)
    }

    @Test
    fun fourAmStartsTheNewUseDay() {
        val id = UseDay.idAt(
            Instant.parse("2026-08-17T19:00:00Z").toEpochMilli(),
            zone
        )

        assertEquals("2026-08-18", id)
    }

    @Test
    fun windowHasExactlyOneLocalDay() {
        val window = UseDay.windowFor("2026-08-17", zone)

        assertTrue(window.last - window.first >= 23 * 60 * 60 * 1000L)
        assertTrue(window.last - window.first <= 25 * 60 * 60 * 1000L)
    }
}
