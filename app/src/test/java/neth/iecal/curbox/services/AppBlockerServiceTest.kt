package neth.iecal.curbox.services

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockerServiceTest {
    @Test
    fun appRuleCancellationDoesNotBlockLaterSynchronousEventHandlingOrBecomeCrashReport() {
        val handled = CopyOnWriteArrayList<String>()
        val cancellations = CopyOnWriteArrayList<Throwable>()
        val nonFatalReports = CopyOnWriteArrayList<Throwable>()

        runAppRuleCheckAtSynchronousServiceBoundary(
            action = {
                handled += "app-rule"
                throw CancellationException("injected app-rule cancellation")
            },
            onCancellation = { cancellations += it },
            onNonFatal = { nonFatalReports += it }
        )
        handled += "mindful"
        handled += "event-channel"

        assertEquals(listOf("app-rule", "mindful", "event-channel"), handled)
        assertEquals(1, cancellations.size)
        assertTrue(nonFatalReports.isEmpty())
    }
}
