package neth.iecal.curbox.utils

import android.content.Intent
import java.util.UUID

/** Process-local session and explicit handoff tokens for Curbox-owned activities. */
object GuardianSessionRegistry {
    const val EXTRA_INTERNAL_NAVIGATION_TOKEN =
        "neth.iecal.curbox.extra.INTERNAL_NAVIGATION_TOKEN"
    const val EXTRA_INTERNAL_RETURN_TOKEN =
        "neth.iecal.curbox.extra.INTERNAL_RETURN_TOKEN"

    val session = GuardianAuthSession()

    private const val TOKEN_TTL_MS = 10_000L
    private var pendingInternalToken: Pair<String, Long>? = null
    private var pendingInternalReturnToken: Pair<String, Long>? = null
    private var pendingOwnedTransitionToken: Pair<String, Long>? = null
    private var ownedDialogActive = false
    private var ownedDialogDepth = 0
    private var internalChildActive = false
    private var oneShotSystemResultPending = false

    @Synchronized
    fun issueInternalNavigationToken(): String {
        val token = UUID.randomUUID().toString()
        pendingInternalToken = token to (System.currentTimeMillis() + TOKEN_TTL_MS)
        return token
    }

    @Synchronized
    fun attachInternalNavigationToken(intent: Intent): Intent = intent.apply {
        if (!hasExtra(EXTRA_INTERNAL_NAVIGATION_TOKEN)) {
            putExtra(EXTRA_INTERNAL_NAVIGATION_TOKEN, issueInternalNavigationToken())
        }
    }

    @Synchronized
    fun issueInternalReturnToken(): String {
        val token = UUID.randomUUID().toString()
        pendingInternalReturnToken = token to (System.currentTimeMillis() + TOKEN_TTL_MS)
        return token
    }

    @Synchronized
    fun attachInternalReturnToken(intent: Intent): Intent = intent.apply {
        if (!hasExtra(EXTRA_INTERNAL_RETURN_TOKEN)) {
            putExtra(EXTRA_INTERNAL_RETURN_TOKEN, issueInternalReturnToken())
        }
    }

    @Synchronized
    fun issueOwnedTransitionToken(): String {
        val token = UUID.randomUUID().toString()
        pendingOwnedTransitionToken = token to (System.currentTimeMillis() + TOKEN_TTL_MS)
        return token
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
        if (valid) {
            internalChildActive = true
        } else if (token != null) {
            internalChildActive = false
            session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
        }
        return valid
    }

    @Synchronized
    fun onCurboxActivityStarted(internalNavigationToken: String? = null): Boolean {
        val isInternal = consumeInternalNavigationToken(internalNavigationToken)
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
        return isInternal
    }

    @Synchronized
    fun onCurboxActivityStopped(
        isInternalActivity: Boolean = false,
        isFinishing: Boolean = false
    ): Boolean {
        discardExpiredTokens()
        if (oneShotSystemResultPending ||
            pendingInternalToken?.second.orZero() >= System.currentTimeMillis()
        ) return true
        if (isInternalActivity && isFinishing &&
            pendingInternalReturnToken?.second.orZero() >= System.currentTimeMillis()
        ) return true
        if (isInternalActivity && isFinishing && internalChildActive) {
            issueInternalReturnToken()
            return true
        }
        if (!isInternalActivity && internalChildActive) return true
        internalChildActive = false
        session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
        return false
    }

    @Synchronized
    fun markExternalSystemScreen() {
        discardExpiredTokens()
        if (oneShotSystemResultPending ||
            pendingInternalToken?.isStillValid() == true
        ) return
        pendingInternalReturnToken = null
        pendingOwnedTransitionToken = null
        ownedDialogActive = false
        ownedDialogDepth = 0
        internalChildActive = false
        session.transition(GuardianFocusSurface.EXTERNAL_SYSTEM, hasPassword = true)
    }

    /** Returns true when focus loss invalidated the session rather than an explicit owned handoff. */
    @Synchronized
    fun handleWindowFocusLost(
        ownedTransitionToken: String? = null,
        isInternalActivity: Boolean = false
    ): Boolean {
        discardExpiredTokens()
        if (oneShotSystemResultPending ||
            pendingInternalToken?.isStillValid() == true
        ) return false
        if (!isInternalActivity && internalChildActive) return false
        if (ownedDialogActive) return false
        val owned = ownedTransitionToken != null &&
            pendingOwnedTransitionToken?.first == ownedTransitionToken &&
            pendingOwnedTransitionToken?.second.orZero() >= System.currentTimeMillis()
        if (owned) {
            pendingOwnedTransitionToken = null
            ownedDialogActive = true
            session.transition(GuardianFocusSurface.CURBOX_DIALOG, hasPassword = true)
            return false
        }
        pendingOwnedTransitionToken = null
        pendingInternalReturnToken = null
        ownedDialogActive = false
        ownedDialogDepth = 0
        internalChildActive = false
        session.transition(GuardianFocusSurface.UNKNOWN, hasPassword = true)
        return true
    }

    @Synchronized
    fun consumeInternalReturnToken(token: String?): Boolean {
        val pending = pendingInternalReturnToken
        val valid = token != null && pending != null &&
            pending.first == token &&
            pending.second >= System.currentTimeMillis()
        if (valid) {
            pendingInternalReturnToken = null
            internalChildActive = false
            session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
            return true
        }
        if (token != null) {
            pendingInternalReturnToken = null
            internalChildActive = false
            session.transition(GuardianFocusSurface.EXTERNAL_APP, hasPassword = true)
        }
        return false
    }

    @Synchronized
    fun consumePendingInternalReturnHandoff(): Boolean {
        discardExpiredTokens()
        if (pendingInternalReturnToken == null) return false
        pendingInternalReturnToken = null
        internalChildActive = false
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
        return true
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

    @Synchronized
    fun markOwnedDialogShown() {
        ownedDialogDepth += 1
        ownedDialogActive = true
        session.transition(GuardianFocusSurface.CURBOX_DIALOG, hasPassword = true)
    }

    @Synchronized
    fun markOwnedDialogHidden() {
        if (ownedDialogDepth > 0) ownedDialogDepth -= 1
        if (ownedDialogDepth > 0) return
        pendingOwnedTransitionToken = null
        ownedDialogActive = false
        session.transition(GuardianFocusSurface.CURBOX_CONTENT, hasPassword = true)
    }

    @Synchronized
    fun isOwnedDialogActive(): Boolean = ownedDialogActive

    private fun Long?.orZero(): Long = this ?: 0L

    private fun Pair<String, Long>.isStillValid(): Boolean = second >= System.currentTimeMillis()

    private fun discardExpiredTokens() {
        val now = System.currentTimeMillis()
        if (pendingInternalToken?.second.orZero() < now) pendingInternalToken = null
        if (pendingInternalReturnToken?.second.orZero() < now) pendingInternalReturnToken = null
        if (pendingOwnedTransitionToken?.second.orZero() < now) pendingOwnedTransitionToken = null
    }
}
