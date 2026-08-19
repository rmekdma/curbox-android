package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import neth.iecal.curbox.utils.TimeTools

class UsageResetDeltaCalculatorTest {
    private val zone = ZoneOffset.UTC
    private val resetAt = Instant.parse("2026-08-18T01:30:00Z").toEpochMilli()

    @Test
    fun subtractsOnlyCurrentUseDayTimeAndPreservesThePreviousCalendarDayBucket() {
        val session = ForegroundSession(
            useDayId = "2026-08-17",
            packageName = "app",
            startedAtMs = Instant.parse("2026-08-17T23:30:00Z").toEpochMilli(),
            endedAtMs = resetAt
        )
        val delta = UsageResetDeltaCalculator.calculate(
            sessions = listOf(session),
            launches = emptyList(),
            packageNames = setOf("app"),
            resetAtMs = resetAt,
            zone = zone
        )

        assertEquals(60 * 60_000L, delta.timeByBucketMs[
            UsageResetBucketKey(TimeTools.dayKey(LocalDate.of(2026, 8, 18)), "app", 0)
        ])
        assertEquals(30 * 60_000L, delta.timeByBucketMs[
            UsageResetBucketKey(TimeTools.dayKey(LocalDate.of(2026, 8, 17)), "app", 23)
        ])
    }

    @Test
    fun launchDeltaIsTakenFromLaunchTimestampsAndNotSessionCount() {
        val launches = listOf(
            ForegroundLaunch(packageName = "app", launchedAtMs = resetAt - 1L),
            ForegroundLaunch(packageName = "app", launchedAtMs = resetAt + 1L),
            ForegroundLaunch(packageName = "other", launchedAtMs = resetAt - 1L)
        )
        val delta = UsageResetDeltaCalculator.calculate(
            sessions = emptyList(),
            launches = launches,
            packageNames = setOf("app"),
            resetAtMs = resetAt,
            zone = zone
        )

        assertEquals(1, delta.launchesByPackage["app"])
    }

    @Test
    fun openSessionTailIsNotSubtractedFromCalendarAggregates() {
        val delta = UsageResetDeltaCalculator.calculate(
            sessions = listOf(
                ForegroundSession(
                    useDayId = "2026-08-18",
                    packageName = "app",
                    startedAtMs = resetAt - 30 * 60_000L,
                    endedAtMs = null
                )
            ),
            launches = emptyList(),
            packageNames = setOf("app"),
            resetAtMs = resetAt,
            zone = zone
        )

        assertEquals(emptyMap<UsageResetBucketKey, Long>(), delta.timeByBucketMs)
    }
}
