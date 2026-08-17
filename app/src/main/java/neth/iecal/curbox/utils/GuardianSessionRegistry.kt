package neth.iecal.curbox.utils

import android.content.Intent
import java.util.UUID

/** Process-local session and explicit handoff tokens for Curbox-owned activities. */
object GuardianSessionRegistry {
    const val EXTRA_INTERNAL_NAVIGATION_TOKEN =
        "neth.iecal.curbox.extra.INTERNAL_NAVIGATION_TOKEN"

    val session = GuardianAuthSession()

    private const val TOKEN_TTL_MS = 10_000L
    private var pendingInternalToken: Pair<String, Long>? = null
    private var oneShotSystemResultPending = false

    @Synchronized
    fun issueInternalNavigationToken(): String {
        val token = UUID.randomUUID().toString()
        pendingInternalToken = token to (System.currentTimeMillis() + TOKEN_TTL_MS)
        return token
    }

    @Synchronized
    fun attachInternalNavigationToken(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_INTERNAL_NAVIGATION_TOKEN, issueInternalNavigationToken())
    }

    @Synchronized
    fun consumeInternalNavigationToken(token: String?): Boolean {
        val pending = pendingInternalToken
        val valid = pending != null &&
            pending.first == token &&
            pending.second >= System.currentTimeMillis()
        if (valid || pending?.second.orZero() < System.currentTimeMillis()) {
            pendingInternalToken = null
        }
        return valid
    }

    fun onCurboxActivityStarted(internalNavigationToken: String? = null) {
        consumeInternalNavigationToken(internalNavigationToken)
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
    }

    @Synchronized
    fun onCurboxActivityStopped() {
        if (pendingInternalToken?.second.orZero() >= System.currentTimeMillis() ||
            oneShotSystemResultPending
        ) return
        session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
    }

    @Synchronized
    fun markExternalSystemScreen() {
        if (oneShotSystemResultPending || pendingInternalToken?.isStillValid() == true) return
        session.transition(GuardianFocusSurface.EXTERNAL_SYSTEM, hasPassword = true)
    }

    @Synchronized
    fun markOneShotSystemResult() {
        oneShotSystemResultPending = true
        session.markOneShotSystemResult()
    }

    @Synchronized
    fun completeOneShotSystemResult() {
        if (!oneShotSystemResultPending) return
        oneShotSystemResultPending = false
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
    }

    @Synchronized
    fun isAwaitingOneShotSystemResult(): Boolean = oneShotSystemResultPending

    fun markOwnedDialogShown() {
        session.transition(GuardianFocusSurface.CURBOX_DIALOG, hasPassword = true)
    }

    fun markOwnedDialogHidden() {
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun Pair<String, Long>.isStillValid(): Boolean = second >= System.currentTimeMillis()
}
