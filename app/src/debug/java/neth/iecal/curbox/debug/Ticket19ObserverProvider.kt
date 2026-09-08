package neth.iecal.curbox.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Shell-facing controller endpoint. It exists only in debug APKs and accepts only shell UID. */
class Ticket19ObserverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceShellCaller()
        return Bundle().apply {
            putString(
                RESULT_KEY,
                Ticket19ObserverRegistry.command(method, arg?.toLongOrNull() ?: 0L)
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        enforceShellCaller()
        return null
    }

    override fun getType(uri: Uri): String? {
        enforceShellCaller()
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceShellCaller()
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceShellCaller()
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        enforceShellCaller()
        return 0
    }

    private fun enforceShellCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.SHELL_UID) {
            throw SecurityException("ticket19 observer caller must be shell")
        }
    }

    companion object {
        const val RESULT_KEY = "result"
    }
}
