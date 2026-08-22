package neth.iecal.curbox.domain.apprules

/** Pure guards shared by usage reset screens and their completion broadcasts. */
object UsageResetUiPolicy {
    fun isCurrentResetEligible(
        resetEligibleEpochDay: Long?,
        currentEpochDay: Long
    ): Boolean = resetEligibleEpochDay != null && resetEligibleEpochDay == currentEpochDay

    fun completionMatchesPackage(
        packageName: String,
        completedPackages: Collection<String>,
        resetEligible: Boolean,
        resetRequested: Boolean = false
    ): Boolean =
        (resetRequested || resetEligible) && packageName in completedPackages

    fun affectsGroupTotals(
        groupPackages: Collection<Collection<String>>,
        completedPackages: Collection<String>
    ): Boolean = groupPackages.any { packages ->
        packages.any { packageName -> packageName in completedPackages }
    }
}
