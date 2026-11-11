package neth.iecal.curbox.learning

import neth.iecal.curbox.domain.apprules.DisplayState
import neth.iecal.curbox.domain.apprules.ForegroundFacts
import neth.iecal.curbox.domain.apprules.ForegroundReadState
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.Screen

internal fun mapForeground(facts: ForegroundFacts, ordinaryApp: Boolean): Pair<Screen, Foreground> {
    val screen = when (facts.displayState) {
        DisplayState.SCREEN_OFF -> Screen.OFF
        DisplayState.UNLOCKED -> Screen.UNLOCKED
        DisplayState.KEYGUARD -> Screen.LOCKED
        DisplayState.UNKNOWN -> Screen.UNKNOWN
    }
    val root = facts.activeRoot
    val name = root.packageName
    val foreground = if (screen == Screen.UNLOCKED && ordinaryApp && root.readState == ForegroundReadState.AVAILABLE && !name.isNullOrBlank()) Foreground.Confirmed(name) else Foreground.Unknown
    return screen to foreground
}
