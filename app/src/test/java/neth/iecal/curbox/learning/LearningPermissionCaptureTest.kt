package neth.iecal.curbox.learning

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LearningPermissionCaptureTest {
    @Test fun `capture failure revokes a granted observation and later recovery advances revision`() {
        var granted = true
        var fail = false
        val errors = mutableListOf<Exception>()
        val capture = LearningPermissionCapture({ if (fail) throw IllegalStateException("failed") else granted }, errors::add)
        capture.refresh(false)
        val before = capture.state.value
        fail = true
        capture.refresh(false)
        assertFalse(capture.state.value.granted)
        assertTrue(capture.state.value.revision > before.revision)
        assertEquals(1, errors.size)
        fail = false
        capture.refresh(false)
        assertTrue(capture.state.value.granted)
        assertTrue(capture.state.value.revision > before.revision)
    }
    @Test(expected = CancellationException::class) fun `cancellation is propagated`() {
        LearningPermissionCapture({ throw CancellationException() }, {}).refresh(false)
    }
}
