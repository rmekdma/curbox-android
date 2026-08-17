package neth.iecal.curbox.domain.apprules

/** Pure representation of an Accessibility window used by JVM reconciliation tests. */
data class VisibleApplicationWindow(
    val packageName: String,
    val type: Int = TYPE_APPLICATION,
    val isInteractive: Boolean = true
) {
    companion object {
        const val TYPE_APPLICATION = 1
        const val TYPE_INPUT_METHOD = 2
        const val TYPE_SYSTEM = 3
        const val TYPE_ACCESSIBILITY_OVERLAY = 4
        const val TYPE_SPLIT_SCREEN_DIVIDER = 5
    }
}

/**
 * Converts the complete interactive window list into the package set that can accrue foreground
 * time. Application windows are deduplicated by package; IME, system, overlay and divider windows
 * never terminate another still visible application.
 */
object VisibleApplicationPackages {
    fun fromWindows(
        windows: Iterable<VisibleApplicationWindow>,
        ownPackage: String = "",
        systemUiPackage: String = "com.android.systemui",
        inputMethodPackage: String? = null
    ): Set<String> = windows.asSequence()
        .filter { it.type == VisibleApplicationWindow.TYPE_APPLICATION }
        .filter { it.isInteractive }
        .map { it.packageName.trim() }
        .filter { it.isNotEmpty() }
        .filter { it != ownPackage && it != systemUiPackage }
        .filter { inputMethodPackage == null || it != inputMethodPackage }
        .toCollection(LinkedHashSet())

    fun reconcile(previous: Set<String>, next: Set<String>): VisiblePackageDelta =
        VisiblePackageDelta(
            ended = previous - next,
            started = next - previous,
            retained = previous intersect next
        )
}

data class VisiblePackageDelta(
    val ended: Set<String>,
    val started: Set<String>,
    val retained: Set<String>
)
