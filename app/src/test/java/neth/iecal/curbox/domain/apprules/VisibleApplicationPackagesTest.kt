package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleApplicationPackagesTest {
    @Test
    fun keepsEachVisibleApplicationOnceAndIgnoresNonApplicationWindows() {
        val result = VisibleApplicationPackages.fromWindows(
            listOf(
                VisibleApplicationWindow("com.example.left"),
                VisibleApplicationWindow("com.example.left"),
                VisibleApplicationWindow("com.example.right"),
                VisibleApplicationWindow("com.android.systemui", VisibleApplicationWindow.TYPE_SYSTEM),
                VisibleApplicationWindow("com.example.keyboard", VisibleApplicationWindow.TYPE_INPUT_METHOD),
                VisibleApplicationWindow("neth.iecal.curbox")
            ),
            ownPackage = "neth.iecal.curbox",
            systemUiPackage = "com.android.systemui"
        )

        assertEquals(setOf("com.example.left", "com.example.right"), result)
    }

    @Test
    fun reconciliationClosesOnlyPackagesThatDisappeared() {
        val delta = VisibleApplicationPackages.reconcile(
            previous = setOf("com.example.left", "com.example.right"),
            next = setOf("com.example.right", "com.example.new")
        )

        assertEquals(setOf("com.example.left"), delta.ended)
        assertEquals(setOf("com.example.new"), delta.started)
        assertEquals(setOf("com.example.right"), delta.retained)
    }
}
