package neth.iecal.curbox.blockers

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.domain.apprules.ObservationKind
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    private fun File.writeWithSync(bytes: ByteArray) {
        FileOutputStream(this).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
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
        val sourceCommit = args.getString("t27SourceCommit") ?: "01ab394e7f0a9a419a70067d824ec86dcfd2023a"
        val harnessCommit = args.getString("t27HarnessCommit") ?: "01ab394e7f0a9a419a70067d824ec86dcfd2023a"
        val targetApkSha256 = args.getString("t27TargetApkSha256") ?: "d5ae92ebd9973dcab23e763ce03183d912d0520f60d60289325ffaf0ef4d7acd"
        val instrumentationApkSha256 = args.getString("t27InstrumentationApkSha256") ?: "646d5f830d4260d4dbbfdf3f03c2dd90c02513a62588d4f0f293b1999f0a379b"

        val runId = "T27-" + Instant.now().epochSecond + "-" + (1000..9999).random()
        val startedAtUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val service = FixedFixtureMeasurementService().also { it.attach(targetContext) }
        val database = AppDatabase.getInstance(targetContext)
        val sessionRepository = RoomCurrentUseDaySessionRepository(
            database.foregroundSessionDao(),
            database.foregroundLaunchDao(),
            database.appUsageDao(),
            database
        )
        val usageResetRepository = RoomUsageResetRepository(database)
        val blocker = AppRuleBlocker()

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", sessionRepository)
        setField(blocker, "usageResetRepository", usageResetRepository)
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

        val unexpectedRecheckDetected = AtomicBoolean(false)
        blocker.recheckPostDelayed = { _, _ ->
            unexpectedRecheckDetected.set(true)
            true
        }
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
            val sanityAllowOutcomeRef = AtomicReference<DecisionOutcome?>(null)
            val sanityDenyOutcomeRef = AtomicReference<DecisionOutcome?>(null)
            val sanityLatch = CountDownLatch(1)

            blocker.decisionOutcomeSinkObserver = { outcome ->
                val allowDec = outcome.packageDecisions.firstOrNull { it.packageName == PACKAGE_ALLOW }
                val denyDec = outcome.packageDecisions.firstOrNull { it.packageName == PACKAGE_DENY }
                if (allowDec != null) sanityAllowOutcomeRef.set(outcome)
                if (denyDec != null) {
                    sanityDenyOutcomeRef.set(outcome)
                    sanityLatch.countDown()
                }
            }

            activeFixturePackage = PACKAGE_ALLOW
            val allowEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            allowEvent.packageName = PACKAGE_ALLOW
            instrumentation.runOnMainSync {
                blocker.doAppRuleCheck(allowEvent)
            }
            allowEvent.recycle()

            activeFixturePackage = PACKAGE_DENY
            val denyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            denyEvent.packageName = PACKAGE_DENY
            instrumentation.runOnMainSync {
                blocker.doAppRuleCheck(denyEvent)
            }
            denyEvent.recycle()

            assertTrue("Sanity phase must reach decision outcome sink", sanityLatch.await(5, TimeUnit.SECONDS))
            val sanityAllow = sanityAllowOutcomeRef.get()?.packageDecisions?.firstOrNull { it.packageName == PACKAGE_ALLOW }
            val sanityDeny = sanityDenyOutcomeRef.get()?.packageDecisions?.firstOrNull { it.packageName == PACKAGE_DENY }
            assertNotNull("Sanity allow decision must be present", sanityAllow)
            assertTrue("Sanity allow must be allowed", sanityAllow!!.isAllowed)
            assertNotNull("Sanity deny decision must be present", sanityDeny)
            assertFalse("Sanity deny must be denied", sanityDeny!!.isAllowed)
            assertEquals("Sanity deny must have 1 denying rule", 1, sanityDeny.denyingRuleIds.size)

            blocker.closeGuardianForInstrumentation(PACKAGE_DENY)
            setField(blocker, "lastShownAt", 0L)
            service.capturedLaunches.clear()
            runBlocking {
                sessionRepository.finishOpenSessions(PACKAGE_ALLOW, System.currentTimeMillis())
                sessionRepository.finishOpenSessions(PACKAGE_DENY, System.currentTimeMillis())
            }
            assertFalse("Unexpected recheck must not occur during sanity preflight", unexpectedRecheckDetected.get())

            // Restore decision outcome sink observer for measured attempts
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

            instrumentation.runOnMainSync {
                slot.callbackStartNs = SystemClock.elapsedRealtimeNanos()
                try {
                    blocker.doAppRuleCheck(event)
                    slot.callbackReturnNs = SystemClock.elapsedRealtimeNanos()
                    slot.callbackReturnPresent = true
                    slot.callbackExitKind = "NORMAL"
                } catch (ce: CancellationException) {
                    slot.callbackReturnNs = SystemClock.elapsedRealtimeNanos()
                    slot.callbackReturnPresent = true
                    slot.callbackExitKind = "CANCELLATION"
                } catch (t: Throwable) {
                    slot.callbackReturnNs = SystemClock.elapsedRealtimeNanos()
                    slot.callbackReturnPresent = true
                    slot.callbackExitKind = "EXCEPTION"
                } finally {
                    event.recycle()
                }
            }

            if (unverifiedIngressDetected.get() || unexpectedRecheckDetected.get()) {
                aborted = true
                abortReason = "ABORT_UNEXPECTED_INGRESS"
                slot.terminalState = "ABORT_UNEXPECTED_INGRESS"
                break
            }

            val elapsedAfterCallback = SystemClock.elapsedRealtimeNanos() - slot.callbackStartNs
            val remainingNs = OBSERVATION_DEADLINE_NS - elapsedAfterCallback
            val outcomeObserved = if (remainingNs > 0) {
                slot.endLatch.await(remainingNs, TimeUnit.NANOSECONDS)
            } else false

            if (!outcomeObserved) {
                val hasReturn = slot.callbackReturnPresent
                val hasEnd = slot.selectedEndPresent
                slot.terminalState = when {
                    !hasReturn && !hasEnd -> "TIMEOUT_MISSING_BOTH"
                    !hasReturn -> "TIMEOUT_MISSING_CALLBACK_RETURN"
                    else -> "TIMEOUT_MISSING_SELECTED_END"
                }
                aborted = true
                abortReason = "ABORT_OBSERVATION_DEADLINE"
                break
            }

            if (slot.callbackExitKind != "NORMAL") {
                slot.terminalState = "EXCLUDED_CALLBACK_EXIT_EXCEPTION"
                slot.exclusionReason = "CALLBACK_EXIT_" + slot.callbackExitKind
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_EXCLUSION_CAP_REACHED"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            if (slot.selectedEndNs!! < slot.callbackStartNs) {
                slot.terminalState = "EXCLUDED_CLOCK_ORDER_FAILURE"
                slot.exclusionReason = "CLOCK_ORDER_VIOLATION"
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_EXCLUSION_CAP_REACHED"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            val quiescenceStartNs = SystemClock.elapsedRealtimeNanos()
            slot.quiescenceStartNs = quiescenceStartNs

            val expectedDecision = if (fixtureLabel == FIXTURE_ALLOW) "ALLOW" else "DENY"
            val expectedDenyingRules = if (fixtureLabel == FIXTURE_ALLOW) 0 else 1
            if (slot.decision != expectedDecision || slot.denyingRuleCount != expectedDenyingRules) {
                slot.terminalState = "EXCLUDED_INCORRECT_DECISION"
                slot.exclusionReason = "EXPECTED_${expectedDecision}_BUT_GOT_${slot.decision}"
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_EXCLUSION_CAP_REACHED"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            var quiescenceSuccessful = true
            if (fixtureLabel == FIXTURE_DENY) {
                val guardianClosed = blocker.closeGuardianForInstrumentation(PACKAGE_DENY)
                if (!guardianClosed) quiescenceSuccessful = false
                setField(blocker, "lastShownAt", 0L)
            }
            service.capturedLaunches.clear()

            runBlocking {
                sessionRepository.finishOpenSessions(PACKAGE_ALLOW, System.currentTimeMillis())
                sessionRepository.finishOpenSessions(PACKAGE_DENY, System.currentTimeMillis())
            }

            val quiescenceEndNs = SystemClock.elapsedRealtimeNanos()
            slot.quiescenceEndNs = quiescenceEndNs

            if (!quiescenceSuccessful || (quiescenceEndNs - quiescenceStartNs) > RECOVERY_DEADLINE_NS) {
                slot.terminalState = "EXCLUDED_POST_SAMPLE_NOT_QUIESCENT"
                slot.exclusionReason = "QUIESCENCE_FAILED_OR_TIMEOUT"
                exclusionCount++
                if (exclusionCount >= EXCLUSION_CAP) {
                    aborted = true
                    abortReason = "ABORT_RECOVERY_NOT_QUIESCENT"
                    break
                }
                activeSlotRef.set(null)
                continue
            }

            slot.terminalState = "VALID_SAMPLE"
            if (slot.phase == "WARMUP") {
                slot.sampleOrdinal = validWarmups + 1
                validWarmups++
            } else {
                val measuredOrdinal = validMeasuredAllow + validMeasuredDeny + 1
                slot.sampleOrdinal = measuredOrdinal
                if (slot.fixtureLabel == FIXTURE_ALLOW) validMeasuredAllow++ else validMeasuredDeny++
            }

            activeSlotRef.set(null)
        }

        val endedAtUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val attemptedRows = ledger.take(attemptIndex).map { it.toRow(runId) }

        val attemptTotals = mapOf(
            "totalAttempts" to attemptedRows.size,
            "warmupAttempts" to attemptedRows.count { it.phase == "WARMUP" },
            "measuredAttempts" to attemptedRows.count { it.phase == "MEASURED" },
            "validWarmupSamples" to validWarmups,
            "validMeasuredSamples" to (validMeasuredAllow + validMeasuredDeny),
            "validMeasuredAllowSamples" to validMeasuredAllow,
            "validMeasuredDenySamples" to validMeasuredDeny
        )
        val terminalTotals = attemptedRows.groupBy { it.terminalState }.mapValues { it.value.size }
        val exclusionTotals = attemptedRows.filter { it.exclusionReason != null }.groupBy { it.exclusionReason!! }.mapValues { it.value.size }

        val metadata = FixedFixtureMetadata(
            runId = runId,
            startedAtUtc = startedAtUtc,
            endedAtUtc = endedAtUtc,
            rawDeviceSerial = rawDeviceSerial,
            pseudonymousDeviceId = pseudonymousDeviceId,
            sourceCommit = sourceCommit,
            harnessCommit = harnessCommit,
            protocolRevision = PROTOCOL_REVISION,
            appVersion = "v4.0.4-debug",
            applicationId = EXPECTED_APPLICATION_ID,
            variant = "fullDebug",
            targetApkSha256 = targetApkSha256,
            instrumentationApkSha256 = instrumentationApkSha256,
            buildFingerprint = android.os.Build.FINGERPRINT,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
            apiLevel = android.os.Build.VERSION.SDK_INT,
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

        val stagingDir = File(targetContext.filesDir, "ticket27/$runId")
        stagingDir.mkdirs()

        val samplesFile = File(stagingDir, "samples.jsonl")
        val metadataFile = File(stagingDir, "metadata.json")
        val digestsFile = File(stagingDir, "device-digests.json")

        samplesFile.writeWithSync(samplesBytes)
        metadataFile.writeWithSync(metadataBytes)
        digestsFile.writeWithSync(digestsBytes)

        val rereadSamples = samplesFile.readBytes()
        val rereadMetadata = metadataFile.readBytes()
        val rereadDigests = digestsFile.readBytes()

        assertEquals(samplesBytes.size.toLong(), rereadSamples.size.toLong())
        assertEquals(samplesSha256, sha256Hex(rereadSamples))
        assertEquals(metadataBytes.size.toLong(), rereadMetadata.size.toLong())
        assertEquals(metadataSha256, sha256Hex(rereadMetadata))
        assertEquals(digestsBytes.size.toLong(), rereadDigests.size.toLong())
        assertEquals(sha256Hex(digestsBytes), sha256Hex(rereadDigests))

        assertNull("Measurement run must not abort: $abortReason", abortReason)
        assertEquals(WARMUP_COUNT, validWarmups)
        assertEquals(100, validMeasuredAllow)
        assertEquals(100, validMeasuredDeny)

        val validMeasuredRows = attemptedRows.filter { it.phase == "MEASURED" && it.terminalState == "VALID_SAMPLE" }
        assertEquals(TOTAL_MEASURED, validMeasuredRows.size)

        val sortedDurations = validMeasuredRows.map { it.selectedDurationNs!! }.sorted()
        val p95Ns = sortedDurations[189]
        val p95Ms = p95Ns / 1_000_000.0

        println("T27_MEASUREMENT_RUN_ID: $runId")
        println("T27_MEASUREMENT_P95_NS: $p95Ns")
        println("T27_MEASUREMENT_P95_MS: $p95Ms")
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
