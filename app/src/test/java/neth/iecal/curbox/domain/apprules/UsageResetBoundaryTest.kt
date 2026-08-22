package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageResetBoundaryTest {
    @Test
    fun delayedResetUsesCommittedBoundaryAndNeverEndsBeforeSessionStart() {
        assertEquals(
            20_000L,
            UsageResetBoundary.effectiveAt(
                requestedAtMs = 10_000L,
                committedBoundaryMs = 20_000L,
                sessionStartedAtMs = 15_000L
            )
        )
    }

    @Test
    fun resetAtBeforeSessionStartIsClampedToSessionStart() {
        assertEquals(
            30_000L,
            UsageResetBoundary.effectiveAt(
                requestedAtMs = 10_000L,
                committedBoundaryMs = 20_000L,
                sessionStartedAtMs = 30_000L
            )
        )
    }
}
