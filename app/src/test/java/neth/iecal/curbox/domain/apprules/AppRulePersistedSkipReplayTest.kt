package neth.iecal.curbox.domain.apprules

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleGuardianSkip
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.GsonSerializer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulePersistedSkipReplayTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val gson = Gson()
    private val rule = AppRule(
        id = "night-rule",
        name = "Night rule",
        weekdays = (0..6).toSet(),
        timeRanges = listOf(AppRuleTimeRange(21 * 60 + 30, 6 * 60)),
        allowedMinutes = 0
    )

    @Test
    fun capturedSkipAllowsUntilResetAndDoesNotCarryIntoNextNight() = runBlocking {
        val useDay = "2026-11-13"
        val reset = 1794596400000L
        val saved = Settings(
            useDayGenerationStartedAtMs = 0L,
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = useDay,
                useDayGenerationStartedAtMs = 0L,
                skips = listOf(AppRuleGuardianSkip(
                    ruleId = rule.id,
                    useDayId = useDay,
                    skipFromMs = 1794573024408L,
                    skipUntilMs = reset
                ))
            )
        )
        val restored = restore(gson.toJson(saved))
        val captured = evaluate(restored, 1794577629805L)
        assertTrue(captured.isActive)
        assertTrue(captured.isSkipped)
        assertTrue(captured.isAllowed)

        for (now in listOf(reset, instant("2026-11-14T12:31:00Z"))) {
            val result = evaluate(restored, now)
            assertTrue(result.isActive)
            assertFalse(result.isSkipped)
            assertFalse(result.isAllowed)
        }
    }

    @Test
    fun syntheticLegacySkipDoesNotOpenNightRuleAtCapturedTimeOrAcrossReset() = runBlocking {
        val oldDay = "2026-11-01"
        val oldSkip = AppRuleGuardianSkip(
            ruleId = rule.id,
            useDayId = oldDay,
            skipFromMs = instant("2026-11-01T13:00:00Z"),
            skipUntilMs = instant("2026-11-01T13:30:00Z")
        )
        val saved = Settings(appRuleOverrideState = AppRuleOverrideState(useDayId = oldDay, skips = listOf(oldSkip)))
        val root = JsonParser.parseString(gson.toJson(saved)).asJsonObject
        val state = root.getAsJsonObject("appRuleOverrideState")
        state.remove("useDayGenerationStartedAtMs")
        state.getAsJsonArray("skips")[0].asJsonObject.remove("skipFromMs")
        val restored = restore(root.toString())

        // Captured 22:13:44 KST and either side of the default 04:00 use-day reset.
        for (now in listOf(1794575624000L, instant("2026-11-12T18:59:00Z"), instant("2026-11-12T19:01:00Z"))) {
            val result = evaluate(restored, now)
            assertTrue(result.isActive)
            assertFalse(result.isSkipped)
            assertFalse(result.isAllowed)
        }

        val now = 1794575624000L
        val currentDay = ConfigurableUseDayCalculator(zone).idAt(now)
        val mismatchedDay = oldSkip.copy(skipFromMs = now - 60_000L, skipUntilMs = now + 60_000L)
        val expired = oldSkip.copy(useDayId = currentDay, skipFromMs = now - 120_000L, skipUntilMs = now - 60_000L)
        for ((day, skip) in listOf(oldDay to mismatchedDay, currentDay to expired)) {
            val restoredCase = restore(gson.toJson(Settings(
                appRuleOverrideState = AppRuleOverrideState(useDayId = day, skips = listOf(skip))
            )))
            val result = evaluate(restoredCase, now)
            assertTrue(result.isActive)
            assertFalse(result.isSkipped)
            assertFalse(result.isAllowed)
        }
    }

    @Test
    fun resetGenerationInvalidatesPersistedSkipEvenWhenIntervalAndDayMatch() = runBlocking {
        val now = 1794575624000L
        val currentDay = ConfigurableUseDayCalculator(zone).idAt(now)
        val saved = Settings(
            useDayGenerationStartedAtMs = 2L,
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = currentDay,
                useDayGenerationStartedAtMs = 1L,
                skips = listOf(AppRuleGuardianSkip(
                    ruleId = rule.id,
                    useDayId = currentDay,
                    skipUntilMs = now + 600_000L,
                    skipFromMs = now - 600_000L
                ))
            )
        )
        val result = evaluate(restore(gson.toJson(saved)), now)
        assertTrue(result.isActive)
        assertFalse(result.isSkipped)
        assertFalse(result.isAllowed)

        val matchingGeneration = saved.copy(useDayGenerationStartedAtMs = 1L)
        val active = evaluate(restore(gson.toJson(matchingGeneration)), now)
        assertTrue(active.isSkipped)
        assertTrue(active.isAllowed)

        val legacyJson = JsonParser.parseString(gson.toJson(matchingGeneration)).asJsonObject
        legacyJson.getAsJsonObject("appRuleOverrideState").remove("useDayGenerationStartedAtMs")
        val missingGeneration = evaluate(restore(legacyJson.toString()), now)
        assertTrue(missingGeneration.isActive)
        assertFalse(missingGeneration.isSkipped)
        assertFalse(missingGeneration.isAllowed)
    }

    private suspend fun restore(json: String): Settings =
        GsonSerializer(gson, Settings::class.java, Settings()).readFrom(ByteArrayInputStream(json.toByteArray()))

    private fun evaluate(settings: Settings, now: Long): AppRuleEvaluation {
        val useDay = ConfigurableUseDayCalculator(zone, settings.useDayResetTime).idAt(now)
        return AppRuleEvaluator.evaluateRule(
            rule = rule,
            targetPackages = setOf("example.todo"),
            useDayId = useDay,
            sessions = emptyList(),
            nowMs = now,
            zone = zone,
            resetTime = settings.useDayResetTime,
            useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs,
            overrides = settings.appRuleOverrideState
        )
    }

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()
}
