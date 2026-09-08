package neth.iecal.curbox.debug

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Ticket19ObserverBinderTest {
    @Test
    fun nonShellTestApkCannotUseAnyShellProviderEntryPoint() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val providerUri = Uri.parse("content://neth.iecal.curbox.debug.ticket19.observer")
        val resolver = context.contentResolver
        val operations = listOf<() -> Unit>(
            { resolver.call(providerUri, "snapshot", "0", null) },
            { resolver.query(providerUri, null, null, null, null)?.close() },
            { resolver.insert(providerUri, ContentValues()) },
            { resolver.delete(providerUri, null, null) },
            { resolver.update(providerUri, ContentValues(), null, null) }
        )
        operations.forEachIndexed { index, operation ->
            var rejected = false
            try {
                operation()
            } catch (_: SecurityException) {
                rejected = true
            }
            assertTrue("provider operation $index must reject non-shell callers", rejected)
        }

        // ContentResolver normalizes a remote getType SecurityException to null on this API 33
        // device. Verify that transport result and invoke the method directly to prove its UID
        // guard is present rather than mistaking null for provider access.
        assertEquals(null, resolver.getType(providerUri))
        var directGetTypeRejected = false
        try {
            Ticket19ObserverProvider().getType(providerUri)
        } catch (_: SecurityException) {
            directGetTypeRejected = true
        }
        assertTrue("getType must enforce the shell UID guard", directGetTypeRejected)
    }

    @Test
    fun signatureMatchedTestApkCanQueryObserverInServiceProcess() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val connected = CountDownLatch(1)
        var observer: ITicket19Observer? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                observer = ITicket19Observer.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                observer = null
            }
        }
        val intent = Intent().setComponent(
            ComponentName(
                "neth.iecal.curbox.debug",
                "neth.iecal.curbox.debug.Ticket19ObserverService"
            )
        )
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10L, TimeUnit.SECONDS))
            val snapshot = JSONObject(requireNotNull(observer).command("snapshot", 0L))
            assertTrue(snapshot.getInt("processPid") > 0)
            assertEquals(0, snapshot.getJSONArray("failures").length())
        } finally {
            context.unbindService(connection)
        }
    }
}
