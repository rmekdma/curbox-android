package neth.iecal.curbox.debug

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process

class Ticket19ObserverService : Service() {
    private val binder = object : ITicket19Observer.Stub() {
        override fun command(name: String, argument: Long): String {
            enforceSignatureCaller()
            return Ticket19ObserverRegistry.command(name, argument)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun enforceSignatureCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return
        if (packageManager.checkSignatures(Process.myUid(), callerUid) !=
            PackageManager.SIGNATURE_MATCH
        ) {
            throw SecurityException("ticket19 observer caller signature does not match")
        }
    }
}
