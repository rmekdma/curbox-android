package neth.iecal.curbox.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Shell-facing controller endpoint. It exists only in debug APKs and rejects non-shell callers. */
class Ticket19ObserverProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceDebugCaller()
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
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun enforceDebugCaller() {
        val callerUid = Binder.getCallingUid()
        val ownUid = Process.myUid()
        if (callerUid == Process.SHELL_UID || callerUid == ownUid) return
        val packageManager = requireNotNull(context).packageManager
        if (packageManager.checkSignatures(ownUid, callerUid) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("ticket19 observer caller must be shell or signature matched")
        }
    }

    companion object {
        const val RESULT_KEY = "result"
    }
}
