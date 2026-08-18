package neth.iecal.curbox.utils

import android.app.Dialog

/**
 * Shows a Curbox-owned dialog while retaining the guardian session.  All dismissal paths release
 * the ownership marker, including cancel, outside/back dismissal, and a failure while showing.
 */
object GuardianOwnedDialog {
    fun <T : Dialog> show(dialog: T): T {
        GuardianSessionRegistry.markOwnedDialogShown()
        var released = false
        fun release() {
            if (!released) {
                released = true
                GuardianSessionRegistry.markOwnedDialogHidden()
            }
        }

        try {
            dialog.setOnCancelListener { release() }
            dialog.setOnDismissListener { release() }
            dialog.show()
        } catch (error: Throwable) {
            release()
            throw error
        }
        return dialog
    }
}
