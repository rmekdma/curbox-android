package neth.iecal.curbox.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningActivityStopPolicyTest {
    @Test
    fun finishesAnOrdinaryBackgroundedWarning() {
        assertTrue(
            WarningActivityStopPolicy.shouldFinish(
                isChangingConfigurations = false,
                isAwaitingOneShotSystemResult = false
            )
        )
    }

    @Test
    fun keepsWarningForQrScannerResult() {
        assertFalse(
            WarningActivityStopPolicy.shouldFinish(
                isChangingConfigurations = false,
                isAwaitingOneShotSystemResult = true
            )
        )
    }

    @Test
    fun keepsWarningDuringConfigurationChange() {
        assertFalse(
            WarningActivityStopPolicy.shouldFinish(
                isChangingConfigurations = true,
                isAwaitingOneShotSystemResult = false
            )
        )
    }
}
