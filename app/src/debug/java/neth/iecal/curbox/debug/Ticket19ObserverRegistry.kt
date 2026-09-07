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
                put("runtimePublicationCount", runtimePublicationCount)
                put("notificationPublicationCount", notificationPublicationCount)
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
