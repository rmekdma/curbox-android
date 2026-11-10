package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.testing.AccessibilityFrameworkTestObjects
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Phase 0 RED contract for AR005 wall-clock recovery after virtual doze. */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerVirtualDozeWakeRedTest {
    @Test
    fun ar005_wallBoundaryDuringVirtualDoze_requiresWakeRecoveryAndNoRetroactiveUsage() {
        assertWakeRecovery(keyguardAtWake = false)
    }

    @Test
    fun ar005_lockedWakeWaitsForUserPresentAndDoesNotRecordSleepUsage() {
        assertWakeRecovery(keyguardAtWake = true)
    }

    private fun assertWakeRecovery(keyguardAtWake: Boolean) {
        val clock = VirtualClock(
            wallClockMs = BASE_TIME_MS,
            elapsedRealtimeMs = INITIAL_ELAPSED_REALTIME_MS,
            schedulerClockMs = INITIAL_SCHEDULER_CLOCK_MS
        )
        val scheduler = VirtualScheduler(clock)
        val repository = RecordingSessionRepository(clock)
        val service = RecordingService(clock).also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        service.lastBackPressTimeStamp = 0L
        val snapshot = snapshotWithNextMinuteBoundary(clock.wallClockMs)
        val decisions = mutableListOf<DecisionObservation>()
        var screenInteractive = true
        var rejectDisplayRead = false
        var keyguardLocked = false
        val blocker = AppRuleBlocker(wakeScheduler = scheduler).apply {
            wallClockMsProvider = { clock.wallClockMs }
            elapsedRealtimeMsProvider = { clock.elapsedRealtimeMs }
            visibleApplicationCheckPostDelayed = { runnable, delayMs ->
                scheduler.post("wake", runnable, delayMs)
            }
            screenInteractiveProvider = {
                check(!rejectDisplayRead) {
                    "SCREEN_OFF must not depend on a fresh display-state read"
                }
                screenInteractive
            }
            keyguardLockedProvider = { keyguardLocked }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
            }
            evaluationResultObserver = { evaluation ->
                decisions += DecisionObservation(
                    wallClockMs = clock.wallClockMs,
                    elapsedRealtimeMs = clock.elapsedRealtimeMs,
                    schedulerClockMs = clock.schedulerClockMs,
                    evaluation = evaluation
                )
            }
        }

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(snapshot)

        try {
            sendWindowEvent(blocker)
            assertTrue(
                "the initial serialized decision must complete",
                awaitCondition { decisions.size == 1 }
            )

            assertEquals(1, decisions.size)
            assertTrue("the rule is inactive before its next wall-clock boundary", decisions.single().evaluation.isAllowed)
            assertTrue(
                "the initial decision must publish its wall-clock boundary",
                awaitCondition { scheduler.dueAt(TARGET_PACKAGE) == BASE_TIME_MS + BOUNDARY_DELAY_MS }
            )
            val sleepStartedWallClockMs = clock.wallClockMs
            val readsBeforeSleep = repository.readHistory.size
            val mutationsBeforeScreenOff = repository.mutationCount
            screenInteractive = false
            rejectDisplayRead = true
            sendScreenAction(blocker, Intent.ACTION_SCREEN_OFF)
            assertTrue(
                "screen off must finish the visible session before virtual sleep",
                awaitCondition { repository.mutationCount > mutationsBeforeScreenOff }
            )
            val mutationsBeforeSleep = repository.mutationHistory.size
            assertEquals("screen off must cancel the app boundary", null, scheduler.dueAt(TARGET_PACKAGE))

            // Wall and elapsed time pass during doze, while Handler-style uptime does not move.
            // The separately advanced values make the no-retroactive-use boundary observable.
            clock.advanceWallBy(SLEEP_DURATION_MS)
            clock.advanceElapsedBy(SLEEP_DURATION_MS)
            scheduler.runDue()

            assertEquals(BASE_TIME_MS + SLEEP_DURATION_MS, clock.wallClockMs)
            assertEquals(INITIAL_ELAPSED_REALTIME_MS + SLEEP_DURATION_MS, clock.elapsedRealtimeMs)
            assertEquals(INITIAL_SCHEDULER_CLOCK_MS, clock.schedulerClockMs)
            assertEquals(readsBeforeSleep, repository.readHistory.size)
            assertEquals(1, decisions.size)
            assertTrue("doze must not launch a guardian without a wake recovery", service.startedActivities.isEmpty())
            assertTrue(repository.mutationHistory.all { it <= sleepStartedWallClockMs })

            val wakeWallClockMs = clock.wallClockMs
            val wakeElapsedRealtimeMs = clock.elapsedRealtimeMs
            val failures = mutableListOf<String>()

            rejectDisplayRead = false
            screenInteractive = true
            keyguardLocked = keyguardAtWake
            val decisionsBeforeWake = decisions.size
            val activitiesBeforeWake = service.startedActivities.size
            sendScreenAction(blocker, Intent.ACTION_SCREEN_ON)
            scheduler.advanceBy(USER_PRESENT_RECOVERY_DELAY_MS)

            if (keyguardAtWake) {
                assertEquals("keyguard must defer decisions", decisionsBeforeWake, decisions.size)
                assertEquals("keyguard must not launch a guardian", activitiesBeforeWake, service.startedActivities.size)
                keyguardLocked = false
                sendScreenAction(blocker, Intent.ACTION_USER_PRESENT)
                scheduler.advanceBy(USER_PRESENT_RECOVERY_DELAY_MS)
            }

            assertTrue(
                "wake recovery must evaluate the current target",
                awaitCondition { decisions.size > decisionsBeforeWake }
            )
            assertTrue(
                "wake recovery must publish the denial activity",
                awaitCondition { service.startedActivities.size > activitiesBeforeWake }
            )
            val recovery = decisions.last()
            assertEquals(wakeWallClockMs, recovery.wallClockMs)
            assertEquals(wakeElapsedRealtimeMs, recovery.elapsedRealtimeMs)
            if (recovery.evaluation.isAllowed) {
                failures += "wake recovery allowed the active target restriction"
            }
            if (recovery.evaluation.denyingRules.none { it.ruleId == TARGET_RULE_ID }) {
                failures += "wake recovery did not name the target rule"
            }
            val denialPayload = service.startedActivities.last().intent
                .getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS).orEmpty()
            if (TARGET_RULE_ID !in denialPayload) {
                failures += "the wake guardian did not contain the target denial"
            }

            // A post-wake session may start at the wake wall time. Only mutations strictly inside
            // the screen-off interval would be retroactive sleep usage.
            if (repository.mutationHistory.drop(mutationsBeforeSleep).any {
                    it > sleepStartedWallClockMs && it < wakeWallClockMs
                }
            ) {
                failures += "virtual doze caused a session mutation"
            }
            if (!repository.readHistory
                    .flatMap { it.sessions }
                    .filter { it.packageName == TARGET_PACKAGE }
                    .all { session ->
                        session.endedAtMs != null && session.endedAtMs!! <= sleepStartedWallClockMs
                    }
            ) {
                failures += "virtual doze was recorded as target usage"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "AR005 RED contract failures:\n" +
                        failures.joinToString(separator = "\n") { "- $it" }
                )
            }
        } finally {
            blocker.onDestroy()
        }
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(10L)
        }
        return condition()
    }

    private fun snapshotWithNextMinuteBoundary(nowMs: Long): AppRuleSnapshot {
        val zone = ZoneId.systemDefault()
        val localNow = Instant.ofEpochMilli(nowMs).atZone(zone)
        val startMinute = localNow.hour * 60 + localNow.minute + 1
        val group = AppRuleAppGroup(
            id = TARGET_GROUP_ID,
            name = "Target",
            selectedPackages = listOf(TARGET_PACKAGE)
        )
        return AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(
                AppRule(
                    id = TARGET_RULE_ID,
                    name = "Next minute restriction",
                    weekdays = setOf(localNow.dayOfWeek.value % 7),
                    startMinute = startMinute,
                    endMinute = (startMinute + 1).coerceAtMost(24 * 60),
                    appGroupId = TARGET_GROUP_ID,
                    allowedMinutes = 0L
                )
            )
        )
    }

    private fun sendWindowEvent(blocker: AppRuleBlocker) {
        val event = AccessibilityFrameworkTestObjects.createEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        try {
            event.packageName = TARGET_PACKAGE
            blocker.doAppRuleCheck(event)
        } finally {
            AccessibilityFrameworkTestObjects.releaseEvent(event)
        }
    }

    private fun sendScreenAction(blocker: AppRuleBlocker, action: String) {
        val receiver = getField(blocker, "screenReceiver") as BroadcastReceiver
        receiver.onReceive(null, Intent(action))
    }

    private data class DecisionObservation(
        val wallClockMs: Long,
        val elapsedRealtimeMs: Long,
        val schedulerClockMs: Long,
        val evaluation: AppRulesEvaluation
    )

    private data class ScheduledPost(
        val label: String,
        val delayMs: Long,
        val postedAtSchedulerClockMs: Long
    )

    private class VirtualClock(
        var wallClockMs: Long,
        var elapsedRealtimeMs: Long,
        var schedulerClockMs: Long
    ) {
        fun advanceWallBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            wallClockMs += deltaMs
        }

        fun advanceElapsedBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            elapsedRealtimeMs += deltaMs
        }

        fun advanceSchedulerBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            schedulerClockMs += deltaMs
        }
    }

    private class VirtualScheduler(private val clock: VirtualClock) : AppRuleWakeScheduler {
        override var onWake: ((String, Long) -> Unit)? = null
        private val wakes = mutableMapOf<String, Pair<Long, Long>>()

        override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
            synchronized(wakes) { wakes[key] = dueAtWallClockMs to token }
        }

        override fun cancel(key: String) {
            synchronized(wakes) { wakes.remove(key) }
        }

        override fun cancelAll() {
            synchronized(wakes) { wakes.clear() }
        }

        fun dueAt(key: String): Long? = synchronized(wakes) { wakes[key]?.first }

        private data class Task(
            val label: String,
            val dueAtSchedulerClockMs: Long,
            val sequence: Long,
            val runnable: Runnable
        )

        private val tasks = mutableListOf<Task>()
        private var nextSequence = 0L
        val posts = mutableListOf<ScheduledPost>()

        fun post(label: String, runnable: Runnable, delayMs: Long): Boolean {
            posts += ScheduledPost(label, delayMs, clock.schedulerClockMs)
            tasks += Task(
                label = label,
                dueAtSchedulerClockMs = clock.schedulerClockMs + delayMs,
                sequence = nextSequence++,
                runnable = runnable
            )
            return true
        }

        fun remove(runnable: Runnable) {
            tasks.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMs: Long) {
            clock.advanceSchedulerBy(deltaMs)
            runDue()
        }

        fun runDue() {
            while (true) {
                val next = tasks
                    .filter { it.dueAtSchedulerClockMs <= clock.schedulerClockMs }
                    .minWithOrNull(compareBy<Task> { it.dueAtSchedulerClockMs }.thenBy { it.sequence })
                    ?: return
                tasks.removeAt(tasks.indexOfFirst { it === next })
                next.runnable.run()
            }
        }
    }

    private class RecordingSessionRepository(private val clock: VirtualClock) : CurrentUseDaySessionRepository {
        private val zone = ZoneId.systemDefault()
        private val calculator = ConfigurableUseDayCalculator(zone)
        private val useDayId = calculator.idAt(BASE_TIME_MS)
        val readHistory = mutableListOf<SessionRead>()
        val mutationHistory = mutableListOf<Long>()
        var mutationCount = 0
            private set

        private fun recordMutation() {
            mutationCount++
            mutationHistory += clock.wallClockMs
        }

        private val sessions = listOf(
            ForegroundSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = BASE_TIME_MS - 5_000L,
                endedAtMs = BASE_TIME_MS
            )
        )

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            recordMutation()
            return 1L
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            recordMutation()
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            recordMutation()
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            readHistory += SessionRead(
                wallClockMs = clock.wallClockMs,
                sessions = sessions
            )
            return sessions
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
            mutationCount++
        }
    }

    private data class SessionRead(
        val wallClockMs: Long,
        val sessions: List<ForegroundSession>
    )

    private class RecordingService(private val clock: VirtualClock) : BaseBlockingService() {
        val startedActivities = mutableListOf<StartedActivity>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += StartedActivity(
                wallClockMs = clock.wallClockMs,
                intent = intent
            )
        }
    }

    private data class StartedActivity(
        val wallClockMs: Long,
        val intent: Intent
    )

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val TARGET_GROUP_ID = "target-group"
        const val TARGET_RULE_ID = "target-rule"
        const val BOUNDARY_DELAY_MS = 30_000L
        const val SLEEP_DURATION_MS = 35_000L
        const val USER_PRESENT_RECOVERY_DELAY_MS = 300L
        const val WAIT_TIMEOUT_MS = 2_000L
        const val INITIAL_ELAPSED_REALTIME_MS = 100_000L
        const val INITIAL_SCHEDULER_CLOCK_MS = 500_000L
        val BASE_TIME_MS = Instant.parse("2026-08-31T10:00:30Z").toEpochMilli()
    }
}
