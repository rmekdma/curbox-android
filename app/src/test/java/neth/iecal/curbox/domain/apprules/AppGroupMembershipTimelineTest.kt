package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppGroupEditMode
import neth.iecal.curbox.data.models.AppGroupMembershipVersion
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.ForegroundSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime

class AppGroupMembershipTimelineTest {
    @Test
    fun pendingModesCanGiveDifferentGroupsDifferentEffectiveBoundaries() {
        val first = AppRuleAppGroup("first", "First", listOf("old-first"))
        val second = AppRuleAppGroup("second", "Second", listOf("old-second"))
        val proposed = AppRuleSnapshot(
            listOf(
                first.copy(selectedPackages = listOf("new-first")),
                second.copy(selectedPackages = listOf("new-second"))
            )
        )

        val applied = AppGroupMembershipTimeline.apply(
            previous = AppRuleSnapshot(listOf(first, second)),
            proposed = proposed,
            effectiveAtByGroup = mapOf(first.id to 100L, second.id to 200L)
        )

        assertEquals(setOf("old-first"), applied.appGroups[0].packagesAt(99L))
        assertEquals(setOf("new-first"), applied.appGroups[0].packagesAt(100L))
        assertEquals(setOf("old-second"), applied.appGroups[1].packagesAt(199L))
        assertEquals(setOf("new-second"), applied.appGroups[1].packagesAt(200L))
    }

    @Test
    fun currentUseDayStartUsesTheConfiguredFourAmBoundaryAtApplyTime() {
        val calculator = ConfigurableUseDayCalculator(
            zone = ZoneOffset.UTC,
            resetTime = UseDayResetTime(4, 0)
        )
        val appliedAt = Instant.parse("2026-08-18T02:00:00Z").toEpochMilli()
        val expectedStart = Instant.parse("2026-08-17T04:00:00Z").toEpochMilli()

        assertEquals(
            expectedStart,
            AppGroupMembershipTimeline.effectiveAt(
                AppGroupEditMode.CURRENT_USE_DAY_START,
                appliedAt,
                calculator
            )
        )
        assertEquals(
            appliedAt,
            AppGroupMembershipTimeline.effectiveAt(AppGroupEditMode.NOW, appliedAt, calculator)
        )
    }

    @Test
    fun nowKeepsTheOldMembershipBeforeTheEditAndUsesTheNewOneAfterIt() {
        val old = AppRuleAppGroup("group", "Group", listOf("old"))
        val next = old.copy(selectedPackages = listOf("new"))
        val applied = AppGroupMembershipTimeline.apply(
            AppRuleSnapshot(listOf(old)),
            AppRuleSnapshot(listOf(next)),
            AppGroupEditMode.NOW,
            effectiveAtMs = 100L
        ).appGroups.single()

        assertEquals(setOf("old"), applied.packagesAt(99L))
        assertEquals(setOf("new"), applied.packagesAt(100L))
    }

    @Test
    fun currentUseDayStartReplacesEarlierVersionsInsideThatUseDay() {
        val old = AppRuleAppGroup(
            id = "group",
            name = "Group",
            selectedPackages = listOf("now"),
            membershipHistory = listOf(
                AppGroupMembershipVersion(Long.MIN_VALUE, listOf("old")),
                AppGroupMembershipVersion(100L, listOf("now"))
            )
        )
        val next = old.copy(selectedPackages = listOf("today"))
        val applied = AppGroupMembershipTimeline.apply(
            AppRuleSnapshot(listOf(old)),
            AppRuleSnapshot(listOf(next)),
            AppGroupEditMode.CURRENT_USE_DAY_START,
            effectiveAtMs = 50L
        ).appGroups.single()

        assertEquals(setOf("old"), applied.packagesAt(49L))
        assertEquals(setOf("today"), applied.packagesAt(50L))
        assertEquals(setOf("today"), applied.packagesAt(101L))
    }

    @Test
    fun resolverDeduplicatesOverlappingContributorGroups() {
        val first = AppRuleAppGroup("a", "A", listOf("one", "shared"))
        val second = AppRuleAppGroup("b", "B", listOf("shared", "two"))
        val rule = neth.iecal.curbox.data.models.AppRule(
            contributorGroupIds = setOf("a", "b")
        )
        val packages = AppRuleMembershipResolver(
            AppRuleSnapshot(listOf(first, second), listOf(rule))
        ).contributorPackagesAt(rule, 1L)

        assertEquals(setOf("one", "shared", "two"), packages)
        assertTrue(packages.size == 3)
    }

    @Test
    fun targetUsageOnlyChargesTheMembershipPieceInsideTheActiveWindow() {
        val old = AppRuleAppGroup("target", "Target", listOf("app"))
        val next = old.copy(selectedPackages = listOf("other"))
        val group = AppGroupMembershipTimeline.apply(
            AppRuleSnapshot(listOf(old)),
            AppRuleSnapshot(listOf(next)),
            AppGroupEditMode.NOW,
            4 * 60 * 60_000L + 100L
        ).appGroups.single()
        val rule = AppRule(
            id = "rule",
            name = "Rule",
            weekdays = setOf(4),
            startMinute = 4 * 60,
            endMinute = 4 * 60 + 10,
            scope = AppRuleScope.forGroup(group.id),
            allowedMinutes = 10
        )
        val result = AppRuleEvaluator.evaluateRuleForSnapshot(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            rule = rule,
            useDayId = "1970-01-01",
            sessions = listOf(
                ForegroundSession(
                    useDayId = "1970-01-01",
                    packageName = "app",
                    startedAtMs = 4 * 60 * 60_000L,
                    endedAtMs = 4 * 60 * 60_000L + 50L
                )
            ),
            nowMs = 4 * 60 * 60_000L + 50L,
            zone = java.time.ZoneOffset.UTC
        )

        assertEquals(50L, result.usedMillis)
    }

    @Test
    fun contributorMembershipChangesAreSymmetricAndUseTheWholeUseDay() {
        val boundary = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-08-17T10:30:00Z").toEpochMilli()
        val oldContributor = AppRuleAppGroup("contributor", "Contributor", listOf("old"))
        val contributor = AppGroupMembershipTimeline.apply(
            previous = AppRuleSnapshot(listOf(oldContributor)),
            proposed = AppRuleSnapshot(listOf(oldContributor.copy(selectedPackages = listOf("new")))),
            mode = AppGroupEditMode.NOW,
            effectiveAtMs = boundary
        ).appGroups.single()
        val target = AppRuleAppGroup("target", "Target", listOf("target"))
        val rule = AppRule(
            id = "rule",
            name = "Rule",
            weekdays = setOf(1),
            startMinute = 12 * 60,
            endMinute = 13 * 60,
            scope = AppRuleScope.forGroup(target.id),
            contributorGroupIds = setOf(contributor.id),
            allowedMinutes = 1
        )

        val result = AppRuleEvaluator.evaluateRuleForSnapshot(
            snapshot = AppRuleSnapshot(listOf(target, contributor), listOf(rule)),
            rule = rule,
            useDayId = "2026-08-17",
            sessions = listOf(
                ForegroundSession(
                    useDayId = "2026-08-17",
                    packageName = "old",
                    startedAtMs = boundary - 10 * 60_000L,
                    endedAtMs = boundary + 10 * 60_000L
                ),
                ForegroundSession(
                    useDayId = "2026-08-17",
                    packageName = "new",
                    startedAtMs = boundary,
                    endedAtMs = now
                )
            ),
            nowMs = now,
            zone = ZoneOffset.UTC
        )

        assertTrue(result.isActive.not())
        assertEquals(40 * 60_000L, result.contributorUsageMillis)
    }
}
