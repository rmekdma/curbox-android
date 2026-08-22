package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageResetUiPolicyTest {
    @Test
    fun currentDayEligibilityExpiresAtTheNextCalendarDay() {
        assertTrue(UsageResetUiPolicy.isCurrentResetEligible(42L, 42L))
        assertFalse(UsageResetUiPolicy.isCurrentResetEligible(42L, 43L))
        assertFalse(UsageResetUiPolicy.isCurrentResetEligible(null, 42L))
    }

    @Test
    fun completionRequiresTheDisplayedPackageAndEligibility() {
        assertTrue(
            UsageResetUiPolicy.completionMatchesPackage(
                packageName = "app",
                completedPackages = setOf("app", "other"),
                resetEligible = true
            )
        )
        assertFalse(
            UsageResetUiPolicy.completionMatchesPackage(
                packageName = "app",
                completedPackages = setOf("other"),
                resetEligible = true
            )
        )
        assertFalse(
            UsageResetUiPolicy.completionMatchesPackage(
                packageName = "app",
                completedPackages = setOf("app"),
                resetEligible = false
            )
        )
        assertTrue(
            UsageResetUiPolicy.completionMatchesPackage(
                packageName = "app",
                completedPackages = setOf("app"),
                resetEligible = false,
                resetRequested = true
            )
        )
    }

    @Test
    fun groupScreenOnlyToastsForAffectingPackages() {
        val groups = listOf(listOf("first"), listOf("second", "third"))
        assertTrue(UsageResetUiPolicy.affectsGroupTotals(groups, setOf("third")))
        assertFalse(UsageResetUiPolicy.affectsGroupTotals(groups, setOf("unrelated")))
    }
}
