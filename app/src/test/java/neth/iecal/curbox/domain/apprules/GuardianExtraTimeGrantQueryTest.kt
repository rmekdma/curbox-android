package neth.iecal.curbox.domain.apprules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleGuardianGrant
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.Settings
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianExtraTimeGrantQueryTest {
    @Test
    fun candidatesUseOneEffectiveSettingsClockZoneAndUseDayGeneration() = runBlocking {
        withSystemZone("UTC") {
            val nowMs = Instant.parse("2026-10-04T20:00:00Z").toEpochMilli()
            val useDayId = "2026-10-05"
            val settings = settingsForCandidates(useDayId, nowMs)
            val sessionReads = mutableListOf<Pair<String, Long>>()
            val query: GuardianExtraTimeGrantQuery = AppRuleGuardianExtraTimeGrantQuery(
                effectiveSettings = MutableStateFlow(settings),
                sessionRepository = RecordingSessionRepository { dayId, generation ->
                    sessionReads += dayId to generation
                    emptyList()
                },
                packageScopeReader = AppRulePackageScopeReader(
                    launchableReader = { setOf("example.target", "example.other") },
                    essentialReader = { emptySet() }
                ),
                clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC")),
                currentZone = { ZoneId.of("Asia/Seoul") }
            )

            val candidates = query.candidates()

            assertEquals(
                listOf("blocking-other-app", "first-allowed", "future-window"),
                candidates.map { it.rule.id }
            )
            assertEquals(
                15 * MINUTE,
                candidates.first { it.rule.id == "first-allowed" }.basis.currentTotalMillis
            )
            assertEquals(useDayId, candidates.first().basis.useDayId)
            assertEquals(77L, candidates.first().basis.useDayGenerationStartedAtMs)
            assertEquals(listOf(useDayId to 77L), sessionReads)
        }
    }

    @Test
    fun selectedRuleBasisReadsTheLatestSettingsAndCurrentClockContext() = runBlocking {
        withSystemZone("UTC") {
            val nowMs = Instant.parse("2026-10-04T20:00:00Z").toEpochMilli()
            val useDayId = "2026-10-05"
            var currentZone = ZoneId.of("Asia/Seoul")
            val settings = settingsForCandidates(useDayId, nowMs)
            val settingsFlow = MutableStateFlow(settings)
            val query: GuardianExtraTimeGrantQuery = AppRuleGuardianExtraTimeGrantQuery(
                effectiveSettings = settingsFlow,
                sessionRepository = RecordingSessionRepository { _, _ -> emptyList() },
                packageScopeReader = AppRulePackageScopeReader(
                    launchableReader = { setOf("example.target", "example.other") },
                    essentialReader = { emptySet() }
                ),
                clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC")),
                currentZone = { currentZone }
            )
            val listedBasis = query.candidates().first { it.rule.id == "first-allowed" }.basis
            val latestUseDayId = "2026-10-04"
            currentZone = ZoneId.of("UTC")
            settingsFlow.value = settings.copy(
                useDayGenerationStartedAtMs = 88L,
                appRuleOverrideState = AppRuleOverrideState(
                    useDayId = latestUseDayId,
                    useDayGenerationStartedAtMs = 88L,
                    grants = listOf(
                        AppRuleGuardianGrant(
                            "first-allowed", latestUseDayId, nowMs - 1L, 23 * MINUTE
                        )
                    )
                )
            )

            val latestBasis = query.currentBasis("first-allowed")

            assertEquals(useDayId, listedBasis.useDayId)
            assertEquals(77L, listedBasis.useDayGenerationStartedAtMs)
            assertEquals(15 * MINUTE, listedBasis.currentTotalMillis)
            assertEquals(latestUseDayId, latestBasis?.useDayId)
            assertEquals(88L, latestBasis?.useDayGenerationStartedAtMs)
            assertEquals(23 * MINUTE, latestBasis?.currentTotalMillis)
        }
    }

    @Test
    fun candidateLookupReturnsAnEmptyListWhenNoRuleCanReceiveTime() = runBlocking {
        val nowMs = Instant.parse("2026-10-04T20:00:00Z").toEpochMilli()
        val settings = settingsForCandidates("2026-10-05", nowMs).copy(
            appRuleSnapshot = AppRuleSnapshot(
                appRules = listOf(
                    AppRule(id = "inactive", isActive = false),
                    AppRule(id = "disallowed", guardianExtraTimeAllowed = false)
                )
            )
        )
        val query: GuardianExtraTimeGrantQuery = AppRuleGuardianExtraTimeGrantQuery(
            effectiveSettings = MutableStateFlow(settings),
            sessionRepository = RecordingSessionRepository { _, _ -> emptyList() },
            packageScopeReader = AppRulePackageScopeReader(
                launchableReader = { emptySet() },
                essentialReader = { emptySet() }
            ),
            clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneId.of("UTC")),
            currentZone = { ZoneId.of("Asia/Seoul") }
        )

        assertEquals(emptyList<GuardianExtraTimeGrantCandidate>(), query.candidates())
    }

    @Test
    fun candidateReadFailuresAndCancellationRemainObservable() {
        val failure = IllegalStateException("settings read failed")
        val failedQuery = queryWithSettings(flow { throw failure })
        val observedFailure = runCatching { runBlocking { failedQuery.candidates() } }.exceptionOrNull()
        assertTrue(observedFailure is IllegalStateException)
        assertEquals(failure.message, observedFailure?.message)

        val cancellation = CancellationException("query cancelled")
        val cancelledQuery = queryWithSettings(flow { throw cancellation })
        val observedCancellation = runCatching {
            runBlocking { cancelledQuery.candidates() }
        }.exceptionOrNull()
        assertTrue(observedCancellation is CancellationException)
        assertEquals(cancellation.message, observedCancellation?.message)
    }

    private fun queryWithSettings(settings: Flow<Settings>): GuardianExtraTimeGrantQuery =
        AppRuleGuardianExtraTimeGrantQuery(
            effectiveSettings = settings,
            sessionRepository = RecordingSessionRepository { _, _ -> emptyList() },
            packageScopeReader = AppRulePackageScopeReader(
                launchableReader = { emptySet() },
                essentialReader = { emptySet() }
            ),
            clock = Clock.fixed(Instant.parse("2026-10-04T20:00:00Z"), ZoneId.of("UTC")),
            currentZone = { ZoneId.of("Asia/Seoul") }
        )

    private fun settingsForCandidates(useDayId: String, nowMs: Long): Settings {
        val targetGroup = AppRuleAppGroup(
            id = "target",
            name = "Target apps",
            selectedPackages = listOf("example.target")
        )
        val otherGroup = AppRuleAppGroup(
            id = "other",
            name = "Other apps",
            selectedPackages = listOf("example.other")
        )
        val snapshot = AppRuleSnapshot(
            appGroups = listOf(targetGroup, otherGroup),
            appRules = listOf(
                AppRule(
                    id = "first-allowed",
                    name = "First allowed",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 30L
                ),
                AppRule(
                    id = "blocking-other-app",
                    name = "Blocking other app",
                    appGroupId = otherGroup.id,
                    allowedMinutes = 0L,
                    timeRanges = listOf(AppRuleTimeRange(4 * 60, 6 * 60))
                ),
                AppRule(
                    id = "future-window",
                    name = "Future window",
                    appGroupId = targetGroup.id,
                    allowedMinutes = 30L,
                    timeRanges = listOf(AppRuleTimeRange(6 * 60, 7 * 60))
                ),
                AppRule(
                    id = "not-grantable",
                    name = "Not grantable",
                    appGroupId = targetGroup.id,
                    guardianExtraTimeAllowed = false
                ),
                AppRule(
                    id = "disabled",
                    name = "Disabled",
                    appGroupId = targetGroup.id,
                    isActive = false
                )
            )
        )
        return Settings(
            appRuleSnapshot = snapshot,
            useDayResetHour = 4,
            useDayResetMinute = 0,
            useDayGenerationStartedAtMs = 77L,
            appRuleOverrideState = AppRuleOverrideState(
                useDayId = useDayId,
                useDayGenerationStartedAtMs = 77L,
                grants = listOf(
                    AppRuleGuardianGrant("first-allowed", useDayId, nowMs - 4L, 15 * MINUTE),
                    AppRuleGuardianGrant(
                        "first-allowed", useDayId, nowMs - 3L, 45 * MINUTE,
                        isFromAccumulatedPool = true
                    ),
                    AppRuleGuardianGrant("first-allowed", "2026-10-04", nowMs - 2L, 9 * MINUTE),
                    AppRuleGuardianGrant("first-allowed", useDayId, nowMs + 1L, 8 * MINUTE)
                )
            )
        )
    }

    private class RecordingSessionRepository(
        private val read: suspend (String, Long) -> List<ForegroundSession>
    ) : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) =
            0L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String) = read(useDayId, 0L)

        override suspend fun sessionsForUseDay(
            useDayId: String,
            generationStartedAtMs: Long
        ) = read(useDayId, generationStartedAtMs)

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private suspend fun <T> withSystemZone(zone: String, block: suspend () -> T): T {
        val previousZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
        return try {
            block()
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
