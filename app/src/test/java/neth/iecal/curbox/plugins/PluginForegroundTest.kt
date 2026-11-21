package neth.iecal.curbox.plugins

import neth.iecal.curbox.domain.apprules.*
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginForegroundTest {
    @Test fun onlyUnlockedAvailableActiveRootCanConfirmForeground() {
        val facts = ForegroundFacts(1, 1, activeRoot = ActiveRootFact("example.app", ForegroundReadState.AVAILABLE))
        assertEquals(Screen.UNLOCKED to Foreground.Confirmed("example.app"), mapForeground(facts, true))
        assertEquals(Screen.LOCKED to Foreground.Unknown, mapForeground(facts.copy(displayState = DisplayState.KEYGUARD), true))
        assertEquals(Screen.UNLOCKED to Foreground.Unknown, mapForeground(facts, false))
        assertEquals(Screen.UNLOCKED to Foreground.Unknown, mapForeground(facts.copy(activeRoot = ActiveRootFact(readState = ForegroundReadState.FAILED), applicationWindows = ApplicationWindowsFact(packages = setOf("example.app"), readState = ForegroundReadState.AVAILABLE)), true))
    }
}
