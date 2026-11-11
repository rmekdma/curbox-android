package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleOverrideState
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleEvaluator
import neth.iecal.curbox.domain.apprules.AppRuleWakeScheduler
import neth.iecal.curbox.domain.apprules.AppRuleGuardianOverrides
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.testing.AccessibilityFrameworkTestObjects
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun r5RetryPostFailuresStillReachFailClosedWithinDeadline() {
        val result = runBoundaryScenario(WindowState.STALE_WINDOW, failRetryPosts = true)
        check(result.rejectedRetryPosts == 3) { "the retry post failures were not all exercised" }
        check(result.decisions.any {
            it.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs() && !it.evaluation.isAllowed
        }) { "retry post failures delayed the fail-closed decision beyond the R5 deadline" }
        check(result.startedActivities.count {
            it.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs() &&
                TARGET_RULE_ID in it.intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS).orEmpty()
        } == 1) { "retry post failures did not publish exactly one target warning by the R5 deadline" }
    }

    @Test
    fun synchronousWakePostDoesNotDuplicateR5Recovery() {
        val result = runBoundaryScenario(WindowState.STALE_WINDOW, synchronousInitialPost = true)
        check(result.synchronousPosts == 1) { "the wake callback was not delivered synchronously" }
        check(result.boundaryPostedDelays == listOf(250L, 500L, 750L)) {
            "a completed synchronous callback was posted again: ${result.boundaryPostedDelays}"
        }
        check(result.decisions.count {
            it.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs() && !it.evaluation.isAllowed
        } == 1) { "synchronous wake did not produce exactly one boundary denial" }
        check(result.startedActivities.count {
            it.observedAtMs in boundaryTimeMs()..boundaryDeadlineMs() &&
                TARGET_RULE_ID in it.intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS).orEmpty()
        } == 1) { "synchronous wake did not produce exactly one boundary warning" }
    }

    @Test
    fun syntheticR5EvaluationDoesNotRenewForegroundEvidenceTimestamp() {
        val result = runBoundaryScenario(WindowState.STALE_WINDOW)

        check(result.boundaryForegroundEvidenceAtElapsedMs != null) {
            "the synthetic R5 boundary evaluation was not observed"
        }
        check(
            result.boundaryForegroundEvidenceAtElapsedMs ==
                result.initialForegroundEvidenceAtElapsedMs
        ) {
            "synthetic R5 evaluation renewed foreground evidence: " +
                "initial=${result.initialForegroundEvidenceAtElapsedMs}, " +
                "boundary=${result.boundaryForegroundEvidenceAtElapsedMs}"
        }
    }

    @Test
    fun cachedTargetThenCurrentPartialOtherDoesNotReevaluateStaleTarget() {
        val result = runBoundaryScenario(WindowState.CACHED_TARGET_THEN_PARTIAL_OTHER)

        check(result.startedActivities.none { it.observedAtMs >= boundaryTimeMs() }) {
            "a stale cached target must not open a guardian when current partial windows identify another package"
        }
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

            if (OTHER_PACKAGE !in result.boundaryEvaluatedPackages) {
                failures += "the identified other app was not evaluated"
            }
            if (TARGET_PACKAGE in result.boundaryEvaluatedPackages) {
                failures += "the previous app was evaluated after the other window appeared"
            }
            if (result.startedActivities.any { it.observedAtMs >= boundaryTimeMs() }) {
                failures += "a warning was launched for the previous app"
            }
            if (result.repository.mutationHistory.none {
                    it.first >= boundaryTimeMs() && it.second == "start:$OTHER_PACKAGE"
                }
            ) {
                failures += "the identified other app did not start a foreground session"
            }
            if (result.repository.mutationHistory.any {
                    it.first > boundaryTimeMs() &&
                        (it.second.startsWith("finish:1:") || it.second.startsWith("update:1:"))
                }
            ) {
                failures += "the previous app session was extended after the window switch"
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
                if (result.boundaryPostedDelays != listOf(0L, 250L, 500L, 750L)) {
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
        check(result.boundaryPostedDelays == listOf(0L, 250L, 500L, 750L)) {
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
            observation.observedAtMs == boundaryDeadlineMs() &&
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
        if (result.decisions.any { it.observedAtMs in boundaryTimeMs() until boundaryDeadlineMs() } ||
            result.startedActivities.any { it.observedAtMs in boundaryTimeMs() until boundaryDeadlineMs() }
        ) {
            failures += "the uncertain boundary produced an outcome before its final retry"
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

        val retryDelays = result.scheduler.postedDelays.filter { it > 0L }
        if (retryDelays != listOf(250L, 500L, 750L)) {
            failures += "persistently uncertain visibility must use the bounded retry delays: $retryDelays"
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
        boundaryFailure: BoundaryFailure = BoundaryFailure.NONE,
        failRetryPosts: Boolean = false,
        synchronousInitialPost: Boolean = false
    ): BoundaryResult {
        val clock = VirtualClock(BASE_TIME_MS)
        val initialRecheckPosted = CountDownLatch(1)
        val scheduler = VirtualRecheckScheduler(clock)
        scheduler.firstPostObserver = { initialRecheckPosted.countDown() }
        val repository = MutableSessionRepository(clock)
        val boundaryWarningObserved = CountDownLatch(1)
        val service = RecordingService({ clock.wallClockMs }) {
            if (clock.wallClockMs in boundaryTimeMs()..boundaryDeadlineMs()) {
                boundaryWarningObserved.countDown()
            }
        }.also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        service.lastBackPressTimeStamp = 0L

        val snapshot = snapshotWithThirtySecondGuardianGrant(clock.wallClockMs, repository)
        val decisions = mutableListOf<EvaluationObservation>()
        val initialEvaluationObserved = CountDownLatch(1)
        val boundaryEvaluationObserved = CountDownLatch(1)
        val otherEvaluationObserved = CountDownLatch(1)
        val boundaryEvaluatedPackages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val laterEvaluationObserved = CountDownLatch(1)
        val boundaryHandlerPostObserved = CountDownLatch(1)
        val handlerPostCount = AtomicInteger(0)
        var windowReadCount = 0
        var rejectedRetryPosts = 0
        var synchronousPosts = 0
        val blocker = AppRuleBlocker(wakeScheduler = scheduler).apply {
            wallClockMsProvider = { clock.wallClockMs }
            elapsedRealtimeMsProvider = { clock.elapsedRealtimeMs }
            visibleApplicationCheckPostDelayed = { runnable, delayMs ->
                if (failRetryPosts && delayMs > 0L && rejectedRetryPosts < 3) {
                    rejectedRetryPosts++
                    false
                } else if (synchronousInitialPost && delayMs == 0L && synchronousPosts == 0) {
                    synchronousPosts++
                    runnable.run()
                    true
                } else {
                    scheduler.postDelayed(runnable, delayMs)
                }
            }
            decisionOutcomeSinkObserver = { outcome ->
                if (outcome is DecisionOutcome.EvaluationReady && clock.wallClockMs >= boundaryTimeMs()) {
                    boundaryEvaluatedPackages += outcome.packageName
                    if (outcome.packageName == OTHER_PACKAGE) otherEvaluationObserved.countDown()
                }
            }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            evaluationResultObserver = { decision ->
                val observedAtMs = clock.wallClockMs
                decisions += EvaluationObservation(
                    observedAtMs = observedAtMs,
                    evaluation = decision,
                    persistedRead = repository.lastRead
                )
                if (observedAtMs < boundaryTimeMs()) {
                    initialEvaluationObserved.countDown()
                } else if (observedAtMs > boundaryDeadlineMs()) {
                    laterEvaluationObserved.countDown()
                } else {
                    boundaryEvaluationObserved.countDown()
                }
            }
            handlerBeforeFrameworkPostObserver = {
                if (handlerPostCount.incrementAndGet() >= 2) {
                    boundaryHandlerPostObserved.countDown()
                }
            }
            applicationWindowSnapshotProvider = {
                val readIndex = windowReadCount++
                when {
                    windowState.seedTargetWindow && readIndex == 0 -> RESOLVED_TARGET_WINDOW
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
        setField(
            blocker,
            "usageResetRepository",
            RoomUsageResetRepository(AppDatabase.getInstance(service))
        )
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE, OTHER_PACKAGE))
        setField(blocker, "overrideState", snapshot.overrideState)
        val coordinator = getField(blocker, "snapshot") as neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
        coordinator.accept(snapshot.snapshot)

        try {
            sendWindowEvent(blocker, TARGET_PACKAGE)
            check(
                initialEvaluationObserved.await(WORKER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) {
                "initial foreground decision was not processed before advancing the virtual clock"
            }
            check(
                initialRecheckPosted.await(WORKER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ) {
                "initial foreground boundary was not scheduled before advancing the virtual clock"
            }
            if (windowState == WindowState.FRESH_DIFFERENT_WINDOW ||
                windowState == WindowState.PARTIAL_DIFFERENT_WINDOW
            ) {
                check(repository.mutationHistory.any { it.second == "start:$TARGET_PACKAGE" }) {
                    "the initial target foreground session was not established"
                }
            }
            val initialForegroundEvidenceAtElapsedMs =
                getField(blocker, "currentForegroundEvidenceAtElapsedMs") as Long

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
                if (windowState == WindowState.FRESH_DIFFERENT_WINDOW ||
                    windowState == WindowState.PARTIAL_DIFFERENT_WINDOW
                ) {
                    check(otherEvaluationObserved.await(WORKER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        "the identified other app was not evaluated at the boundary"
                    }
                }
                scheduler.advanceBy(250L)
                scheduler.advanceBy(500L)
                scheduler.advanceBy(750L)
            } catch (error: Throwable) {
                if (boundaryFailure != BoundaryFailure.CANCELLATION) throw error
                escapedFailure = error
            }
            val boundaryPostedDelays = scheduler.postedDelays.toList()

            if (boundaryFailure != BoundaryFailure.NONE) {
                check(
                    repository.boundaryFailureObserved.await(
                        WORKER_WAIT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    "the injected ${boundaryFailure.name} did not reach the boundary evaluator"
                }
                if (windowState.expectsBoundaryDecision &&
                    boundaryFailure != BoundaryFailure.CANCELLATION
                ) {
                    check(
                        boundaryEvaluationObserved.await(
                            WORKER_WAIT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        "the ${boundaryFailure.name} boundary denial was not evaluated before recovery"
                    }
                    check(
                        boundaryHandlerPostObserved.await(
                            WORKER_WAIT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        "the ${boundaryFailure.name} boundary outcome was not posted before recovery"
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    check(
                        boundaryWarningObserved.await(
                            WORKER_WAIT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        "the ${boundaryFailure.name} boundary denial was not published before recovery"
                    }
                }
                repository.boundaryFailure = BoundaryFailure.NONE
                clock.advanceBy(LATER_EVENT_DELAY_MS)
                sendWindowEvent(blocker, TARGET_PACKAGE)
                check(
                    laterEvaluationObserved.await(WORKER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ) {
                    "a healthy later event was not evaluated after the boundary failure"
                }
            }
            if (windowState.expectsBoundaryDecision &&
                boundaryFailure != BoundaryFailure.CANCELLATION
            ) {
                check(
                    boundaryEvaluationObserved.await(WORKER_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ) {
                    "${windowState.contractName} boundary worker decision was not processed " +
                        "after $rejectedRetryPosts rejected retry posts; " +
                        "before the scenario ended; posted=${scheduler.postedDelays}, " +
                        "windowReads=$windowReadCount, decisions=$decisions"
                }
                check(
                    boundaryHandlerPostObserved.await(
                        WORKER_WAIT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    "boundary worker outcome was not posted before the scenario ended"
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                if (boundaryFailure == BoundaryFailure.NONE) {
                    check(
                        boundaryWarningObserved.await(
                            WORKER_WAIT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        "the boundary warning was not published before the scenario ended"
                    }
                }
            } else if (windowState.expectsBoundaryDecision) {
                check(
                    boundaryHandlerPostObserved.await(
                        WORKER_WAIT_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    "the healthy post-cancellation outcome was not posted before the scenario ended"
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
            val boundaryForegroundEvidenceAtElapsedMs = if (
                windowState.expectsBoundaryDecision &&
                boundaryFailure != BoundaryFailure.CANCELLATION
            ) {
                getField(blocker, "currentForegroundEvidenceAtElapsedMs") as Long
            } else {
                null
            }
            return BoundaryResult(
                decisions = decisions.toList(),
                rejectedRetryPosts = rejectedRetryPosts,
                synchronousPosts = synchronousPosts,
                boundaryEvaluatedPackages = boundaryEvaluatedPackages.toList(),
                startedActivities = service.startedActivities.toList(),
                persistedBoundaryDecision = persistedBoundaryDecision,
                initialForegroundEvidenceAtElapsedMs = initialForegroundEvidenceAtElapsedMs,
                boundaryForegroundEvidenceAtElapsedMs = boundaryForegroundEvidenceAtElapsedMs,
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
        val event = AccessibilityFrameworkTestObjects.createEvent(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )
        try {
            event.packageName = packageName
            blocker.doAppRuleCheck(event)
        } finally {
            AccessibilityFrameworkTestObjects.releaseEvent(event)
        }
    }

    private enum class WindowState(
        val contractName: String,
        val windowSnapshot: AppRuleBlocker.ApplicationWindowSnapshot,
        val activeWindowSnapshot: AppRuleBlocker.ActiveWindowSnapshot,
        val expectsBoundaryDecision: Boolean = false,
        val seedResolvedWindow: Boolean = false,
        val failAfterSeed: Boolean = false,
        val seedTargetWindow: Boolean = false
    ) {
        STALE_WINDOW(
            contractName = "successful window then empty read",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            expectsBoundaryDecision = true,
            seedResolvedWindow = true
        ),
        EMPTY_WINDOW(
            contractName = "no event + empty window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = false,
                hasUnknownApplicationWindow = true
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            expectsBoundaryDecision = true
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
            expectsBoundaryDecision = true,
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
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedTargetWindow = true
        ),
        PARTIAL_DIFFERENT_WINDOW(
            contractName = "expired event + partial different window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 2
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedTargetWindow = true
        ),
        CACHED_PARTIAL_WINDOW(
            contractName = "successful window then partial unresolved read",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = emptySet(),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 1
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            expectsBoundaryDecision = true,
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
            expectsBoundaryDecision = true,
            seedResolvedWindow = true,
            failAfterSeed = true
        ),
        CACHED_TARGET_THEN_PARTIAL_OTHER(
            contractName = "cached target then current partial other window",
            windowSnapshot = AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(OTHER_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = true,
                applicationWindowCount = 2
            ),
            activeWindowSnapshot = AppRuleBlocker.ActiveWindowSnapshot(packageName = null),
            seedTargetWindow = true
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
        val rejectedRetryPosts: Int,
        val synchronousPosts: Int,
        val boundaryEvaluatedPackages: List<String>,
        val startedActivities: List<StartedActivityObservation>,
        val persistedBoundaryDecision: AppRulesEvaluation,
        val initialForegroundEvidenceAtElapsedMs: Long,
        val boundaryForegroundEvidenceAtElapsedMs: Long?,
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

    private class VirtualRecheckScheduler(private val clock: VirtualClock) : AppRuleWakeScheduler {
        override var onWake: ((String, Long) -> Unit)? = null
        private val wakes = mutableMapOf<String, Pair<Long, Long>>()

        override fun schedule(key: String, dueAtWallClockMs: Long, token: Long) {
            wakes[key] = dueAtWallClockMs to token
            firstPostObserver?.invoke()
        }

        override fun cancel(key: String) { wakes.remove(key) }

        override fun cancelAll() { wakes.clear() }
        private data class Task(
            val dueAtElapsedMs: Long,
            val sequence: Long,
            val runnable: Runnable
        )

        private val tasks = mutableListOf<Task>()
        private var nextSequence = 0L
        val postedDelays = mutableListOf<Long>()
        var firstPostObserver: (() -> Unit)? = null

        fun postDelayed(runnable: Runnable, delayMs: Long): Boolean {
            postedDelays += delayMs
            tasks += Task(
                dueAtElapsedMs = clock.elapsedRealtimeMs + delayMs,
                sequence = nextSequence++,
                runnable = runnable
            )
            if (postedDelays.size == 1) firstPostObserver?.invoke()
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
            val dueWakes = wakes.filterValues { it.first <= clock.wallClockMs }.toList()
            dueWakes.forEach { (key, wake) ->
                wakes.remove(key)
                onWake?.invoke(key, wake.second)
            }
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
        val mutationHistory = mutableListOf<Pair<Long, String>>()
        val boundaryFailureObserved = CountDownLatch(1)
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
            mutationHistory += clock.wallClockMs to "start:$packageName"
            return if (packageName == TARGET_PACKAGE) 1L else 2L
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs to "finish:$id:$endedAtMs"
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs to "update:$id:$endedAtMs"
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            if (clock.wallClockMs >= BASE_TIME_MS + THIRTY_SECOND_GRANT_MS) {
                when (boundaryFailure) {
                    BoundaryFailure.SESSION_READ -> {
                        boundaryFailureObserved.countDown()
                        throw IllegalStateException("injected ordinary R5 boundary read failure")
                    }
                    BoundaryFailure.EVALUATOR_INPUT -> {
                        boundaryFailureObserved.countDown()
                        return ThrowingSessionList()
                    }
                    BoundaryFailure.CANCELLATION -> {
                        boundaryFailureObserved.countDown()
                        throw CancellationException(CANCELLATION_MESSAGE)
                    }
                    BoundaryFailure.NONE -> Unit
                }
            }
            val read = PersistedSessionRead(clock.wallClockMs, persistedSessions.toList())
            readHistory += read
            lastRead = read
            return read.sessions.filter { it.useDayId == useDayId }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
            mutationHistory += clock.wallClockMs to "finishOpen:$useDayId:$endedAtMs"
        }
    }

    private class RecordingService(
        private val nowMsProvider: () -> Long,
        private val onStarted: () -> Unit
    ) : BaseBlockingService() {
        val startedActivities = mutableListOf<StartedActivityObservation>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += StartedActivityObservation(nowMsProvider(), intent)
            onStarted()
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
        const val LATER_EVENT_DELAY_MS = 2_500L
        const val WORKER_WAIT_TIMEOUT_MS = 5_000L
        const val CANCELLATION_MESSAGE = "injected R5 boundary cancellation"
        val RESOLVED_OTHER_WINDOW = AppRuleBlocker.ApplicationWindowSnapshot(
            packages = setOf(OTHER_PACKAGE),
            hasApplicationWindow = true,
            hasUnknownApplicationWindow = false,
            applicationWindowCount = 1
        )
        val RESOLVED_TARGET_WINDOW = AppRuleBlocker.ApplicationWindowSnapshot(
            packages = setOf(TARGET_PACKAGE),
            hasApplicationWindow = true,
            hasUnknownApplicationWindow = false,
            applicationWindowCount = 1
        )
        val BASE_TIME_MS = Instant.parse("2026-08-31T10:00:00Z").toEpochMilli()
    }
}
