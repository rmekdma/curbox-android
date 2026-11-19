package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleRestConfig
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.FakeWakeScheduler
import neth.iecal.curbox.testing.AccessibilityFrameworkTestObjects
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppRuleBlockerRestRuleTest {
    @Test
    fun switchingTargetAppsKeepsOneUsageLimitAndDismissesAfterRest() {
        RestService().use { fixture ->
            fixture.observe(READER)
            fixture.advance(40_000L)
            fixture.observe(GAME)
            fixture.awaitWakeIn(20_000L)
            fixture.advance(19_999L)
            assertNull(fixture.approval.get())

            fixture.advance(1L)
            val request = fixture.awaitApproval(GAME)
            fixture.openApproval(request)
            fixture.observe(fixture.service.packageName)
            fixture.awaitWakeIn(60_000L)

            fixture.advance(59_999L)
            assertNull(fixture.dismissal())
            fixture.advance(1L)
            fixture.awaitDismissal(request)

            fixture.closeApproval(request, GAME)
            fixture.awaitWakeIn(60_000L)
            fixture.advance(60_000L)
            fixture.awaitApproval(GAME)
        }
    }

    @Test
    fun screenOffCountsAsRestEvenWithAStaleTargetWindow() {
        RestService().use { fixture ->
            fixture.observe(READER)
            fixture.awaitWakeIn(60_000L)
            fixture.advance(60_000L)
            val request = fixture.awaitApproval(READER)
            fixture.openApproval(request)

            fixture.screenOff()
            fixture.awaitWakeIn(60_000L)
            fixture.advance(59_999L)
            assertNull(fixture.dismissal())
            fixture.advance(1L)
            fixture.awaitDismissal(request)
        }
    }

    @Test
    fun qualifyingRestFragmentsResetBeforeTheLimitIsReached() {
        RestService(AppRuleRestConfig(60L, 10L, 5L)).use { fixture ->
            fixture.observe(READER)
            fixture.advance(40 * MINUTE)
            repeat(2) {
                fixture.observe(fixture.service.packageName)
                fixture.advance(2 * MINUTE)
                fixture.observe(READER)
                fixture.advance(MINUTE)
            }
            fixture.observe(fixture.service.packageName)
            fixture.awaitWakeIn(MINUTE)
            fixture.advance(MINUTE)
            fixture.observe(READER)
            fixture.awaitWakeIn(60 * MINUTE)
            fixture.advance(60 * MINUTE - 1L)
            assertNull(fixture.approval.get())
            fixture.advance(1L)
            fixture.awaitApproval(READER)
        }
    }

    private class RestService(
        restConfig: AppRuleRestConfig = AppRuleRestConfig(1L, 2L, 1L)
    ) : AutoCloseable {
        val service = RecordingService().also { it.attach(InstrumentationContext.context) }
        val approval = AtomicReference<Intent?>()
        private val clock = FakeWakeScheduler(initialWallClockMs = System.currentTimeMillis())
        private val processed = AtomicInteger()
        @Volatile private var rootPackage = READER
        @Volatile private var interactive = true

        private val blocker = AppRuleBlocker(wakeScheduler = clock).apply {
            wallClockMsProvider = { clock.currentWallClockMs }
            elapsedRealtimeMsProvider = { clock.currentElapsedRealtimeMs }
            screenInteractiveProvider = { interactive }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = { AppRuleBlocker.ActiveWindowSnapshot(rootPackage) }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(rootPackage),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            decisionOutcomeSinkObserver = { if (it is DecisionOutcome.EnforcementOutcome) processed.incrementAndGet() }
        }

        init {
            val repository = EmptySessions()
            service.lastBackPressTimeStamp = 0L
            service.startActivityObserver = { intent -> approval.compareAndSet(null, intent) }
            setField(blocker, "service", service)
            setField(blocker, "sessionRepository", repository)
            setField(blocker, "enforcement", AppRuleEnforcement(repository))
            setField(blocker, "setupReady", true)
            setField(blocker, "launchablePackages", setOf(READER, GAME))
            setField(blocker, "serviceConnectionId", CONNECTION)
            val snapshots = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
            snapshots.accept(
                AppRuleSnapshot(
                    appRules = listOf(
                        AppRule(
                            id = "break",
                            name = "Rest",
                            scope = AppRuleScope.allApps(),
                            restConfig = restConfig,
                            guardianExtraTimeAllowed = false
                        )
                    )
                )
            )
            clock.onWake = blocker::onWakeFromScheduler
        }

        fun observe(packageName: String) {
            val before = processed.get()
            rootPackage = packageName
            val event = AccessibilityFrameworkTestObjects.createEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
            try {
                event.packageName = packageName
                blocker.doAppRuleCheck(event)
            } finally {
                AccessibilityFrameworkTestObjects.releaseEvent(event)
            }
            await("foreground observation for $packageName") { processed.get() > before }
        }

        fun advance(millis: Long) = clock.advanceTimeBy(millis)

        fun awaitWakeIn(millis: Long) {
            val expectedTime = clock.currentWallClockMs + millis
            await("app rule wake at $expectedTime") {
                clock.scheduledKeys().any { clock.getScheduled(it)?.dueAtWallClockMs == expectedTime }
            }
        }

        fun awaitApproval(packageName: String): Intent {
            await("guardian approval for $packageName") { approval.get() != null }
            return checkNotNull(approval.get()).also {
                assertEquals(packageName, it.getStringExtra(GuardianApprovalActivity.EXTRA_PACKAGE))
            }
        }

        fun openApproval(intent: Intent) {
            receiveApprovalLifecycle(GuardianApprovalActivity.INTENT_ACTION_OPENED, intent)
        }

        fun closeApproval(intent: Intent, visiblePackage: String) {
            approval.set(null)
            rootPackage = visiblePackage
            receiveApprovalLifecycle(GuardianApprovalActivity.INTENT_ACTION_CLOSED, intent)
        }

        private fun receiveApprovalLifecycle(action: String, intent: Intent) {
            val receiver = getField(blocker, "guardianReceiver") as BroadcastReceiver
            receiver.onReceive(
                service,
                guardianLifecycleIntent(
                    action = action,
                    packageName = requireNotNull(intent.getStringExtra(GuardianApprovalActivity.EXTRA_PACKAGE)),
                    screenRequestId = requireNotNull(intent.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)),
                    connectionId = CONNECTION
                ).putExtra(
                    GuardianApprovalActivity.EXTRA_CLOSE_REASON,
                    GuardianApprovalActivity.REASON_AUTO_DISMISSED
                )
            )
        }

        fun screenOff() {
            val before = processed.get()
            interactive = false
            val receiver = getField(blocker, "screenReceiver") as BroadcastReceiver
            receiver.onReceive(service, Intent(Intent.ACTION_SCREEN_OFF))
            await("screen off observation") { processed.get() > before }
        }

        fun dismissal(): Intent? = service.sentBroadcasts.firstOrNull {
            it.action == GuardianApprovalActivity.INTENT_ACTION_AUTO_DISMISS
        }

        fun awaitDismissal(request: Intent) {
            await("dismissal for the current guardian") { dismissal() != null }
            assertEquals(
                request.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID),
                dismissal()?.getStringExtra(GuardianApprovalActivity.EXTRA_SCREEN_REQUEST_ID)
            )
        }

        private fun await(description: String, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10L)
            assertTrue("No $description", ready())
        }

        override fun close() = blocker.onDestroy()
    }

    private class EmptySessions : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L
        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit
        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit
        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()
        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private companion object {
        const val READER = "com.example.reader"
        const val GAME = "com.example.game"
        const val CONNECTION = "rest-test-connection"
        const val MINUTE = 60_000L
    }
}
