package neth.iecal.curbox.domain.apprules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRuleReceiverLifecycleTest {
    @Test
    fun notReadyRegistrationDoesNotTouchReceiversOrRequireCleanup() {
        val events = mutableListOf<String>()
        val lifecycle = AppRuleReceiverLifecycle(
            registrations = listOf(
                AppRuleReceiverLifecycle.Registration(
                    register = { events += "register refresh" },
                    unregister = { events += "unregister refresh" }
                ),
                AppRuleReceiverLifecycle.Registration(
                    register = { events += "register package" },
                    unregister = { events += "unregister package" }
                )
            ),
            isReady = { false }
        )

        lifecycle.register()

        assertEquals(emptyList<String>(), events)
        assertEquals(emptyList<Exception>(), lifecycle.unregister())
    }

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
