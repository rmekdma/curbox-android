package neth.iecal.curbox.learning

import neth.iecal.plugin.Connection
import neth.iecal.plugin.Foreground
import neth.iecal.plugin.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LearningHostObservationsTest {
    @Test fun screenOffAndCallbackSurviveAnOlderBlockedCapture() {
        val observations = LearningHostObservations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            observations.capture {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                LearningHostCapture(true, Screen.UNLOCKED, Foreground.Confirmed("com.enuma.todomath"), 1)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val transitionDone = CountDownLatch(1)
            val transition = thread {
                observations.screenChanged(Screen.OFF)
                observations.event(false)
                transitionDone.countDown()
            }
            assertTrue(transitionDone.await(2, TimeUnit.SECONDS))
            transition.join(5000)
            release.countDown()
            worker.join(5000)
            assertEquals(Screen.OFF, observations.observations.value.screen)
            assertEquals(Foreground.Unknown, observations.observations.value.foreground)
            assertTrue(observations.observations.value.callbackReceived)
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun stopSurvivesAnOlderBlockedCapture() {
        val observations = LearningHostObservations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            observations.capture {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                LearningHostCapture(true, Screen.UNLOCKED, Foreground.Confirmed("com.enuma.todomath"), 1)
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val transitionDone = CountDownLatch(1)
            val transition = thread {
                observations.stop()
                observations.event(true)
                transitionDone.countDown()
            }
            assertTrue(transitionDone.await(2, TimeUnit.SECONDS))
            transition.join(5000)
            release.countDown()
            worker.join(5000)
            assertEquals(Connection.DISCONNECTED, observations.observations.value.connection)
            assertEquals(Screen.UNKNOWN, observations.observations.value.screen)
            assertEquals(Foreground.Unknown, observations.observations.value.foreground)
        } finally { release.countDown(); worker.join(5000) }
    }
}
