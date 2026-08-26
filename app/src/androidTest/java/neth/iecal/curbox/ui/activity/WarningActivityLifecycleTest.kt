package neth.iecal.curbox.ui.activity

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarningActivityLifecycleTest {
    @Test
    fun warningIsFinishedAfterItLeavesTheForeground() {
        ActivityScenario.launch<WarningActivity>(warningIntent()).use { scenario ->
            if (scenario.state != Lifecycle.State.DESTROYED) {
                try {
                    scenario.moveToState(Lifecycle.State.CREATED)
                } catch (error: IllegalStateException) {
                    if (scenario.state != Lifecycle.State.DESTROYED) throw error
                }
            }

            if (scenario.state != Lifecycle.State.DESTROYED) {
                scenario.onActivity { warning ->
                    assertTrue(
                        "A backgrounded warning can reappear after its restriction has ended",
                        warning.isFinishing
                    )
                }
            }
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
