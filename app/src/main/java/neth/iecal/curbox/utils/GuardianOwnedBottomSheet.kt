package neth.iecal.curbox.utils

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Base for Curbox-owned settings sheets. Ownership is established before the dialog can become
 * visible and released from every DialogFragment dismissal boundary. Commits re-check the local
 * guardian configuration so an external focus loss cannot save stale sheet state.
 */
abstract class GuardianOwnedBottomSheet : BottomSheetDialogFragment() {
    private var ownershipReleased = true
    private var commitRequested = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        GuardianSessionRegistry.markOwnedDialogShown()
        ownershipReleased = false
        return try {
            super.onCreateDialog(savedInstanceState)
        } catch (error: Throwable) {
            releaseOwnership()
            throw error
        }
    }

    override fun onStart() {
        try {
            super.onStart()
        } catch (error: Throwable) {
            releaseOwnership()
            throw error
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        releaseOwnership()
        super.onCancel(dialog)
    }

    override fun onDismiss(dialog: DialogInterface) {
        releaseOwnership()
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        releaseOwnership()
        super.onDestroyView()
    }

    /** Run a settings write only while the current local guardian session is still valid. */
    protected fun launchGuardianCommit(action: () -> Unit) {
        if (commitRequested) return
        commitRequested = true
        val appContext = context?.applicationContext
        if (appContext == null) {
            commitRequested = false
            return
        }
        lifecycleScope.launch {
            val hasPassword = runCatching {
                DataStoreManager(appContext).settings.first().guardianAuthConfig.isConfigured
            }.getOrNull()
            if (hasPassword == null) {
                commitRequested = false
                return@launch
            }
            if (GuardianSessionRegistry.session.isAuthenticated(hasPassword)) {
                action()
            } else {
                commitRequested = false
            }
        }
    }

    private fun releaseOwnership() {
        if (!ownershipReleased) {
            ownershipReleased = true
            GuardianSessionRegistry.markOwnedDialogHidden()
        }
    }
}
