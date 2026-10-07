package neth.iecal.curbox.testing

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

internal object AccessibilityFrameworkTestObjects {
    fun createEvent(eventType: Int): AccessibilityEvent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityEvent(eventType)
        } else {
            obtainEventFromPool(eventType)
        }

    fun releaseEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            recyclePooledEvent(event)
        }
    }

    fun createNodeInfo(): AccessibilityNodeInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityNodeInfo()
        } else {
            obtainNodeInfoFromPool()
        }

    @Suppress("DEPRECATION")
    private fun obtainEventFromPool(eventType: Int): AccessibilityEvent =
        AccessibilityEvent.obtain(eventType)

    @Suppress("DEPRECATION")
    private fun recyclePooledEvent(event: AccessibilityEvent) {
        event.recycle()
    }

    @Suppress("DEPRECATION")
    private fun obtainNodeInfoFromPool(): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain()
}
