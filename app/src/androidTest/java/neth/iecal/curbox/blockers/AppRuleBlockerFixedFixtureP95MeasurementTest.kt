package neth.iecal.curbox.blockers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.LifecycleGeneration
import neth.iecal.curbox.domain.apprules.ObservationKind
import neth.iecal.curbox.domain.apprules.RuntimeRevision
import neth.iecal.curbox.domain.apprules.SourceOrderIdentity
import neth.iecal.curbox.services.BaseBlockingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppRuleBlockerFixedFixtureP95MeasurementTest {

    private companion object {
        const val FIXTURE_ALLOW = "T27_ALLOW"
        const val FIXTURE_DENY = "T27_DENY"
        const val PACKAGE_ALLOW = "com.curbox.t27.synthetic.allow"
        const val PACKAGE_DENY = "com.curbox.t27.synthetic.deny"

        const val OBSERVATION_DEADLINE_NS = 5_000_000_000L
        const val RECOVERY_DEADLINE_NS = 5_000_000_000L
        const val EXCLUSION_CAP = 5
        const val WARMUP_COUNT = 20
        const val MEASURED_PER_KIND = 100
        const val TOTAL_MEASURED = 200
        const val LEDGER_CAPACITY = WARMUP_COUNT + TOTAL_MEASURED + EXCLUSION_CAP

        const val EXPECTED_APPLICATION_ID = "neth.iecal.curbox.debug"
        const val EXPECTED_BOUNDARY = "T27-B fixed synthetic callback-to-decision-publication-entry p95"
        const val PROTOCOL_REVISION = "T27-P6"
    }

    private class IngressViolationException(message: String) : RuntimeException(message)

    private class FixedFixtureMeasurementService : BaseBlockingService() {
        val capturedLaunches = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            capturedLaunches.add(intent)
        }

        override fun getWindows(): MutableList<AccessibilityWindowInfo> = mutableListOf()
    }

    private class InMemoryUseDaySessionRepository : CurrentUseDaySessionRepository {
        private var nextId = AtomicLong(0L)
        val sessions = ConcurrentHashMap<Long, ForegroundSession>()

        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long): Long {
            val id = nextId.incrementAndGet()
            sessions[id] = ForegroundSession(
                id = id,
                useDayId = useDayId,
                packageName = packageName,
                startedAtMs = startedAtMs,
                endedAtMs = null
            )
            return id
        }

        override suspend fun finishSession(id: Long, endedAtMs: Long) {
            val existing = sessions[id]
            if (existing != null) {
                sessions[id] = existing.copy(endedAtMs = endedAtMs)
            }
        }

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) {
            val existing = sessions[id]
            if (existing != null) {
                sessions[id] = existing.copy(endedAtMs = endedAtMs)
            }
        }

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> {
            return sessions.values.filter { it.useDayId == useDayId }
        }

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) {
            for ((id, session) in sessions) {
                if (session.useDayId == useDayId && session.endedAtMs == null) {
                    sessions[id] = session.copy(endedAtMs = endedAtMs)
                }
            }
        }

        fun hasOpenSessions(): Boolean {
            return sessions.values.any { it.endedAtMs == null }
        }
    }

    private class AttemptSlot(
        val attemptOrdinal: Int
    ) {
        var sampleOrdinal: Int? = null
        var phase: String = ""
        var fixtureLabel: String = ""
        var sourceOrderIdentity: String? = null
        var observationKind: String? = null
        var lifecycleGeneration: Long? = null
        var acceptedRuntimeRevision: Long? = null
        var callbackExitKind: String? = null
        var packageDecisionCount: Int? = null
        var decision: String? = null
        var denyingRuleCount: Int? = null
        var commitStatus: String? = null
        var publicationStatus: String? = null

        var callbackStartNs: Long = 0L
        var callbackReturnNs: Long? = null
        var selectedEndNs: Long? = null
        var quiescenceStartNs: Long? = null
        var quiescenceEndNs: Long? = null

        var callbackReturnPresent: Boolean = false
        var selectedEndPresent: Boolean = false
        var terminalState: String = "STARTED"
        var exclusionReason: String? = null

        val endLatch = CountDownLatch(1)

        fun toRow(runId: String): AttemptLedgerRow {
            val cbDuration = if (callbackReturnNs != null && callbackStartNs > 0) callbackReturnNs!! - callbackStartNs else null
            val selDuration = if (selectedEndNs != null && callbackStartNs > 0) selectedEndNs!! - callbackStartNs else null
            val diffNs = if (selectedEndNs != null && callbackReturnNs != null) selectedEndNs!! - callbackReturnNs!! else null
            val quiescenceDuration = if (quiescenceEndNs != null && quiescenceStartNs != null) quiescenceEndNs!! - quiescenceStartNs!! else null

            return AttemptLedgerRow(
                runId = runId,
                attemptOrdinal = attemptOrdinal,
                sampleOrdinal = sampleOrdinal,
                phase = phase,
                fixtureLabel = fixtureLabel,
                sourceOrderIdentity = sourceOrderIdentity,
                observationKind = observationKind,
                lifecycleGeneration = lifecycleGeneration,
                acceptedRuntimeRevision = acceptedRuntimeRevision,
                callbackExitKind = callbackExitKind,
                packageDecisionCount = packageDecisionCount,
                decision = decision,
                denyingRuleCount = denyingRuleCount,
                commitStatus = commitStatus,
                publicationStatus = publicationStatus,
                callbackStartNs = callbackStartNs,
                callbackReturnNs = callbackReturnNs,
                selectedEndNs = selectedEndNs,
                quiescenceStartNs = quiescenceStartNs,
                quiescenceEndNs = quiescenceEndNs,
                callbackDurationNs = cbDuration,
                selectedDurationNs = selDuration,
                selectedEndMinusCallbackReturnNs = diffNs,
                quiescenceDurationNs = quiescenceDuration,
                callbackReturnPresent = callbackReturnPresent,
                selectedEndPresent = selectedEndPresent,
                terminalState = terminalState,
                exclusionReason = exclusionReason
            )
        }
    }

    @Test
    fun measureFixedSyntheticFixtureP95() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val currentProcess = Application.getProcessName()

        assertEquals(EXPECTED_APPLICATION_ID, currentProcess)
        assertFalse(currentProcess.endsWith(":app_blocker_service"))
        assertFalse(currentProcess.endsWith(":crash_handler"))

        val args = InstrumentationRegistry.getArguments()
        val rawDeviceSerial = args.getString("t27RawDeviceSerial") ?: "T811MA256GB23418064398"
        val pseudonymousDeviceId = args.getString("t27PseudonymousDeviceId") ?: "T27_DEVICE_01"

        val runId = "T27-" + Instant.now().epochSecond + "-" + (1000..9999).random()
        val startedAtUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val service = FixedFixtureMeasurementService().also { it.attach(targetContext) }
        val sessionRepository = InMemoryUseDaySessionRepository()
        val blocker = AppRuleBlocker()

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", sessionRepository)
        setField(blocker, "usageResetRepository", RoomUsageResetRepository(AppDatabase.getInstance(service)))
        setField(blocker, "enforcement", AppRuleEnforcement(sessionRepository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(PACKAGE_ALLOW, PACKAGE_DENY))

        var activeFixturePackage = PACKAGE_ALLOW
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = activeFixturePackage)
        }
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(activeFixturePackage),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false
            )
        }

        val syntheticGroup = AppRuleAppGroup(id = "T27_SYNTHETIC_DENY_GROUP", name = "T27 Deny Group", selectedPackages = listOf(PACKAGE_DENY))
        val syntheticDenyRule = AppRule(
            id = "T27_SYNTHETIC_DENY_RULE",
            name = "T27 Deny Rule",
            weekdays = (0..6).toSet(),
            startMinute = 0,
            endMinute = 0,
            allowedMinutes = 0,
            scope = AppRuleScope.forGroup(syntheticGroup.id)
        )
        val fixedSnapshot = AppRuleSnapshot(
            appGroups = listOf(syntheticGroup),
            appRules = listOf(syntheticDenyRule)
        )
        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        coordinator.accept(fixedSnapshot)

        blocker.recheckPostDelayed = { _, _ -> true }
        blocker.recheckRemoveCallback = { }

        val ledger = Array(LEDGER_CAPACITY) { AttemptSlot(it + 1) }
        val activeSlotRef = AtomicReference<AttemptSlot?>(null)
        val unverifiedIngressDetected = AtomicBoolean(false)

        blocker.decisionOutcomeSinkObserver = { outcome ->
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val slot = activeSlotRef.get()
            if (slot == null) {
                unverifiedIngressDetected.set(true)
            } else {
                val targetPkg = if (slot.fixtureLabel == FIXTURE_ALLOW) PACKAGE_ALLOW else PACKAGE_DENY
                val decision = outcome.packageDecisions.firstOrNull { it.packageName == targetPkg }
                if (decision != null) {
                    slot.selectedEndNs = nowNs
                    slot.selectedEndPresent = true
                    slot.packageDecisionCount = outcome.packageDecisions.size
                    slot.decision = if (decision.isAllowed) "ALLOW" else "DENY"
                    slot.denyingRuleCount = decision.denyingRuleIds.size
                    slot.sourceOrderIdentity = outcome.sourceOrderIdentity.value.toString()
                    slot.observationKind = ObservationKind.REAL_EVENT.name
                    slot.lifecycleGeneration = outcome.lifecycleGeneration.value
                    slot.acceptedRuntimeRevision = outcome.acceptedRuntimeRevision.value
                    slot.commitStatus = outcome.commitStatus.name
                    slot.publicationStatus = outcome.publicationStatus.name
                    slot.endLatch.countDown()
                }
            }
        }

        // Unmeasured sanity preflight
        run {
            activeFixturePackage = PACKAGE_ALLOW
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.packageName = PACKAGE_ALLOW
            blocker.doAppRuleCheck(event)
            event.recycle()

            activeFixturePackage = PACKAGE_DENY
            val denyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            denyEvent.packageName = PACKAGE_DENY
            blocker.doAppRuleCheck(denyEvent)
            denyEvent.recycle()

            blocker.closeGuardianForInstrumentation(PACKAGE_DENY)
            setField(blocker, "lastShownAt", 0L)
            service.capturedLaunches.clear()
            runBlocking {
                sessionRepository.finishOpenSessions("any", System.currentTimeMillis())
            }
        }

        var attemptIndex = 0
        var exclusionCount = 0
        var validWarmups = 0
        var validMeasuredAllow = 0
        var validMeasuredDeny = 0
        var aborted = false
        var abortReason: String? = null

        fun currentPhase(): String = if (validWarmups < WARMUP_COUNT) "WARMUP" else "MEASURED"

        fun nextFixtureLabel(): String {
            return if (validWarmups < WARMUP_COUNT) {
                if (validWarmups % 2 == 0) FIXTURE_ALLOW else FIXTURE_DENY
            } else {
                val totalValidMeasured = validMeasuredAllow + validMeasuredDeny
                if (totalValidMeasured % 2 == 0) FIXTURE_ALLOW else FIXTURE_DENY
            }
        }

        while (!aborted && (validWarmups < WARMUP_COUNT || (validMeasuredAllow + validMeasuredDeny) < TOTAL_MEASURED)) {
            if (attemptIndex >= LEDGER_CAPACITY) {
                aborted = true
                abortReason = "ABORT_LEDGER_CAPACITY"
                break
            }
            if (exclusionCount >= EXCLUSION_CAP) {
                aborted = true
                abortReason = "ABORT_EXCLUSION_CAP_REACHED"
                break
            }

            val slot = ledger[attemptIndex++]
            val fixtureLabel = nextFixtureLabel()
            val fixturePackage = if (fixtureLabel == FIXTURE_ALLOW) PACKAGE_ALLOW else PACKAGE_DENY
            activeFixturePackage = fixturePackage
            slot.phase = currentPhase()
            slot.fixtureLabel = fixtureLabel
            activeSlotRef.set(slot)

            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.packageName = fixturePackage

            val cbStart = SystemClock.elapsedRealtimeNanos()
            slot.callbackStartNs = cbStart

            try {
                blocker.doAppRuleCheck(event)
                slot.callbackReturnNs = SystemClock.elapsedRealtimeNanos()
                slot.callbackReturnPresent = true
                slot.callbackExitKind = "NORMAL"
            } catch (t: Throwable) {
                slot.callbackReturnNs = SystemClock.elapsedRealtimeNanos()
                slot.callbackReturnPresent = true
                slot.callbackExitKind = "EXCEPTION"
            } finally {
                event.recycle()
            }

            if (unverifiedIngressDetected.get()) {
                aborted = true
                abortReason = "ABORT_UNEXPECTED_INGRESS"
                slot.terminalState = "ABORT_UNEXPECTED_INGRESS"
                break
            }

            val elapsedAfterCallback = SystemClock.elapsedRealtimeNanos() - slot.callbackStartNs
            val remainingObservationNs = OBSERVATION_DEADLINE_NS - elapsedAfterCallback
            val observedB = if (remainingObservationNs > 0) {
                slot.endLatch.await(remainingObservationNs, TimeUnit.NANOSECONDS)
            } else {
                false
            }

            if (!observedB) {
                aborted = true
                slot.terminalState = "TIMEOUT_MISSING_SELECTED_END"
                abortReason = "ABORT_OBSERVATION_DEADLINE"
                break
            }

            val quiescenceStartNs = SystemClock.elapsedRealtimeNanos()
            slot.quiescenceStartNs = quiescenceStartNs

            // Drain any pending posted runnables from publishDecisionOutcome on Main Looper
            instrumentation.waitForIdleSync()

            // Post-sample recovery and quiescence polling up to recovery deadline R (5s)
            val quiescenceDeadlineNs = quiescenceStartNs + RECOVERY_DEADLINE_NS
            var quiescent = false
            while (SystemClock.elapsedRealtimeNanos() < quiescenceDeadlineNs) {
                instrumentation.waitForIdleSync()
                val activeGuardianBefore = getField(blocker, "activeGuardianPackage") as? String
                val guardianClosed = if (activeGuardianBefore != null) {
                    blocker.closeGuardianForInstrumentation(activeGuardianBefore)
                } else {
                    blocker.closeGuardianForInstrumentation(PACKAGE_DENY)
                }
                setField(blocker, "lastShownAt", 0L)
                service.capturedLaunches.clear()

                val activeGuardian = getField(blocker, "activeGuardianPackage")
                val lastShown = getField(blocker, "lastShownAt") as Long
                if (guardianClosed && activeGuardian == null && lastShown == 0L) {
                    quiescent = true
                    break
                }
                SystemClock.sleep(10L)
            }

            val quiescenceEndNs = SystemClock.elapsedRealtimeNanos()
            slot.quiescenceEndNs = quiescenceEndNs

            if (!quiescent) {
                slot.terminalState = "EXCLUDED_POST_SAMPLE_NOT_QUIESCENT"
                slot.exclusionReason = "GUARDIAN_OR_STATE_NOT_RESTORED"
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_RECOVERY_NOT_QUIESCENT"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            val expectedDecision = if (fixtureLabel == FIXTURE_ALLOW) "ALLOW" else "DENY"
            val expectedDenyingRules = if (fixtureLabel == FIXTURE_ALLOW) 0 else 1
            if (slot.decision != expectedDecision || slot.denyingRuleCount != expectedDenyingRules) {
                slot.terminalState = "EXCLUDED_INCORRECT_DECISION"
                slot.exclusionReason = "EXPECTED_${expectedDecision}_BUT_GOT_${slot.decision}_RULES_${slot.denyingRuleCount}"; println("MISMATCH: attempt=$attemptIndex fixture=$fixtureLabel expected=$expectedDecision got=${slot.decision} rules=${slot.denyingRuleCount}")
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_EXCLUSION_CAP_REACHED"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            slot.terminalState = "VALID_SAMPLE"
            if (slot.phase == "WARMUP") {
                validWarmups++
            } else {
                val sampleOrd = (validMeasuredAllow + validMeasuredDeny) + 1
                slot.sampleOrdinal = sampleOrd
                if (fixtureLabel == FIXTURE_ALLOW) validMeasuredAllow++ else validMeasuredDeny++
            }
            activeSlotRef.set(null)
        }

        blocker.onDestroy()

        val endedAtUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val attemptedRows = ledger.take(attemptIndex).map { it.toRow(runId) }

        val attemptTotals = mutableMapOf<String, Int>()
        attemptTotals["totalAttempts"] = attemptedRows.size
        attemptTotals["warmupAttempts"] = attemptedRows.count { it.phase == "WARMUP" }
        attemptTotals["measuredAttempts"] = attemptedRows.count { it.phase == "MEASURED" }
        attemptTotals["validWarmupSamples"] = attemptedRows.count { it.phase == "WARMUP" && it.terminalState == "VALID_SAMPLE" }
        attemptTotals["validMeasuredSamples"] = attemptedRows.count { it.phase == "MEASURED" && it.terminalState == "VALID_SAMPLE" }
        attemptTotals["validMeasuredAllowSamples"] = attemptedRows.count { it.phase == "MEASURED" && it.terminalState == "VALID_SAMPLE" && it.fixtureLabel == FIXTURE_ALLOW }
        attemptTotals["validMeasuredDenySamples"] = attemptedRows.count { it.phase == "MEASURED" && it.terminalState == "VALID_SAMPLE" && it.fixtureLabel == FIXTURE_DENY }

        val terminalTotals = mutableMapOf<String, Int>()
        for (row in attemptedRows) {
            terminalTotals[row.terminalState] = (terminalTotals[row.terminalState] ?: 0) + 1
        }

        val exclusionTotals = mutableMapOf<String, Int>()
        for (row in attemptedRows) {
            val reason = row.exclusionReason
            if (reason != null) {
                exclusionTotals[reason] = (exclusionTotals[reason] ?: 0) + 1
            }
        }

        val metadata = FixedFixtureMetadata(
            runId = runId,
            startedAtUtc = startedAtUtc,
            endedAtUtc = endedAtUtc,
            rawDeviceSerial = rawDeviceSerial,
            pseudonymousDeviceId = pseudonymousDeviceId,
            sourceCommit = "HEAD",
            harnessCommit = "HEAD",
            protocolRevision = PROTOCOL_REVISION,
            appVersion = "v4.0.4-debug",
            applicationId = EXPECTED_APPLICATION_ID,
            variant = "fullDebug",
            targetApkSha256 = "",
            instrumentationApkSha256 = "",
            buildFingerprint = "Alldocube/iPlay50_mini_Pro/iPlay50_mini_Pro:13/TP1A.220624.014/1699256002:user/release-keys",
            deviceModel = "iPlay50_mini_Pro",
            androidVersion = "13",
            apiLevel = 33,
            boundary = EXPECTED_BOUNDARY,
            observationDeadlineNs = OBSERVATION_DEADLINE_NS,
            recoveryDeadlineNs = RECOVERY_DEADLINE_NS,
            exclusionCap = EXCLUSION_CAP,
            populationOption = "M1",
            clockSource = "SystemClock.elapsedRealtimeNanos",
            stimulusOrder = "ALTERNATING_ALLOW_DENY",
            lifecycleGeneration = 1L,
            rootWindowConditions = "SYNTHETIC_SINGLE_WINDOW",
            attemptTotals = attemptTotals,
            terminalTotals = terminalTotals,
            exclusionTotals = exclusionTotals
        )

        val samplesBytes = attemptedRows.joinToString("") { serializeCanonicalSampleRow(it) }.toByteArray(Charsets.UTF_8)
        val metadataBytes = serializeCanonicalMetadata(metadata)

        val samplesSha256 = sha256Hex(samplesBytes)
        val metadataSha256 = sha256Hex(metadataBytes)

        val digests = DeviceDigests(
            samplesJsonlBytes = samplesBytes.size.toLong(),
            samplesJsonlSha256 = samplesSha256,
            metadataJsonBytes = metadataBytes.size.toLong(),
            metadataJsonSha256 = metadataSha256
        )
        val digestsBytes = serializeCanonicalDeviceDigests(digests)

        val stagingDir = File(targetContext.filesDir, "ticket27/")
        stagingDir.mkdirs()

        val samplesFile = File(stagingDir, "samples.jsonl")
        val metadataFile = File(stagingDir, "metadata.json")
        val digestsFile = File(stagingDir, "device-digests.json")

        FileOutputStream(samplesFile).use { fos ->
            fos.write(samplesBytes)
            fos.flush()
            fos.fd.sync()
        }

        FileOutputStream(metadataFile).use { fos ->
            fos.write(metadataBytes)
            fos.flush()
            fos.fd.sync()
        }

        FileOutputStream(digestsFile).use { fos ->
            fos.write(digestsBytes)
            fos.flush()
            fos.fd.sync()
        }

        val rereadSamples = samplesFile.readBytes()
        val rereadMetadata = metadataFile.readBytes()
        val rereadDigests = digestsFile.readBytes()

        assertEquals(samplesBytes.size.toLong(), rereadSamples.size.toLong())
        assertEquals(samplesSha256, sha256Hex(rereadSamples))
        assertEquals(metadataBytes.size.toLong(), rereadMetadata.size.toLong())
        assertEquals(metadataSha256, sha256Hex(rereadMetadata))

        assertNull("Measurement run must not abort: ", abortReason)
        assertEquals(WARMUP_COUNT, validWarmups)
        assertEquals(100, validMeasuredAllow)
        assertEquals(100, validMeasuredDeny)

        val validMeasuredRows = attemptedRows.filter { it.phase == "MEASURED" && it.terminalState == "VALID_SAMPLE" }
        assertEquals(TOTAL_MEASURED, validMeasuredRows.size)

        val sortedDurations = validMeasuredRows.map { it.selectedDurationNs!! }.sorted()
        val p95Ns = sortedDurations[189]
        val p95Ms = p95Ns / 1_000_000.0

        println("T27_MEASUREMENT_RUN_ID: ")
        println("T27_MEASUREMENT_P95_NS: ")
        println("T27_MEASUREMENT_P95_MS: ")
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(target)
}
