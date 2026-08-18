package neth.iecal.curbox.utils

import android.view.View
import android.view.Window

/**
 * Pure access state shared by guardian protected activities.  A focus transition invalidates
 * this state before any asynchronous settings read can finish, so callers cannot commit a result
 * while the content is still obscured.
 */
class GuardianGateAccess {
    private var authorized = false

    fun authorize(hasPassword: Boolean, sessionAuthenticated: Boolean): Boolean {
        authorized = !hasPassword || sessionAuthenticated
        return authorized
    }

    fun invalidate() {
        authorized = false
    }

    fun canCommit(hasPassword: Boolean, sessionAuthenticated: Boolean): Boolean =
        authorized && (!hasPassword || sessionAuthenticated)
}

/** View and window adapter for [GuardianGateAccess]. */
class GuardianActivityGate(
    window: Window,
    private val contentGate: View
) {
    private val access = GuardianGateAccess()

    init {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        obscure(invalidateAccess = true)
    }

    fun obscure(invalidateAccess: Boolean = true) {
        if (invalidateAccess) access.invalidate()
        contentGate.visibility = View.VISIBLE
        contentGate.isEnabled = true
        contentGate.isClickable = true
        contentGate.isFocusable = true
    }

    fun revealIfAuthorized(hasPassword: Boolean, sessionAuthenticated: Boolean): Boolean {
        if (!access.authorize(hasPassword, sessionAuthenticated)) {
            obscure(invalidateAccess = false)
            return false
        }
        contentGate.visibility = View.GONE
        contentGate.isEnabled = false
        contentGate.isClickable = false
        contentGate.isFocusable = false
        return true
    }

    fun canCommit(hasPassword: Boolean, sessionAuthenticated: Boolean): Boolean =
        access.canCommit(hasPassword, sessionAuthenticated)
}

/** Route policy used before FragmentActivity creates any sensitive content. */
object GuardianRoutePolicy {
    enum class Route {
        ONBOARDING,
        MANAGED
    }

    /** A configured credential protects every route, including an exported onboarding route. */
    fun requiresGate(guardianConfigured: Boolean, route: Route): Boolean = when (route) {
        Route.ONBOARDING,
        Route.MANAGED -> guardianConfigured
    }
}
