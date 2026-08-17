package neth.iecal.curbox.utils

import android.os.Handler
import android.os.Looper

/** Process local session shared by Curbox management activities. */
object GuardianSessionRegistry {
    val session = GuardianAuthSession()
    private val handler = Handler(Looper.getMainLooper())
    private var internalNavigationUntilMs = 0L

    fun onCurboxActivityStarted() {
        internalNavigationUntilMs = System.currentTimeMillis() + INTERNAL_NAVIGATION_GRACE_MS
        handler.removeCallbacksAndMessages(null)
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
    }

    fun onCurboxActivityStopped() {
        val deadline = System.currentTimeMillis() + INTERNAL_NAVIGATION_GRACE_MS
        internalNavigationUntilMs = maxOf(internalNavigationUntilMs, deadline)
        handler.postDelayed({
            if (System.currentTimeMillis() >= internalNavigationUntilMs) {
                session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
            }
        }, INTERNAL_NAVIGATION_GRACE_MS)
    }

    fun markExternalSystemScreen() {
        session.transition(GuardianFocusSurface.EXTERNAL_SYSTEM, hasPassword = true)
    }

    private const val INTERNAL_NAVIGATION_GRACE_MS = 1_500L
}
