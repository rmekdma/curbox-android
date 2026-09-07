package neth.iecal.curbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.AppRuleTimeRange
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ticket19's smallest service-level tracer. It attaches the real service to a recording Context,
 * then drives the real setup, receiver, reconnect and destroy entry points on the main looper.
 * Reflection is limited to test configuration and identity observation; lifecycle calls are real.
 */
@RunWith(AndroidJUnit4::class)
class AppBlockerServiceLifecycleProbeTest {
    @Test
    fun reconnectKeepsAppRuleOwnershipTransactionalAndFencesStaleServiceEffects() {
        val harness = createHarness()
        try {
            val blocker = harness.appRuleBlocker()
            quiesceSettingsCollector(blocker)
            val appRuleReceivers = appRuleReceivers(blocker)
            val firstRegistrations = harness.context.receiverRegistrations.toList()
            val missingAppRuleReceivers = appRuleReceivers.filter { receiver ->
                firstRegistrations.none { it === receiver }
            }
            val appRuleReady = getField(harness.service, "appRuleBlockerReady")
            val blockerSetupReady = getField(blocker, "setupReady")
            assertTrue(
                "the actual service must register every AppRuleBlocker receiver; missing=" +
                    missingAppRuleReceivers.map(::identity) +
                    " registrations=${firstRegistrations.map(::identity)}" +
                    " serviceReady=$appRuleReady blockerReady=$blockerSetupReady",
                missingAppRuleReceivers.isEmpty()
            )

            (getField(blocker, "handler") as Handler).removeCallbacksAndMessages(null)
            configureFrameworkFacts(blocker)

            val staleEventEntered = CountDownLatch(1)
            val releaseStaleEvent = CountDownLatch(1)
            val currentEventReachedEvidence = CountDownLatch(1)
            val blockOnlyFirstEvent = AtomicBoolean(true)
            val evaluations = CopyOnWriteArrayList<AppRulesEvaluation>()
            val warningPackages = CopyOnWriteArrayList<String>()
            val notificationPublications = CopyOnWriteArrayList<Any>()
            val reconnectNotificationPublished = CountDownLatch(1)
            val expectedReconnectGeneration = lifecycleGeneration(blocker) + 1L

            blocker.evaluationResultObserver = { evaluations += it }
            blocker.warningBeforeFrameworkCallObserver = { warningPackages += it }
            blocker.notificationPublicationObserver = {
                notificationPublications += it
                if (lifecycleGeneration(blocker) >= expectedReconnectGeneration) {
                    reconnectNotificationPublished.countDown()
                }
            }
            blocker.foregroundEvidenceBeforeRecordObserver = {
                if (blockOnlyFirstEvent.compareAndSet(true, false)) {
                    staleEventEntered.countDown()
                    check(
                        releaseStaleEvent.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ) { "the stale event barrier was not released" }
                } else {
                    currentEventReachedEvidence.countDown()
                }
            }
            // The second setup must not run visible reconciliation before ownership is measured.
            // The first callback was removed above; this seam captures the reconnect callback
            // deterministically instead of relying on scheduler timing.
            blocker.visibleApplicationCheckPostDelayed = { _, _ -> true }

            val unregistersBeforeReconnect = harness.context.unregisterAttempts.toList()
            val staleEvent = Thread {
                dispatchWindowEvent(harness.service, TARGET_PACKAGE)
            }
            staleEvent.start()
            assertTrue(
                "the event must cross the real service boundary before reconnect",
                staleEventEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            val generationBeforeReconnect = lifecycleGeneration(blocker)
            harness.connect()
            val generationAfterReconnect = lifecycleGeneration(blocker)
            assertTrue(
                "reconnect must advance the AppRuleBlocker lifecycle generation",
                generationAfterReconnect > generationBeforeReconnect
            )
            assertTrue(
                "reconnect must publish through the real notification path",
                reconnectNotificationPublished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            quiesceSettingsCollector(blocker)
            configureFrameworkFacts(blocker)

            val secondRegistrations = harness.context.receiverRegistrations.toList()
            val unregistersDuringReconnect = harness.context.unregisterAttempts
                .drop(unregistersBeforeReconnect.size)
            val duplicateNonAppRuleReceivers = firstRegistrations
                .filterNot { receiver -> appRuleReceivers.any { it === receiver } }
                .distinctIdentity()
                .filter { receiver ->
                    countIdentity(secondRegistrations, receiver) > countIdentity(
                        firstRegistrations,
                        receiver
                    ) && unregistersDuringReconnect.none { it === receiver }
                }
            assertTrue(
                "reconnect ownership evidence must include a non-AppRule receiver registered " +
                    "again without a service-level cleanup boundary",
                duplicateNonAppRuleReceivers.isNotEmpty()
            )
            assertTrue(
                "AppRuleBlocker receiver ownership must be reclaimed before its new setup",
                appRuleReceivers.all { receiver ->
                    harness.context.unregisterAttempts.any { it === receiver }
                }
            )
            Log.i(
                EVIDENCE_TAG,
                "reconnect generation $generationBeforeReconnect->$generationAfterReconnect " +
                    "registrations ${firstRegistrations.size}->${secondRegistrations.size}; " +
                    "duplicateNonApp=${duplicateNonAppRuleReceivers.size}; " +
                    "appRuleReclaimed=${appRuleReceivers.size}"
            )

            // Only the event held across reconnect may affect this assertion. Reconnect's own
            // publication and any setup noise are removed before the stale event is released.
            evaluations.clear()
            warningPackages.clear()
            releaseStaleEvent.countDown()
            staleEvent.join(WAIT_TIMEOUT_MS)
            assertFalse("the stale service event must finish after reconnect", staleEvent.isAlive)
            assertTrue(
                "the old generation must not publish an evaluation after reconnect",
                evaluations.isEmpty()
            )
            assertTrue(
                "the old generation must not publish a warning after reconnect",
                warningPackages.isEmpty()
            )

            // Install a deterministic denying snapshot only after reconnect. The publication is
            // queued before the next event, so the worker observes it without timing luck.
            harness.service.startedActivities.clear()
            val denialActivityPublished = CountDownLatch(1)
            harness.service.onStartActivity = { denialActivityPublished.countDown() }
            installSnapshot(harness.service, blocker, denyingSnapshot(), generationAfterReconnect)
            val denialObserved = CountDownLatch(1)
            val warningObserved = CountDownLatch(1)
            blocker.evaluationResultObserver = { evaluation ->
                evaluations += evaluation
                if (!evaluation.isAllowed) denialObserved.countDown()
            }
            blocker.warningBeforeFrameworkCallObserver = {
                warningPackages += it
                warningObserved.countDown()
            }
            dispatchWindowEvent(harness.service, TARGET_PACKAGE)
            assertTrue(
                "the current event must reach AppRuleBlocker through AppBlockerService",
                currentEventReachedEvidence.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "the current generation must publish a denial evaluation; " +
                    "observed=${evaluations.map { it.isAllowed }}",
                denialObserved.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "the current generation must publish the denial warning",
                warningObserved.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "the service must reach the current denial's deterministic external boundary",
                warningPackages.contains(TARGET_PACKAGE)
            )
            assertTrue(
                "the denial must publish exactly one external approval activity",
                denialActivityPublished.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertEquals(1, harness.service.startedActivities.size)
            val warningCountAfterDenial = warningPackages.size

            val allowObserved = CountDownLatch(1)
            harness.service.onStartActivity = null
            installSnapshot(harness.service, blocker, AppRuleSnapshot(), generationAfterReconnect)
            blocker.evaluationResultObserver = { evaluation ->
                evaluations += evaluation
                if (evaluation.isAllowed) allowObserved.countDown()
            }
            dispatchWindowEvent(harness.service, TARGET_PACKAGE)
            assertTrue(
                "the current generation must publish the subsequent allow decision",
                allowObserved.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertEquals(
                "an allow publication must not add a warning activity",
                warningCountAfterDenial,
                warningPackages.size
            )
            assertEquals(
                "an allow decision must not publish another external activity",
                1,
                harness.service.startedActivities.size
            )
            assertTrue(
                "the real service notification publication must be observable",
                notificationPublications.isNotEmpty()
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun destroyContainsReceiverFaultAndSuppressesBlockedNotificationPublication() {
        val harness = createHarness()
        try {
            val blocker = harness.appRuleBlocker()
            quiesceSettingsCollector(blocker)
            val appRuleReceivers = appRuleReceivers(blocker)
            val focusReceiver = nestedReceiver(harness.service, "focusModeBlocker", "refreshReceiver")
            val reelReceiver = nestedReceiver(harness.service, "reelBlocker", "refreshReceiver")
            assertNotNull("FocusModeBlocker receiver must be registered", focusReceiver)
            assertNotNull("ReelBlocker receiver must be registered", reelReceiver)
            assertTrue(
                "the actual service setup must register the fault target",
                harness.context.receiverRegistrations.any { it === focusReceiver }
            )
            assertTrue(
                "the actual service setup must register a later feature receiver",
                harness.context.receiverRegistrations.any { it === reelReceiver }
            )

            configureFrameworkFacts(blocker)
            val generation = lifecycleGeneration(blocker)
            installSnapshot(harness.service, blocker, denyingSnapshot(), generation)

            val notificationEntered = CountDownLatch(1)
            val releaseNotification = CountDownLatch(1)
            val notificationObserverExited = CountDownLatch(1)
            val notificationPublications = CopyOnWriteArrayList<Any>()
            blocker.notificationBeforeFrameworkCallObserver = {
                notificationEntered.countDown()
                try {
                    releaseNotification.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Destruction may cancel the IO coroutine while the deterministic barrier is held.
                } finally {
                    notificationObserverExited.countDown()
                }
            }
            blocker.notificationPublicationObserver = { notificationPublications += it }
            harness.context.faultOnUnregister = focusReceiver
            blocker.updateLiveNotification()
            assertTrue(
                "the real service notification path must reach its final framework guard",
                notificationEntered.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )

            harness.destroy()
            releaseNotification.countDown()
            assertTrue(
                "the blocked notification callback must leave its barrier",
                notificationObserverExited.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "the injected cleanup failure must be observed",
                harness.context.failedUnregisters.any { it === focusReceiver }
            )
            assertTrue(
                "AppRuleBlocker cleanup must run before the injected feature failure",
                appRuleReceivers.all { receiver ->
                    harness.context.unregisterAttempts.any { it === receiver }
                }
            )
            assertTrue(
                "a later feature cleanup must run after the injected failure",
                harness.context.unregisterAttempts.any { it === reelReceiver }
            )
            assertTrue(
                "destroy must invalidate receiver ownership",
                getField(blocker, "receiverLifecycle") == null
            )
            assertTrue(
                "destroy must suppress the blocked notification's external publication",
                notificationPublications.isEmpty()
            )
            Log.i(
                EVIDENCE_TAG,
                "destroy cleanup fault=${harness.context.failedUnregisters.size}; " +
                    "unregisterAttempts=${harness.context.unregisterAttempts.size}; " +
                    "laterReceiverAttempted=" +
                    harness.context.unregisterAttempts.any { it === reelReceiver } +
                    "; notificationPublications=${notificationPublications.size}"
            )
        } finally {
            harness.close()
        }
    }

    private fun createHarness(): ServiceHarness {
        val service = ProbeService()
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val recordingContext = RecordingContext(baseContext)
        val attach = ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java
        ).apply { isAccessible = true }
        attach.invoke(service, recordingContext)
        service.lastBackPressTimeStamp = 0L
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { service.onCreate() }
        val harness = ServiceHarness(service, recordingContext)
        harness.connect()
        return harness
    }

    private fun configureFrameworkFacts(blocker: AppRuleBlocker) {
        // Keep service setup and worker ownership real, but replace only the framework window
        // reader so the synthetic event has deterministic, production-shaped facts.
        setField(blocker, "foregroundObservationSource", null)
        blocker.screenInteractiveProvider = { true }
        blocker.keyguardLockedProvider = { false }
        blocker.applicationWindowSnapshotProvider = {
            AppRuleBlocker.ApplicationWindowSnapshot(
                packages = setOf(TARGET_PACKAGE),
                hasApplicationWindow = true,
                hasUnknownApplicationWindow = false,
                applicationWindowCount = 1
            )
        }
        blocker.activeWindowSnapshotProvider = {
            AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
        }
    }

    private fun installSnapshot(
        service: AppBlockerService,
        blocker: AppRuleBlocker,
        snapshot: AppRuleSnapshot,
        generation: Long
    ) {
        val currentSettings = runBlocking { service.dataStoreManager.settings.first() }
        invokePrivate(
            blocker,
            "applySettingsSnapshot",
            currentSettings.copy(appRuleSnapshot = snapshot)
        )
        invokePrivate(blocker, "submitRuntimePublication", generation)
    }

    private fun quiesceSettingsCollector(blocker: AppRuleBlocker) {
        val settingsJob = getField(blocker, "settingsJob") as? Job ?: return
        settingsJob.cancel()
        runBlocking { settingsJob.join() }
    }

    private fun dispatchWindowEvent(service: AppBlockerService, packageName: String) {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = packageName
        try {
            service.onAccessibilityEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun appRuleReceivers(blocker: AppRuleBlocker): List<BroadcastReceiver> = listOf(
        "refreshReceiver",
        "packageReceiver",
        "screenReceiver",
        "schedulerWakeReceiver",
        "guardianReceiver"
    ).map { name ->
        getField(blocker, name) as BroadcastReceiver
    }

    private fun nestedReceiver(
        service: AppBlockerService,
        owner: String,
        receiver: String
    ): BroadcastReceiver = getField(getField(service, owner)!!, receiver) as BroadcastReceiver

    private fun lifecycleGeneration(blocker: AppRuleBlocker): Long =
        (getField(blocker, "lifecycleGeneration") as AtomicLong).get()

    private fun denyingSnapshot(): AppRuleSnapshot {
        val groupId = "ticket19-target-group"
        return AppRuleSnapshot(
            appGroups = listOf(
                AppRuleAppGroup(
                    id = groupId,
                    name = "Ticket19 target",
                    selectedPackages = listOf(TARGET_PACKAGE)
                )
            ),
            appRules = listOf(
                AppRule(
                    id = "ticket19-deny-rule",
                    name = "Ticket19 denial",
                    weekdays = (0..6).toSet(),
                    scope = AppRuleScope.forGroup(groupId),
                    allowedMinutes = 0L,
                    timeRanges = listOf(AppRuleTimeRange(0, 0))
                )
            )
        )
    }

    private fun countIdentity(values: List<BroadcastReceiver>, target: BroadcastReceiver): Int =
        values.count { it === target }

    private fun identity(receiver: BroadcastReceiver): String =
        receiver.javaClass.name + "@" + System.identityHashCode(receiver).toString(16)

    private fun List<BroadcastReceiver>.distinctIdentity(): List<BroadcastReceiver> {
        val result = mutableListOf<BroadcastReceiver>()
        forEach { candidate ->
            if (result.none { it === candidate }) result += candidate
        }
        return result
    }

    private fun getField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("field $name was not found on ${target.javaClass.name}")
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                type.getDeclaredField(name).apply {
                    isAccessible = true
                    set(target, value)
                }
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("field $name was not found on ${target.javaClass.name}")
    }

    private fun invokePrivate(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first { candidate ->
            (candidate.name == name || candidate.name.startsWith("$name-")) &&
                candidate.parameterTypes.size == args.size
        }.apply { isAccessible = true }
        method.invoke(target, *args)
    }

    private inner class ServiceHarness(
        val service: ProbeService,
        val context: RecordingContext
    ) {
        private var destroyed = false

        fun appRuleBlocker(): AppRuleBlocker =
            getField(service, "appRuleBlocker") as AppRuleBlocker

        fun connect() {
            check(!destroyed) { "service already destroyed" }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppBlockerService::class.java.getDeclaredMethod("onServiceConnected").apply {
                    isAccessible = true
                }.invoke(service)
            }
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.onDestroy()
            }
        }

        fun close() {
            runCatching { destroy() }
        }
    }

    private class ProbeService : AppBlockerService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()
        var onStartActivity: ((Intent) -> Unit)? = null

        override fun startForegroundService() = Unit

        override fun startActivity(intent: Intent) {
            startedActivities += intent
            onStartActivity?.invoke(intent)
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val receiverRegistrations = CopyOnWriteArrayList<BroadcastReceiver>()
        val unregisterAttempts = CopyOnWriteArrayList<BroadcastReceiver>()
        val failedUnregisters = CopyOnWriteArrayList<BroadcastReceiver>()
        @Volatile var faultOnUnregister: BroadcastReceiver? = null

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?
        ): Intent? {
            if (receiver != null) receiverRegistrations += receiver
            return super.registerReceiver(receiver, filter)
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int
        ): Intent? {
            if (receiver != null) receiverRegistrations += receiver
            return super.registerReceiver(receiver, filter, flags)
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: Handler?,
            flags: Int
        ): Intent? {
            if (receiver != null) receiverRegistrations += receiver
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregisterAttempts += receiver
            if (receiver === faultOnUnregister && failedUnregisters.none { it === receiver }) {
                failedUnregisters += receiver
                throw IllegalStateException("ticket19 injected unregister failure")
            }
            super.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.ticket19.target"
        const val WAIT_TIMEOUT_MS = 3_000L
        const val EVIDENCE_TAG = "Ticket19Evidence"
    }
}
