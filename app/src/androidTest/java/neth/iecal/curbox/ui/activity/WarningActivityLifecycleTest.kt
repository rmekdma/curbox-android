package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarningActivityLifecycleTest {
    @Test
    fun warningIsFinishedAfterItLeavesTheForeground() {
        ActivityScenario.launch<WarningActivity>(warningIntent()).use { scenario ->
            assertEquals("the warning must start in the foreground", Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { warning ->
                assertFalse("the foreground warning must not already be finishing", warning.isFinishing)
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50L)
            }
            assertEquals(
                "a backgrounded warning must finish so it cannot reappear after its restriction ends",
                Lifecycle.State.DESTROYED,
                scenario.state
            )
        }
    }

    private fun warningIntent(): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, WarningActivity::class.java).apply {
            putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
            putExtra("launch_package", context.packageName)
            putExtra(
                "warning_config",
                Gson().toJson(AppBlockerWarningScreenConfig())
            )
        }
    }
}
