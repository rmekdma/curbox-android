package neth.iecal.curbox.debug

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.services.AppBlockerService
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object Ticket19ObserverRegistry {
    private const val REFRESH_ACTION = "neth.iecal.curbox.refresh.app_rules"
    private const val SCHEDULER_ACTION =
        "neth.iecal.curbox.blockers.APP_RULE_SCHEDULER_WAKE"
    private const val GUARDIAN_CLOSED_ACTION =
        "neth.iecal.curbox.guardian.approval.closed"
    private const val GUARDIAN_OPENED_ACTION =
        "neth.iecal.curbox.guardian.approval.opened"
    private const val MAX_EVENTS = 100

    private val lock = Any()
    private val processToken = UUID.randomUUID().toString()
    private val processStartedAtElapsedMs = SystemClock.elapsedRealtime()
    private var serviceGeneration = 0L
    private var serviceRef: WeakReference<AppBlockerService>? = null
    private var blockerRef: WeakReference<AppRuleBlocker>? = null
    private var serviceIdentity = 0
    private var runtimePublicationCount = 0L
    private var notificationPublicationCount = 0L
    private var evaluationCount = 0L
    private var allowedEvaluationCount = 0L
    private var deniedEvaluationCount = 0L
    private var warningFrameworkBoundaryCount = 0L
    private var refreshContinuationCompletionCount = 0L
    private var lastEvaluatedPackage = ""
    private var failNextRuntimePublication = false
    private var runtimeBarrier: RuntimeBarrier? = null
    private val failures = mutableListOf<FailureRecord>()
    private val events = mutableListOf<EventRecord>()

    private data class FailureRecord(
        val stage: String,
        val type: String,
        val message: String,
        val elapsedMs: Long
    )

    private data class EventRecord(
        val name: String,
        val elapsedMs: Long,
        val detail: String
    )

    private class RuntimeBarrier(val timeoutMs: Long) {
        val release = CountDownLatch(1)
        var state: String = "ARMED"
        var enteredAtElapsedMs: Long? = null
        var releasedAtElapsedMs: Long? = null
    }

    fun attach(service: AppBlockerService) {
        try {
            val blocker = readField(service, "appRuleBlocker") as AppRuleBlocker
            synchronized(lock) {
                serviceGeneration += 1L
                serviceRef = WeakReference(service)
                blockerRef = WeakReference(blocker)
                serviceIdentity = System.identityHashCode(service)
                resetObservationLocked(clearEvents = false)
                recordEventLocked(
                    name = "service_instantiated",
                    detail = "generation=$serviceGeneration identity=$serviceIdentity"
                )
            }
            installPublicationObservers(blocker)
        } catch (error: Throwable) {
            recordFailure("attach", error)
        }
    }

    fun command(name: String, argument: Long): String {
        try {
            when (name) {
                "snapshot" -> Unit
                "reset_observation" -> synchronized(lock) {
                    resetObservationLocked(clearEvents = true)
                    recordEventLocked("observation_reset", "")
                }
                "arm_runtime_barrier" -> armRuntimeBarrier(argument)
                "release_runtime_barrier" -> releaseRuntimeBarrier()
                "await_refresh_continuation" -> awaitRefreshContinuation(argument)
                "await_quiescence" -> awaitQuiescence(argument)
                "fail_next_runtime_publication" -> synchronized(lock) {
                    failNextRuntimePublication = true
                    recordEventLocked("runtime_failure_armed", "")
                }
                "reapply_app_rule_receivers" -> reapplyAppRuleReceivers()
                "terminate_process" -> terminateProcess(argument)
                else -> error("unknown ticket19 observer command: $name")
            }
        } catch (error: Throwable) {
            recordFailure("command:$name", error)
        }
        return snapshot()
    }

    private fun installPublicationObservers(blocker: AppRuleBlocker) {
        val previousRuntimeObserver = blocker.runtimePublicationBeforeWorkerHandoff
        blocker.runtimePublicationBeforeWorkerHandoff = { revision ->
            previousRuntimeObserver?.invoke(revision)
            observeRuntimePublication()
        }
        val previousNotificationObserver = blocker.notificationPublicationObserver
        blocker.notificationPublicationObserver = { model ->
            previousNotificationObserver?.invoke(model)
            synchronized(lock) {
                notificationPublicationCount += 1L
                recordEventLocked("notification_published", model.title)
            }
        }
        val previousForegroundObserver = blocker.foregroundEvidenceRecordObserver
        blocker.foregroundEvidenceRecordObserver = { packageName ->
            previousForegroundObserver?.invoke(packageName)
            synchronized(lock) {
                lastEvaluatedPackage = packageName
                recordEventLocked("foreground_evidence", packageName)
            }
        }
        val previousEvaluationObserver = blocker.evaluationResultObserver
        blocker.evaluationResultObserver = { evaluation ->
            previousEvaluationObserver?.invoke(evaluation)
            synchronized(lock) {
                evaluationCount += 1L
                if (evaluation.isAllowed) {
                    allowedEvaluationCount += 1L
                } else {
                    deniedEvaluationCount += 1L
                }
                recordEventLocked(
                    "evaluation_completed",
                    "package=$lastEvaluatedPackage allowed=${evaluation.isAllowed}"
                )
            }
        }
        val previousWarningObserver = blocker.warningBeforeFrameworkCallObserver
        blocker.warningBeforeFrameworkCallObserver = { packageName ->
            previousWarningObserver?.invoke(packageName)
            synchronized(lock) {
                warningFrameworkBoundaryCount += 1L
                recordEventLocked("warning_framework_boundary", packageName)
            }
        }
    }

    private fun observeRuntimePublication() {
        var barrier: RuntimeBarrier? = null
        var injectedFailure: Throwable? = null
        synchronized(lock) {
            runtimePublicationCount += 1L
            recordEventLocked("runtime_publication", "count=$runtimePublicationCount")
            if (failNextRuntimePublication) {
                failNextRuntimePublication = false
                injectedFailure = IllegalStateException(
                    "ticket19 injected runtime publication failure"
                )
            } else {
                runtimeBarrier?.takeIf { it.state == "ARMED" }?.let {
                    it.state = "ENTERED"
                    it.enteredAtElapsedMs = SystemClock.elapsedRealtime()
                    recordEventLocked("runtime_barrier_entered", "timeoutMs=${it.timeoutMs}")
                    barrier = it
                }
            }
        }
        injectedFailure?.let { error ->
            recordFailure("runtime_publication_injected", error)
            throw error
        }
        barrier?.let { activeBarrier ->
            val released = try {
                activeBarrier.release.await(activeBarrier.timeoutMs, TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                recordFailure("runtime_barrier_interrupted", error)
                throw IllegalStateException("ticket19 runtime barrier interrupted", error)
            }
            if (!released) {
                val error = IllegalStateException(
                    "ticket19 runtime barrier timed out after ${activeBarrier.timeoutMs}ms"
                )
                synchronized(lock) {
                    activeBarrier.state = "TIMED_OUT"
                    recordEventLocked("runtime_barrier_timed_out", "")
                }
                recordFailure("runtime_barrier_timeout", error)
                throw error
            }
        }
    }

    private fun armRuntimeBarrier(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "runtime barrier timeout must be between 1000ms and 30000ms"
        }
        synchronized(lock) {
            runtimeBarrier?.release?.countDown()
            runtimeBarrier = RuntimeBarrier(timeoutMs)
            recordEventLocked("runtime_barrier_armed", "timeoutMs=$timeoutMs")
        }
    }

    private fun releaseRuntimeBarrier() {
        synchronized(lock) {
            val barrier = runtimeBarrier ?: return
            barrier.state = "RELEASED"
            barrier.releasedAtElapsedMs = SystemClock.elapsedRealtime()
            barrier.release.countDown()
            recordEventLocked("runtime_barrier_released", "")
        }
    }

    private fun awaitRefreshContinuation(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "continuation timeout must be between 1000ms and 30000ms"
        }
        synchronized(lock) {
            require(runtimeBarrier?.state == "RELEASED") {
                "runtime barrier must be released before awaiting continuation"
            }
        }
        awaitQuiescenceInternal(timeoutMs)
        synchronized(lock) {
            refreshContinuationCompletionCount += 1L
            recordEventLocked("refresh_continuation_completed", "")
        }
    }

    private fun awaitQuiescence(timeoutMs: Long) {
        awaitQuiescenceInternal(timeoutMs)
        synchronized(lock) {
            recordEventLocked("app_rule_quiescence_acknowledged", "")
        }
    }

    private fun awaitQuiescenceInternal(timeoutMs: Long) {
        require(timeoutMs in 1_000L..30_000L) {
            "quiescence timeout must be between 1000ms and 30000ms"
        }
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (appRuleWorkCounts(blocker).values.all { it == 0 }) return
            Thread.sleep(10L)
        }
        error("AppRule work did not quiesce within ${timeoutMs}ms")
    }

    private fun reapplyAppRuleReceivers() {
        val blocker = synchronized(lock) { blockerRef?.get() }
            ?: error("no current AppRuleBlocker")
        runOnMainThread("reapply_app_rule_receivers") {
            blocker.setupReceivers()
        }
        synchronized(lock) {
            recordEventLocked("app_rule_receivers_reapplied", "")
        }
    }

    private fun terminateProcess(delayMs: Long) {
        val safeDelayMs = delayMs.coerceIn(100L, 2_000L)
        synchronized(lock) {
            recordEventLocked("process_termination_scheduled", "delayMs=$safeDelayMs")
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            safeDelayMs
        )
    }

    private fun snapshot(): String {
        val service = synchronized(lock) { serviceRef?.get() }
        val blocker = synchronized(lock) { blockerRef?.get() }
        val activeRegistrations = blocker?.let(::activeRegistrations).orEmpty()
        val activeReceiverCount = activeRegistrations.size
        val appRuleDestroyed = blocker?.let { readFieldOrNull(it, "destroyed") as? Boolean }
        val appRuleSetupReady = blocker?.let { readFieldOrNull(it, "setupReady") as? Boolean }
        val appRuleLifecycleGeneration = blocker
            ?.let { readFieldOrNull(it, "lifecycleGeneration") as? java.util.concurrent.atomic.AtomicLong }
            ?.get()
        val serviceScopeActive = service
            ?.let { readFieldOrNull(it, "serviceScope") as? CoroutineScope }
            ?.isActive
        val protectionScopeActive = service
            ?.let { readFieldOrNull(it, "protectionScope") as? CoroutineScope }
            ?.isActive
        val workCounts = blocker?.let(::appRuleWorkCounts).orEmpty()

        return synchronized(lock) {
            JSONObject().apply {
                put("processPid", Process.myPid())
                put("processToken", processToken)
                put("processStartedAtElapsedMs", processStartedAtElapsedMs)
                put("serviceGeneration", serviceGeneration)
                put("serviceIdentity", serviceIdentity)
                put("servicePresent", service != null)
                put("appRuleSetupReady", appRuleSetupReady ?: JSONObject.NULL)
                put("appRuleDestroyed", appRuleDestroyed ?: JSONObject.NULL)
                put("appRuleLifecycleGeneration", appRuleLifecycleGeneration ?: JSONObject.NULL)
                put("serviceScopeActive", serviceScopeActive ?: JSONObject.NULL)
                put("protectionScopeActive", protectionScopeActive ?: JSONObject.NULL)
                put("activeReceiverCount", activeReceiverCount)
                put("receiverOwnership", receiverOwnership(blocker, activeRegistrations))
                put("serviceWideReceiverOwnership", serviceWideReceiverOwnership(service, blocker))
                put("runtimePublicationCount", runtimePublicationCount)
                put("notificationPublicationCount", notificationPublicationCount)
                put("evaluationCount", evaluationCount)
                put("allowedEvaluationCount", allowedEvaluationCount)
                put("deniedEvaluationCount", deniedEvaluationCount)
                put("warningFrameworkBoundaryCount", warningFrameworkBoundaryCount)
                put("lastEvaluatedPackage", lastEvaluatedPackage)
                put("refreshContinuationCompletionCount", refreshContinuationCompletionCount)
                put("workCounts", JSONObject(workCounts))
                put("barrierState", runtimeBarrier?.state ?: "DISARMED")
                put(
                    "barrierEnteredAtElapsedMs",
                    runtimeBarrier?.enteredAtElapsedMs ?: JSONObject.NULL
                )
                put(
                    "barrierReleasedAtElapsedMs",
                    runtimeBarrier?.releasedAtElapsedMs ?: JSONObject.NULL
                )
                put("failures", JSONArray().apply {
                    failures.forEach { failure ->
                        put(JSONObject().apply {
                            put("stage", failure.stage)
                            put("type", failure.type)
                            put("message", failure.message)
                            put("elapsedMs", failure.elapsedMs)
                        })
                    }
                })
                put("events", JSONArray().apply {
                    events.forEach { event ->
                        put(JSONObject().apply {
                            put("name", event.name)
                            put("elapsedMs", event.elapsedMs)
                            put("detail", event.detail)
                        })
                    }
                })
            }.toString()
        }
    }

    private fun activeRegistrations(blocker: AppRuleBlocker): List<Any> {
        val lifecycle = readFieldOrNull(blocker, "receiverLifecycle") ?: return emptyList()
        val registered = readFieldOrNull(lifecycle, "registered") as? Collection<*>
        return registered?.filterNotNull().orEmpty()
    }

    private fun appRuleWorkCounts(blocker: AppRuleBlocker): Map<String, Int> {
        val counts = linkedMapOf(
            "refreshes" to atomicIntField(blocker, "inFlightRefreshes"),
            "notifications" to atomicIntField(blocker, "inFlightNotifications"),
            "callbacks" to atomicIntField(blocker, "inFlightCallbacks"),
            "usageResetCompletions" to atomicIntField(blocker, "inFlightUsageResetCompletions"),
            "recheckPlans" to atomicIntField(blocker, "inFlightRecheckPlans")
        )
        val worker = readFieldOrNull(blocker, "decisionWorker")
        counts["workerQueued"] = worker?.let { atomicIntField(it, "queuedWorkCount") } ?: 0
        counts["workerInFlight"] = worker?.let { atomicIntField(it, "inFlightWorkCount") } ?: 0
        return counts
    }

    private fun atomicIntField(instance: Any, name: String): Int =
        (readFieldOrNull(instance, name) as? AtomicInteger)?.get() ?: 0

    private fun receiverOwnership(
        blocker: AppRuleBlocker?,
        activeRegistrations: List<Any>
    ): JSONArray {
        val definitions = listOf(
            ReceiverDefinition("refreshReceiver", listOf(REFRESH_ACTION), "", true),
            ReceiverDefinition(
                "packageReceiver",
                listOf(
                    "android.intent.action.PACKAGE_ADDED",
                    "android.intent.action.PACKAGE_REMOVED",
                    "android.intent.action.PACKAGE_REPLACED"
                ),
                "package",
                true
            ),
            ReceiverDefinition(
                "screenReceiver",
                listOf(
                    "android.intent.action.SCREEN_ON",
                    "android.intent.action.SCREEN_OFF",
                    "android.intent.action.USER_PRESENT"
                ),
                "",
                true
            ),
            ReceiverDefinition("schedulerWakeReceiver", listOf(SCHEDULER_ACTION), "", false),
            ReceiverDefinition(
                "guardianReceiver",
                listOf(GUARDIAN_CLOSED_ACTION, GUARDIAN_OPENED_ACTION),
                "",
                false
            )
        )
        return JSONArray().apply {
            definitions.forEachIndexed { index, definition ->
                val receiver = blocker?.let { readFieldOrNull(it, definition.fieldName) }
                val registration = activeRegistrations.getOrNull(index)
                put(JSONObject().apply {
                    put("name", definition.fieldName)
                    put("identity", receiver?.let(System::identityHashCode) ?: 0)
                    put(
                        "registrationIdentity",
                        registration?.let(System::identityHashCode) ?: 0
                    )
                    put("ownerServiceIdentity", serviceIdentity)
                    put("active", registration != null)
                    put("actions", JSONArray(definition.actions))
                    put("dataScheme", definition.dataScheme)
                    put("exported", definition.exported)
                })
            }
        }
    }

    private fun serviceWideReceiverOwnership(
        service: AppBlockerService?,
        blocker: AppRuleBlocker?
    ): JSONArray {
        val receivers = mutableListOf<Pair<String, Any?>>()
        if (blocker != null) {
            listOf(
                "refreshReceiver",
                "packageReceiver",
                "screenReceiver",
                "schedulerWakeReceiver",
                "guardianReceiver"
            ).forEach { fieldName ->
                receivers += "AppRuleBlocker.$fieldName" to readFieldOrNull(blocker, fieldName)
            }
        }
        if (service != null) {
            listOf(
                Triple("FocusModeBlocker.refreshReceiver", "focusModeBlocker", "refreshReceiver"),
                Triple("ReelBlocker.refreshReceiver", "reelBlocker", "refreshReceiver"),
                Triple("KeywordBlocker.refreshReceiver", "keywordBlocker", "refreshReceiver"),
                Triple("GrayScaleFilter.refreshReceiver", "grayScaleFilter", "refreshReceiver"),
                Triple("UiHider.refreshReceiver", "uiHider", "refreshReceiver"),
                Triple("NodePicker.receiver", "nodePicker", "receiver"),
                Triple("ReelsCountTracker.refreshReceiver", "reelsCountTracker", "refreshReceiver"),
                Triple("MindfulMessage.intentReceiver", "mindfulMessage", "intentReceiver"),
                Triple("AppUsageTracker.screenReceiver", "appUsageTracker", "screenReceiver"),
                Triple("AppUsageTracker.usageResetReceiver", "appUsageTracker", "usageResetReceiver")
            ).forEach { (name, ownerField, receiverField) ->
                val owner = readFieldOrNull(service, ownerField)
                receivers += name to owner?.let { readFieldOrNull(it, receiverField) }
            }
        }
        return JSONArray().apply {
            receivers.forEach { (name, receiver) ->
                put(JSONObject().apply {
                    put("name", name)
                    put("identity", receiver?.let(System::identityHashCode) ?: 0)
                    put("ownerServiceIdentity", serviceIdentity)
                })
            }
        }
    }

    private data class ReceiverDefinition(
        val fieldName: String,
        val actions: List<String>,
        val dataScheme: String,
        val exported: Boolean
    )

    private fun resetObservationLocked(clearEvents: Boolean) {
        runtimeBarrier?.release?.countDown()
        runtimeBarrier = null
        runtimePublicationCount = 0L
        notificationPublicationCount = 0L
        evaluationCount = 0L
        allowedEvaluationCount = 0L
        deniedEvaluationCount = 0L
        warningFrameworkBoundaryCount = 0L
        refreshContinuationCompletionCount = 0L
        lastEvaluatedPackage = ""
        failNextRuntimePublication = false
        failures.clear()
        if (clearEvents) events.clear()
    }

    private fun recordFailure(stage: String, error: Throwable) {
        synchronized(lock) {
            failures += FailureRecord(
                stage = stage,
                type = error.javaClass.name,
                message = error.message.orEmpty(),
                elapsedMs = SystemClock.elapsedRealtime()
            )
            recordEventLocked("failure", "$stage:${error.javaClass.simpleName}")
        }
    }

    private fun recordEventLocked(name: String, detail: String) {
        events += EventRecord(name, SystemClock.elapsedRealtime(), detail)
        if (events.size > MAX_EVENTS) events.removeAt(0)
    }

    private fun runOnMainThread(stage: String, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            } finally {
                completed.countDown()
            }
        }
        if (!completed.await(10L, TimeUnit.SECONDS)) {
            error("$stage timed out on the service main thread")
        }
        failure?.let { throw it }
    }

    private fun readField(instance: Any, name: String): Any =
        requireNotNull(findField(instance, name).get(instance)) { "$name was null" }

    private fun readFieldOrNull(instance: Any, name: String): Any? =
        runCatching { findField(instance, name).get(instance) }.getOrNull()

    private fun findField(instance: Any, name: String): Field {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("field $name not found on ${instance.javaClass.name}")
    }
}
