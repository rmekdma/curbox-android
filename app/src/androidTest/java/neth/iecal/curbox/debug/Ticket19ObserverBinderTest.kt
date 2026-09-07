package neth.iecal.curbox.debug

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
