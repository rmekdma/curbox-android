package neth.iecal.curbox.domain.apprules

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Production adapter from Accessibility framework state to the Android-free observation seam.
 * It reports raw read failures and leaves all policy interpretation to [ForegroundEvidenceModule].
 */
class AndroidForegroundObservationSource private constructor(
    private val factProvider: (ObservationTrigger) -> ForegroundFacts,
    private val errorReporter: (Throwable) -> Unit
) : ForegroundObservationSource {
    constructor(
        service: AccessibilityService,
        wallClockMs: () -> Long = { System.currentTimeMillis() },
        elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
        displayStateProvider: (() -> DisplayState)? = null,
        onNonFatalError: (Throwable) -> Unit = {}
    ) : this(
        factProvider = { trigger ->
            captureAndroidFacts(
                service = service,
                trigger = trigger,
                wallClockMs = wallClockMs,
                elapsedRealtimeMs = elapsedRealtimeMs,
                displayStateProvider = displayStateProvider,
                onNonFatalError = onNonFatalError
            )
        },
        errorReporter = onNonFatalError
    )

    override fun capture(trigger: ObservationTrigger): ForegroundFacts = try {
        factProvider(trigger).normalized()
    } catch (error: Throwable) {
        report(error)
        ForegroundFacts(
            capturedAtWallMs = trigger.requestedAtWallMs,
            capturedAtElapsedMs = trigger.requestedAtElapsedMs,
            signal = SignalFact(
                kind = trigger.kind,
                eventPackage = trigger.eventPackage,
                eventWallMs = trigger.eventPackage?.let { trigger.requestedAtWallMs },
                eventElapsedMs = trigger.eventPackage?.let { trigger.requestedAtElapsedMs }
            ),
            activeRoot = ActiveRootFact(readState = ForegroundReadState.FAILED),
            applicationWindows = ApplicationWindowsFact(
                readState = ForegroundReadState.FAILED
            ),
            displayState = DisplayState.UNLOCKED
        )
    }

    /**
     * Captures a callback event without handing a framework object across the value seam. The
     * incoming callback event remains owned by its host; the adapter always recycles its copy.
     */
    internal fun captureEvent(
        event: AccessibilityEvent?,
        trigger: ObservationTrigger
    ): ForegroundFacts {
        var eventCopy: AccessibilityEvent? = null
        return try {
            eventCopy = event?.let { AccessibilityEvent.obtain(it) }
            val packageName = eventCopy?.packageName
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val eventTime = eventCopy?.eventTime?.takeIf { it >= 0L }
            val effectiveTrigger = if (packageName == null) {
                trigger
            } else {
                trigger.copy(eventPackage = packageName)
            }
            capture(effectiveTrigger).let { facts ->
                if (effectiveTrigger.kind == ObservationKind.REAL_EVENT &&
                    effectiveTrigger.eventPackage != null &&
                    eventTime != null
                ) {
                    facts.copy(
                        signal = facts.signal.copy(
                            eventPackage = effectiveTrigger.eventPackage,
                            eventWallMs = facts.capturedAtWallMs,
                            eventElapsedMs = eventTime
                        )
                    ).normalized()
                } else {
                    facts
                }
            }
        } catch (error: Throwable) {
            report(error)
            capture(trigger)
        } finally {
            eventCopy?.let { copy ->
                try {
                    copy.recycle()
                } catch (error: Throwable) {
                    report(error)
                }
            }
        }
    }

    private fun report(error: Throwable) {
        runCatching { errorReporter(error) }
    }
}

private fun captureAndroidFacts(
    service: AccessibilityService,
    trigger: ObservationTrigger,
    wallClockMs: () -> Long,
    elapsedRealtimeMs: () -> Long,
    displayStateProvider: (() -> DisplayState)?,
    onNonFatalError: (Throwable) -> Unit
): ForegroundFacts {
    val capturedAtWallMs = safeClockRead(
        read = wallClockMs,
        fallback = trigger.requestedAtWallMs,
        onNonFatalError = onNonFatalError
    )
    val capturedAtElapsedMs = safeClockRead(
        read = elapsedRealtimeMs,
        fallback = trigger.requestedAtElapsedMs,
        onNonFatalError = onNonFatalError
    )
    val signal = SignalFact(
        kind = trigger.kind,
        eventPackage = trigger.eventPackage,
        eventWallMs = trigger.eventPackage?.let { capturedAtWallMs },
        eventElapsedMs = trigger.eventPackage?.let { capturedAtElapsedMs }
    )
    return ForegroundFacts(
        capturedAtWallMs = capturedAtWallMs,
        capturedAtElapsedMs = capturedAtElapsedMs,
        signal = signal,
        activeRoot = readActiveRoot(service, onNonFatalError),
        applicationWindows = readApplicationWindows(service, onNonFatalError),
        displayState = readDisplayState(service, displayStateProvider, onNonFatalError)
    ).normalized()
}

private fun safeClockRead(
    read: () -> Long,
    fallback: Long,
    onNonFatalError: (Throwable) -> Unit
): Long = try {
    read().coerceAtLeast(0L)
} catch (error: Throwable) {
    reportNonFatal(onNonFatalError, error)
    fallback.coerceAtLeast(0L)
}

private fun readActiveRoot(
    service: AccessibilityService,
    onNonFatalError: (Throwable) -> Unit
): ActiveRootFact {
    val root = try {
        service.rootInActiveWindow
    } catch (error: Throwable) {
        reportNonFatal(onNonFatalError, error)
        return ActiveRootFact(readState = ForegroundReadState.FAILED)
    } ?: return ActiveRootFact()

    var readFailed = false
    val packageName = try {
        root.packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)
    } catch (error: Throwable) {
        readFailed = true
        reportNonFatal(onNonFatalError, error)
        null
    } finally {
        try {
            root.recycle()
        } catch (error: Throwable) {
            readFailed = true
            reportNonFatal(onNonFatalError, error)
        }
    }
    return ActiveRootFact(
        packageName = packageName,
        readState = if (readFailed) {
            ForegroundReadState.FAILED
        } else if (packageName == null) {
            ForegroundReadState.EMPTY
        } else {
            ForegroundReadState.AVAILABLE
        }
    )
}

private fun readApplicationWindows(
    service: AccessibilityService,
    onNonFatalError: (Throwable) -> Unit
): ApplicationWindowsFact {
    val windows = try {
        service.windows
    } catch (error: Throwable) {
        reportNonFatal(onNonFatalError, error)
        return ApplicationWindowsFact(readState = ForegroundReadState.FAILED)
    }
    if (windows.isNullOrEmpty()) return ApplicationWindowsFact()

    val packages = linkedSetOf<String>()
    var unknownSlotCount = 0
    var applicationWindowCount = 0
    var readFailed = false
    try {
        for (window in windows) {
            val isApplicationWindow = try {
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            } catch (error: Throwable) {
                readFailed = true
                reportNonFatal(onNonFatalError, error)
                false
            }
            if (!isApplicationWindow) continue
            applicationWindowCount++
            val root = try {
                window.root
            } catch (error: Throwable) {
                readFailed = true
                reportNonFatal(onNonFatalError, error)
                null
            }
            if (root == null) {
                unknownSlotCount++
                continue
            }
            try {
                val packageName = root.packageName
                    ?.toString()
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                if (packageName == null) {
                    unknownSlotCount++
                } else {
                    packages += packageName
                }
            } catch (error: Throwable) {
                readFailed = true
                unknownSlotCount++
                reportNonFatal(onNonFatalError, error)
            } finally {
                try {
                    root.recycle()
                } catch (error: Throwable) {
                    readFailed = true
                    reportNonFatal(onNonFatalError, error)
                }
            }
        }
    } catch (error: Throwable) {
        readFailed = true
        reportNonFatal(onNonFatalError, error)
    }

    return ApplicationWindowsFact(
        packages = packages,
        unknownSlotCount = unknownSlotCount,
        readState = when {
            readFailed -> ForegroundReadState.FAILED
            applicationWindowCount == 0 -> ForegroundReadState.EMPTY
            else -> ForegroundReadState.AVAILABLE
        },
        freshness = ApplicationWindowsFreshness.FRESH
    )
}

private fun readDisplayState(
    service: AccessibilityService,
    displayStateProvider: (() -> DisplayState)?,
    onNonFatalError: (Throwable) -> Unit
): DisplayState {
    displayStateProvider?.let { provider ->
        return try {
            provider()
        } catch (error: Throwable) {
            reportNonFatal(onNonFatalError, error)
            DisplayState.UNLOCKED
        }
    }
    return try {
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            DisplayState.SCREEN_OFF
        } else {
            val keyguardManager =
                service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguardManager?.isKeyguardLocked == true) {
                DisplayState.KEYGUARD
            } else {
                DisplayState.UNLOCKED
            }
        }
    } catch (error: Throwable) {
        reportNonFatal(onNonFatalError, error)
        DisplayState.UNLOCKED
    }
}

private fun reportNonFatal(reporter: (Throwable) -> Unit, error: Throwable) {
    runCatching { reporter(error) }
}
