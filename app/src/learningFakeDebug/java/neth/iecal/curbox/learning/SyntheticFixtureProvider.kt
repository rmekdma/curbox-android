package neth.iecal.curbox.learning

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import neth.iecal.curbox.BuildConfig

/** Shell-only synthetic fixture replacement for fake debug builds. */
class SyntheticFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun guard() {
        check(BuildConfig.DEBUG && BuildConfig.LEARNING_FAKE_ONLY && context?.packageName == "neth.iecal.curbox.debug")
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("shell only")
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        guard()
        return Bundle().apply {
            try {
                when (method) {
                    "write" -> putString("revision", SyntheticFixtureFile.write(requireNotNull(context), requireNotNull(arg)))
                    "reset" -> SyntheticFixtureFile.reset(requireNotNull(context))
                    else -> throw IllegalArgumentException()
                }
                putBoolean("ok", true)
            } catch (_: Exception) { putBoolean("ok", false) }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { guard(); return null }
    override fun getType(uri: Uri): String? { guard(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { guard(); return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { guard(); return 0 }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { guard(); return 0 }
}
