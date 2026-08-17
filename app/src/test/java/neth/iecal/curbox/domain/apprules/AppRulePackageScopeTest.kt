package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRulePackageScopeTest {
    @Test
    fun essentialPackagesAreReadAgainWhenLauncherOrImeChanges() {
        var currentEssentials = setOf("com.launcher.old", "com.ime.old")
        val reader = AppRulePackageScopeReader(
            launchableReader = { setOf("com.reader") },
            essentialReader = { currentEssentials }
        )

        assertEquals(currentEssentials, reader.readEssentialPackages())

        currentEssentials = setOf("com.launcher.new", "com.ime.new")

        assertEquals(currentEssentials, reader.readEssentialPackages())
    }
}
