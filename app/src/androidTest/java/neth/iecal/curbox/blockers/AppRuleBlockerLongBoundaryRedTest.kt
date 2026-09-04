package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AppRuleBlockerLongBoundaryRedTest {
    @Test
    fun ar002R05_staleWindowAfterThirtySecondGrantUsesPersistedDecisionAndWarning() {
        assertBoundaryContract(WindowState.STALE_WINDOW)
    }

    @Test
    fun ar002R05_emptyWindowAfterThirtySecondGrantUsesPersistedDecisionAndWarning() {
        assertBoundaryContract(WindowState.EMPTY_WINDOW)
    }

    @Test
    fun ar002R05_nullRootAfterThirtySecondGrantUsesPersistedDecisionAndWarning() {
        assertBoundaryContract(WindowState.NULL_ROOT)
    }

    @Test
    fun successfulWindowThenUncertainReadsCarryStaleProvenanceToBoundedR5Decision() {
        listOf(
            WindowState.STALE_WINDOW,
            WindowState.NULL_ROOT,
            WindowState.CACHED_PARTIAL_WINDOW,
            WindowState.CACHED_FAILED_WINDOW
        ).forEach(::assertBoundaryContract)
    }

    @Test
    fun ar002R06_differentNonessentialActiveRootDoesNotLockPreviousPackage() {
        val result = runBoundaryScenario(WindowState.DIFFERENT_ACTIVE_ROOT)

        check(result.virtualElapsedMs >= THIRTY_SECOND_GRANT_MS) {
            "the scenario must advance the virtual clock past the 30 second grant"
        }
        check(result.startedActivities.isEmpty()) {
            "a different active nonessential root must not launch a warning for the previous app"
        }
        check(result.decisions.none { it.observedAtMs >= boundaryTimeMs() }) {
            "a different active nonessential root must not evaluate the previous app at its boundary"
        }
    }

    @Test
    fun unsupportedFreshOrPartialDifferentWindowDoesNotMutateOrPublishBoundaryOutcome() {
        listOf(
            WindowState.FRESH_DIFFERENT_WINDOW,
            WindowState.PARTIAL_DIFFERENT_WINDOW
        ).forEach { windowState ->
            val result = runBoundaryScenario(windowState)
            val failures = mutableListOf<String>()

            if (result.decisions.any { it.observedAtMs >= boundaryTimeMs() }) {
                failures += "boundary evaluator outcome was published"
            }
            if (result.startedActivities.any { it.observedAtMs >= boundaryTimeMs() }) {
                failures += "boundary warning was launched"
            }
            if (result.repository.readHistory.any { it.observedAtMs >= boundaryTimeMs() }) {
                failures += "persisted session state was read at the unsupported boundary"
            }
            if (result.repository.mutationHistory.any { it >= boundaryTimeMs() }) {
                failures += "persisted session state was mutated at the unsupported boundary"
            }
            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "${windowState.contractName} deferred contract failures:\n" +
                        failures.joinToString(separator = "\n") { "- $it" }
                )
            }
        }
    }

    @Test
    fun ordinaryBoundaryFailureDeniesStaleEmptyAndNullThenAllowsLaterEventHandling() {
        listOf(BoundaryFailure.SESSION_READ, BoundaryFailure.EVALUATOR_INPUT).forEach { failureMode ->
            listOf(
                WindowState.STALE_WINDOW,
                WindowState.EMPTY_WINDOW,
                WindowState.NULL_ROOT
            ).forEach { windowState ->
                val result = runBoundaryScenario(
                    windowState = windowState,
                    boundaryFailure = failureMode
                )
                val failures = mutableListOf<String>()
                val boundaryDecisions = result.decisions.filter { observation ->
                    observation.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs()
                }

                if (boundaryDecisions.none { observation ->
                        !observation.evaluation.isAllowed &&
                            observation.evaluation.denyingRules.any { it.ruleId == TARGET_RULE_ID }
                    }
                ) {
                    failures += "ordinary failure did not publish the target-rule denial"
                }
                if (boundaryDecisions.any { it.evaluation.isAllowed }) {
                    failures += "ordinary failure published an allow outcome"
                }
                val boundaryPayloads = result.startedActivities
                    .filter { it.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs() }
                    .mapNotNull {
                        it.intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS)
                    }
                if (boundaryPayloads.none { TARGET_RULE_ID in it }) {
                    failures += "ordinary failure did not launch the target-rule warning"
                }
                if (result.boundaryPostedDelays != listOf(20_000L, 250L, 500L, 750L)) {
                    failures += "ordinary failure scheduled recovery beyond the bounded retries: " +
                        result.boundaryPostedDelays
                }
                if (result.decisions.none { it.observedAtMs > boundaryDeadlineMs() }) {
                    failures += "a healthy later event did not reach the evaluator"
                }
                if (failures.isNotEmpty()) {
                    throw AssertionError(
                        "${windowState.contractName} ${failureMode.name} contract failures:\n" +
                            failures.joinToString(separator = "\n") { "- $it" }
                    )
                }
            }
        }
    }

    @Test
    fun scheduledBoundaryCancellationIsContainedWithoutEffectsOrRecovery() {
        val result = runBoundaryScenario(
            windowState = WindowState.STALE_WINDOW,
            boundaryFailure = BoundaryFailure.CANCELLATION
        )
        val boundaryDecisions = result.decisions.filter { observation ->
            observation.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs()
        }

        check(result.escapedFailure == null) {
            "boundary cancellation escaped the Handler callback: ${result.escapedFailure}"
        }
        check(boundaryDecisions.isEmpty()) {
            "boundary cancellation published a denial or allow outcome: $boundaryDecisions"
        }
        check(result.startedActivities.none { observation ->
            observation.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs()
        }) {
            "boundary cancellation launched a denial warning"
        }
        check(CANCELLATION_MESSAGE !in result.appendedCrashLog) {
            "boundary cancellation was logged as a nonfatal error"
        }
        check(result.boundaryPostedDelays == listOf(20_000L, 250L, 500L, 750L)) {
            "boundary cancellation scheduled recovery: ${result.boundaryPostedDelays}"
        }
        check(result.decisions.any { it.observedAtMs > boundaryDeadlineMs() }) {
            "a healthy later event was not handled after boundary cancellation"
        }
    }

    private fun assertBoundaryContract(windowState: WindowState) {
        val result = runBoundaryScenario(windowState)
        val failures = mutableListOf<String>()

        if (result.virtualElapsedMs < THIRTY_SECOND_GRANT_MS) {
            failures += "virtual clock did not advance beyond the 30 second grant"
        }
        if (result.persistedBoundaryDecision.isAllowed) {
            failures += "persisted 30 second session should deny the target rule at the boundary"
        }
        val boundaryDecision = result.decisions.any { observation ->
            observation.observedAtMs >= boundaryTimeMs() &&
                !observation.evaluation.isAllowed &&
                observation.evaluation.denyingRules.any { rule -> rule.ruleId == TARGET_RULE_ID } &&
                observation.persistedRead?.let { read ->
                    read.observedAtMs >= boundaryTimeMs() &&
                        read.sessions.any { session ->
                            session.packageName == TARGET_PACKAGE &&
                                session.startedAtMs == BASE_TIME_MS &&
                                session.endedAtMs == boundaryTimeMs()
                        }
                } == true
        }
        if (!boundaryDecision) {
            failures += "the persisted boundary evaluator decision was not observed"
        }
        val denialPayloads = result.startedActivities
            .filter { it.observedAtMs >= boundaryTimeMs() }
            .mapNotNull { observation ->
                observation.intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS)
        }
        if (denialPayloads.none { TARGET_RULE_ID in it }) {
            failures += "the boundary warning did not contain the persisted target-rule denial"
        }
        if (result.repository.readHistory.none { it.observedAtMs >= boundaryTimeMs() }) {
            failures += "the boundary decision did not read the persisted session state"
        }
        if (result.scheduler.postedDelays.take(4) != listOf(20_000L, 250L, 500L, 750L)) {
            failures += "the bounded boundary retry schedule was not observed"
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "${windowState.contractName} RED contract failures:\n" +
                    failures.joinToString(separator = "\n") { "- $it" }
            )
        }
    }

    private fun runBoundaryScenario(
        windowState: WindowState,
        boundaryFailure: BoundaryFailure = BoundaryFailure.NONE
    ): BoundaryResult {
        val clock = VirtualClock(BASE_TIME_MS)
        val scheduler = VirtualRecheckScheduler(clock)
        val repository = MutableSessionRepository(clock)
        val service = RecordingService { clock.wallClockMs }.also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        service.lastBackPressTimeStamp = 0L

        val snapshot = snapshotWithThirtySecondGuardianGrant(clock.wallClockMs, repository)
        val decisions = mutableListOf<EvaluationObservation>()
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { clock.wallClockMs }
            elapsedRealtimeMsProvider = { clock.elapsedRealtimeMs }
            recheckPostDelayed = scheduler::postDelayed
            recheckRemoveCallback = scheduler::remove
            evaluationResultObserver = { decision ->
                decisions += EvaluationObservation(
                    observedAtMs = clock.wallClockMs,
                    evaluation = decision,
                    persistedRead = repository.lastRead
                )
            }
            var windowReadCount = 0
            applicationWindowSnapshotProvider = {
                val readIndex = windowReadCount++
                when {
                    windowState.seedResolvedWindow && readIndex == 0 -> RESOLVED_OTHER_WINDOW
                    windowState.failAfterSeed ->
                        error("injected application-window provider failure")
                    else -> windowState.windowSnapshot
                }
            }
            activeWindowSnapshotProvider = { windowState.activeWindowSnapshot }
        }

        setField(blocker, "service", service)
        setField(blocker, "crashLogger", CrashLogger(service))
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE, OTHER_PACKAGE))
        setField(blocker, "overrideState", snapshot.overrideState)
        val coordinator = getField(blocker, "snapshot") as neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
        coordinator.accept(snapshot.snapshot)

        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)

            // The row is the persisted usage outcome at the allowance boundary. No real clock or
            // looper is advanced; the scheduler only runs queued callbacks after virtual time is
            // moved forward.
            clock.advanceBy(THIRTY_SECOND_GRANT_MS)
            repository.persistedSessions = listOf(
                ForegroundSession(
                    useDayId = repository.useDayId,
                    packageName = TARGET_PACKAGE,
                    startedAtMs = BASE_TIME_MS,
                    endedAtMs = clock.wallClockMs
                )
            )
            repository.boundaryFailure = boundaryFailure
            val crashLog = File(service.filesDir, "crash_log.txt")
            val crashLogLengthBeforeBoundary = crashLog.length()
            var escapedFailure: Throwable? = null
            try {
                scheduler.runDue()
                scheduler.advanceBy(250L)
                scheduler.advanceBy(500L)
                scheduler.advanceBy(750L)
            } catch (error: Throwable) {
                if (boundaryFailure != BoundaryFailure.CANCELLATION) throw error
                escapedFailure = error
            }
            val boundaryPostedDelays = scheduler.postedDelays.toList()

            if (boundaryFailure != BoundaryFailure.NONE) {
                repository.boundaryFailure = BoundaryFailure.NONE
                clock.advanceBy(1_000L)
                sendWindowEvent(blocker, TARGET_PACKAGE)
            }

            val persistedBoundaryDecision = AppRuleEvaluator.evaluate(
                snapshot = snapshot.snapshot,
                packageName = TARGET_PACKAGE,
                useDayId = repository.useDayId,
                sessions = repository.persistedSessions,
                nowMs = clock.wallClockMs,
                zone = repository.zone,
                useDayCalculator = repository.calculator,
                overrides = snapshot.overrideState
            )
            return BoundaryResult(
                decisions = decisions.toList(),
                startedActivities = service.startedActivities.toList(),
                persistedBoundaryDecision = persistedBoundaryDecision,
                virtualElapsedMs = clock.elapsedRealtimeMs,
                scheduler = scheduler.snapshot(),
                boundaryPostedDelays = boundaryPostedDelays,
                escapedFailure = escapedFailure,
                appendedCrashLog = appendedText(crashLog, crashLogLengthBeforeBoundary),
                repository = repository
            )
        } finally {
            blocker.onDestroy()
        }
    }

    private fun snapshotWithThirtySecondGuardianGrant(
        nowMs: Long,
        repository: MutableSessionRepository
    ): ScenarioSnapshot {
        val group = AppRuleAppGroup(
            id = TARGET_GROUP_ID,
            name = "Target",
            selectedPackages = listOf(TARGET_PACKAGE)
        )
        val rule = AppRule(
            id = TARGET_RULE_ID,
            name = "Target rule",
            weekdays = (0..6).toSet(),
            startMinute = 0,
            endMinute = 0,
            appGroupId = TARGET_GROUP_ID,
            allowedMinutes = 0L
        )
        val useDayId = repository.useDayId
        val overrides = AppRuleGuardianOverrides.grant(
            state = AppRuleOverrideState(useDayId),
            ruleId = TARGET_RULE_ID,
            useDayId = useDayId,
            grantedMillis = THIRTY_SECOND_GRANT_MS,
            grantedAtMs = nowMs
        )
        return ScenarioSnapshot(
            snapshot = AppRuleSnapshot(listOf(group), listOf(rule)),
            overrideState = overrides
        )
    }

    private fun sendWindowEvent(blocker: AppRuleBlocker, packageName: String) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        try {
            event.packageName = packageName
            blocker.doAppRuleCheck(event)
        } finally {
            event.recycle()
        }
    }

    private enum class WindowState(
        val contractName: String,
        val windowSnapshot: AppRuleBlocker.ApplicationWindowSnapshot,
        val activeWindowSnapshot: AppRuleBlocker.ActiveWindowSnapshot,
        val seedResolvedWindow: Boolean = false,
        val failAfterSeed: Boolean = false
    ) {
        STALE_WINDOW(
            contractName = "successful window then empty read",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedResolvedWindow = true
        ),
        EMPTY_WINDOW(
            contractName = "no event + empty window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        ),
        NULL_ROOT(
            contractName = "successful window then null root",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 1
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedResolvedWindow = true
        ),
        FRESH_DIFFERENT_WINDOW(
            contractName = "expired event + fresh complete different window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false,
                applicationWindowCount = 1
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        ),
        PARTIAL_DIFFERENT_WINDOW(
            contractName = "expired event + partial different window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 2
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null)
        ),
        CACHED_PARTIAL_WINDOW(
            contractName = "successful window then partial unresolved read",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 2
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedResolvedWindow = true
        ),
        CACHED_FAILED_WINDOW(
            contractName = "successful window then failed read",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true,
                providerFailed = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedResolvedWindow = true,
            failAfterSeed = true
        ),
        DIFFERENT_ACTIVE_ROOT(
            contractName = "different active nonessential root",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = OTHER_PACKAGE),
            seedResolvedWindow = true
        )
    }

    private data class ScenarioSnapshot(
        val snapshot: AppRuleSnapshot,
        val overrideState: AppRuleOverrideState
    )

    private data class BoundaryResult(
        val decisions: List<EvaluationObservation>,
        val startedActivities: List<StartedActivityObservation>,
        val persistedBoundaryDecision: AppRulesEvaluation,
        val virtualElapsedMs: Long,
        val scheduler: SchedulerState,
        val boundaryPostedDelays: List<Long>,
        val escapedFailure: Throwable?,
        val appendedCrashLog: String,
        val repository: MutableSessionRepository
    )

    private data class EvaluationObservation(
        val observedAtMs: Long,
        val evaluation: AppRulesEvaluation,
        val persistedRead: PersistedSessionRead?
    )

    private data class StartedActivityObservation(
        val observedAtMs: Long,
        val intent: Intent
    )

    private data class PersistedSessionRead(
        val observedAtMs: Long,
        val sessions: List<ForegroundSession>
    )

    private data class SchedulerState(
        val postedDelays: List<Long>
    )

    private class VirtualClock(startMs: Long) {
        var wallClockMs: Long = startMs
            private set
        var elapsedRealtimeMs: Long = 0L
            private set

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            wallClockMs += deltaMs
            elapsedRealtimeMs += deltaMs
        }
    }

    private class VirtualRecheckScheduler(private val clock: VirtualClock) {
        private data class Task(
            val dueAtElapsedMs: Long,
            val sequence: Long,
            val runnable: Runnable
        )

        private val tasks = mutableListOf<Task>()
        private var nextSequence = 0L
        val postedDelays = mutableListOf<Long>()

        fun postDelayed(runnable: Runnable, delayMs: Long): Boolean {
            postedDelays += delayMs
            tasks += Task(
                dueAtElapsedMs = clock.elapsedRealtimeMs + delayMs,
                sequence = nextSequence++,
                runnable = runnable
            )
            return true
        }

        fun remove(runnable: Runnable) {
            tasks.removeAll { it.runnable === runnable }
        }

        fun advanceBy(deltaMs: Long) {
            clock.advanceBy(deltaMs)
            runDue()
        }

        fun runDue() {
            while (true) {
                val next = tasks
                    .filter { it.dueAtElapsedMs <= clock.elapsedRealtimeMs }
                    .minWithOrNull(compareBy<Task> { it.dueAtElapsedMs }.thenBy { it.sequence })
                    ?: return
                tasks.remove(next)
                next.runnable.run()
            }
        }

        fun snapshot(): SchedulerState = SchedulerState(
            postedDelays = postedDelays.toList()
        )
    }

    private class MutableSessionRepository(private val clock: VirtualClock) : CurrentUseDaySessionRepository {
        val zone = java.time.ZoneId.systemDefault()
        val calculator = ConfigurableUseDayCalculator(zone)
        val useDayId: String = calculator.idAt(BASE_TIME_MS)
        val readHistory = mutableListOf<PersistedSessionRead>()
        val mutationHistory = mutableListOf<Long>()
        var boundaryFailure: BoundaryFailure = BoundaryFailure.NONE
        var lastRead: PersistedSessionRead? = null
        var persistedSessions: List<ForegroundSession> = listOf(
            ForegroundSession(
                useDayId = useDayId,
                packageName = TARGET_PACKAGE,
                startedAtMs = BASE_TIME_MS,
                endedAtMs = BASE_TIME_MS
            )
        )

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            mutationHistory += clock.wallClockMs
            return 1L
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (clock.wallClockMs >= BASE_TIME_MS + THIRTY_SECOND_GRANT_MS) {
                when (boundaryFailure) {
                    BoundaryFailure.SESSION_READ ->
                        throw IllegalStateException("injected ordinary R5 boundary read failure")
                    BoundaryFailure.EVALUATOR_INPUT -> return ThrowingSessionList()
                    BoundaryFailure.CANCELLATION -> throw CancellationException(CANCELLATION_MESSAGE)
                    BoundaryFailure.NONE -> Unit
                }
            }
            val read = PersistedSessionRead(clock.wallClockMs, persistedSessions.toList())
            readHistory += read
            lastRead = read
            return read.sessions.filter { it.useDayId == useDayId }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs
        }
    }

    private class RecordingService(private val nowMsProvider: () -> Long) : BaseBlockingService() {
        val startedActivities = mutableListOf<StartedActivityObservation>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += StartedActivityObservation(nowMsProvider(), intent)
        }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun boundaryTimeMs(): Long = BASE_TIME_MS + THIRTY_SECOND_GRANT_MS

    private fun boundaryDeadlineMs(): Long = boundaryTimeMs() + 1_500L

    private fun appendedText(file: File, startOffset: Long): String {
        if (!file.exists() || file.length() <= startOffset) return ""
        return RandomAccessFile(file, "r").use { input ->
            input.seek(startOffset)
            ByteArray((input.length() - startOffset).toInt()).also(input::readFully)
                .toString(Charsets.UTF_8)
        }
    }

    private enum class BoundaryFailure {
        NONE,
        SESSION_READ,
        EVALUATOR_INPUT,
        CANCELLATION
    }

    private class ThrowingSessionList : AbstractList<ForegroundSession>() {
        override val size: Int get() = 1

        override fun get(index: Int): ForegroundSession =
            error("injected ordinary R5 evaluator input failure")
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val OTHER_PACKAGE = "com.example.other"
        const val TARGET_GROUP_ID = "target-group"
        const val TARGET_RULE_ID = "target-rule"
        const val THIRTY_SECOND_GRANT_MS = 30_000L
        const val CANCELLATION_MESSAGE = "injected R5 boundary cancellation"
        val RESOLVED_OTHER_WINDOW = AppRuleBlocker.ApplicationWindowSnapshot(
            packages = setOf(OTHER_PACKAGE),
            hasApplicationWindow = true,
            hasUnknownApplicationWindow = false,
            applicationWindowCount = 1
        )
        val BASE_TIME_MS = Instant.parse("2026-08-31T10:00:00Z").toEpochMilli()
    }
}
