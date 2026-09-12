package neth.iecal.curbox.utils

import android.app.Dialog
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows a Curbox-owned dialog while retaining the guardian session.  All dismissal paths release
 * the ownership marker, including cancel, outside/back dismissal, and a failure while showing.
 */
object GuardianOwnedDialog {
    fun <T : Dialog> show(
        dialog: T,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ): T {
        GuardianSessionRegistry.markOwnedDialogShown()
        var released = false
        fun release() {
            if (!released) {
                released = true
                GuardianSessionRegistry.markOwnedDialogHidden()
                onDismiss?.invoke()
            }
        }

        try {
            dialog.setOnCancelListener {
                onCancel?.invoke()
                release()
            }
            dialog.setOnDismissListener { release() }
            dialog.show()
        } catch (error: Throwable) {
            release()
            throw error
        }
        return dialog
    }

    /**
     * Re-check the local credential state immediately before a dialog callback mutates settings.
     * A missing credential remains auth-free, while an external return invalidates configured
     * sessions before the action is run.
     */
    fun launchCommit(context: Context, scope: CoroutineScope, action: () -> Unit) {
        val appContext = context.applicationContext
        scope.launch {
            val hasPassword = runCatching {
                DataStoreManager(appContext).settings.first().guardianAuthConfig.isConfigured
            }.getOrNull() ?: return@launch
            if (GuardianSessionRegistry.session.isAuthenticated(hasPassword)) action()
        }
    }
}
