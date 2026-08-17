package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleReceiverLifecycleTest {
    @Test
    fun failedRegistrationUnregistersEveryReceiverThatWasRegistered() {
        val events = mutableListOf<String>()
        val lifecycle = AppRuleReceiverLifecycle(
            listOf(
                AppRuleReceiverLifecycle.Registration(
                    register = { events += "register refresh" },
                    unregister = { events += "unregister refresh" }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = {
                        events += "register package"
                        throw IllegalStateException("registration failed")
                    },
                    unregister = { events += "unregister package" }
                )
            )
        )

        var failed = false
        try {
            lifecycle.register()
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(
            listOf("register refresh", "register package", "unregister refresh"),
            events
        )
        assertEquals(emptyList<Exception>(), lifecycle.unregister())
    }
}
