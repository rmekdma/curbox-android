package neth.iecal.curbox.services

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBlockerServiceTest {
    @Test
    fun appRuleCancellationIsContainedWithoutBecomingCrashReport() {
        val cancellations = CopyOnWriteArrayList<Throwable>()
        val nonFatalReports = CopyOnWriteArrayList<Throwable>()

        runAppRuleCheckAtSynchronousServiceBoundary(
            action = { throw CancellationException("injected app-rule cancellation") },
            onCancellation = { cancellations += it },
            onNonFatal = { nonFatalReports += it }
        )

        assertEquals(1, cancellations.size)
        assertTrue(nonFatalReports.isEmpty())
    }
}
