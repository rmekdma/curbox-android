package neth.iecal.curbox.domain.apprules

import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRuleScopeTest {
    @Test
    fun allAppsAndIncludedGroupsAreUnionedBeforeExcludedGroupsAndEssentialPackages() {
        val study = AppRuleAppGroup("study", "Study", listOf("com.reader", "com.settings"))
        val games = AppRuleAppGroup("games", "Games", listOf("com.game", "com.reader"))
        val scope = AppRuleScope(
            includeAllApps = true,
            includedGroupIds = setOf(study.id),
            excludedGroupIds = setOf(games.id)
        )

        val resolved = scope.resolve(
            groups = listOf(study, games),
            launchablePackages = setOf(
                "com.reader", "com.settings", "com.game", "com.future"
            ),
            essentialExcludedPackages = setOf("com.curbox", "com.launcher", "com.systemui", "com.ime")
        )

        assertEquals(setOf("com.settings", "com.future"), resolved)
    }
}
