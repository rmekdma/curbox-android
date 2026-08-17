package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CurrentUseDayUsageAggregatorTest {
    @Test
    fun currentUseDayAggregationMergesOverlapsAndFiltersOldGeneration() {
        val sessions = listOf(
            ForegroundSession(
                packageName = "com.example.video",
                startedAtMs = 0L,
                endedAtMs = 1_000L,
                useDayGenerationStartedAtMs = 100L
            ),
            ForegroundSession(
                packageName = "com.example.video",
                startedAtMs = 500L,
                endedAtMs = 2_000L,
                useDayGenerationStartedAtMs = 100L
            ),
            ForegroundSession(
                packageName = "com.example.video",
                startedAtMs = 0L,
                endedAtMs = 10_000L,
                useDayGenerationStartedAtMs = 50L
            ),
            ForegroundSession(
                packageName = "com.example.video",
                startedAtMs = 2_000L,
                endedAtMs = 4_000L,
                useDayGenerationStartedAtMs = 100L,
                statisticsTracked = false
            )
        )
        val launches = listOf(
            ForegroundLaunch(
                packageName = "com.example.video",
                launchedAtMs = 200L,
                useDayGenerationStartedAtMs = 100L
            ),
            ForegroundLaunch(
                packageName = "com.example.video",
                launchedAtMs = 300L,
                useDayGenerationStartedAtMs = 50L
            )
        )

        val result = CurrentUseDayUsageAggregator.aggregate(
            sessions = sessions,
            launches = launches,
            nowMs = 5_000L,
            zone = ZoneId.of("UTC"),
            generationStartedAtMs = 100L
        )

        assertEquals(1, result.size)
        assertEquals("com.example.video", result.single().packageName)
        assertEquals(2_000L, result.single().totalTimeMs)
        assertEquals(1, result.single().launchCount)
    }

    @Test
    fun launchOnlyPackageRemainsVisibleInCurrentUseDayStats() {
        val result = CurrentUseDayUsageAggregator.aggregate(
            sessions = emptyList(),
            launches = listOf(
                ForegroundLaunch(packageName = "com.example.instant", launchedAtMs = 100L)
            ),
            nowMs = 200L,
            zone = ZoneId.of("UTC")
        )

        assertEquals(1, result.size)
        assertEquals("com.example.instant", result.single().packageName)
        assertEquals(0L, result.single().totalTimeMs)
        assertEquals(1, result.single().launchCount)
        assertTrue(result.single().hourlyUsage.all { it == 0L })
    }

    @Test
    fun groupUsageUsesExactSessionIntersectionOncePerPackage() {
        val sessions = listOf(
            ForegroundSession(
                packageName = "com.example.one",
                startedAtMs = 0L,
                endedAtMs = 2_000L
            ),
            ForegroundSession(
                packageName = "com.example.one",
                startedAtMs = 1_000L,
                endedAtMs = 3_000L
            ),
            ForegroundSession(
                packageName = "com.example.two",
                startedAtMs = 500L,
                endedAtMs = 2_500L
            )
        )

        assertEquals(
            4_000L,
            CurrentUseDayUsageAggregator.usageMillisBetween(
                sessions = sessions,
                packageNames = setOf("com.example.one", "com.example.two"),
                startMs = 500L,
                endMs = 2_500L,
                nowMs = 10_000L
            )
        )
    }
}
