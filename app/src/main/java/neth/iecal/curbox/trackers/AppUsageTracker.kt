package neth.iecal.curbox.trackers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageDao
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppUsageTrackingPolicy
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ForegroundSessionBoundaryWriter
import neth.iecal.curbox.domain.apprules.ForegroundUsageCheckpoint
import neth.iecal.curbox.domain.apprules.TrackedForegroundSession
import neth.iecal.curbox.domain.apprules.UsageResetRequest
import neth.iecal.curbox.domain.apprules.UsageResetCommandPolicy
import neth.iecal.curbox.domain.apprules.UsageResetSessionRestart
import neth.iecal.curbox.domain.apprules.VisibleApplicationPackages
import neth.iecal.curbox.domain.apprules.VisibleApplicationWindow
import neth.iecal.curbox.domain.apprules.VisibleApplicationSessionReconciler
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.UsageResetManager
import neth.iecal.curbox.utils.ConfigurableUseDayCalculator
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UseDayCalculator
import neth.iecal.curbox.utils.UseDayResetTime
import java.time.Instant
import java.time.ZoneId

/**
 * Records the complete set of visible application packages. Accessibility window enumeration is
 * intentionally kept here as a lightweight operation; node traversal remains in the service's
 * background worker.
 */
class AppUsageTracker {

    companion object {
        private const val HEARTBEAT_MS = 20_000L
        private const val CLEANUP_HEARTBEAT_MS = 60_000L
        private val IGNORED_PACKAGES = setOf(Constants.SYSTEM_UI_PACKAGE_NAME)
    }

    private lateinit var service: BaseBlockingService
    private lateinit var crashLogger: CrashLogger
    private lateinit var dao: AppUsageDao
    private lateinit var sessionRepository: CurrentUseDaySessionRepository
    private lateinit var usageResetRepository: RoomUsageResetRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val usageResetCommands = Channel<UsageResetCommand>(Channel.UNLIMITED)
    private var usageResetJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ownPackage = ""
    @Volatile private var resetTime = UseDayResetTime()
    @Volatile private var useDayGenerationStartedAtMs = 0L
    @Volatile private var useDayCalculator: UseDayCalculator = ConfigurableUseDayCalculator(resetTime = resetTime)
    @Volatile private var trackingDecision = AppUsageTrackingPolicy.decide(
        statisticsTrackingEnabled = true,
        hasActiveTimeBasedRules = false
    )
    private var lastCleanupUseDayId: String? = null
    private var lastCleanupGenerationStartedAtMs = Long.MIN_VALUE
    private var pendingSettingsSnapshot: Settings? = null
    private var destroying = false
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var screenOn = true
    /** All state below is owned by the accessibility service main thread. */
    private val pendingUsageResetPackages = mutableSetOf<String>()
    private var activeUsageResetCommands = 0

    private data class ActiveSession(
        val packageName: String,
        var useDayId: String,
        var sessionId: Long,
        var lastCommittedWallMs: Long,
        var lastCommittedElapsedMs: Long
    )

    private data class UsageResetCommand(
        val request: UsageResetRequest,
        val sessionRestarts: List<UsageResetSessionRestart>
    )

    private data class ShutdownSessionSnapshot(
        val packageName: String,
        val useDayId: String,
        val sessionId: Long,
        val lastCommittedWallMs: Long
    )

    /** Access is confined to the accessibility service thread and its main-handler callbacks. */
    private val activeSessions = LinkedHashMap<String, ActiveSession>()

    private val recordingEnabled: Boolean
        get() = trackingDecision.shouldRecordSessions

    fun setup(service: BaseBlockingService) {
        this.service = service
        crashLogger = CrashLogger(service)
        ownPackage = service.packageName
        val database = AppDatabase.getInstance(service)
        dao = database.appUsageDao()
        sessionRepository = RoomCurrentUseDaySessionRepository(
            database.foregroundSessionDao(),
            database.foregroundLaunchDao(),
            database.appUsageDao(),
            database
        )
        usageResetRepository = RoomUsageResetRepository(database)

        try {
            val initialSettings = runBlocking(Dispatchers.IO) {
                service.dataStoreManager.settings.first()
            }
            resetTime = safeResetTime(initialSettings.useDayResetHour, initialSettings.useDayResetMinute)
            useDayGenerationStartedAtMs = initialSettings.useDayGenerationStartedAtMs
            useDayCalculator = ConfigurableUseDayCalculator(ZoneId.systemDefault(), resetTime)
            trackingDecision = AppUsageTrackingPolicy.decide(
                statisticsTrackingEnabled = initialSettings.isAppUsageTrackingEnabled,
                hasActiveTimeBasedRules = initialSettings.appRuleSnapshot.appRules.any { it.isActive }
            )
            // A session row is written before its first heartbeat. If the process died before a
            // heartbeat, discard that uncommitted tail rather than inventing time on restart.
            val now = System.currentTimeMillis()
            val currentUseDayId = useDayCalculator.idAt(now)
            runBlocking(Dispatchers.IO) {
                sessionRepository.recoverOpenSessions(currentUseDayId)
                sessionRepository.cleanupBeforeUseDay(
                    currentUseDayId,
                    useDayGenerationStartedAtMs
                )
            }
            lastCleanupUseDayId = currentUseDayId
            lastCleanupGenerationStartedAtMs = useDayGenerationStartedAtMs
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }

        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = powerManager.isInteractive
        registerScreenReceiver()
        registerUsageResetReceiver()
        usageResetJob?.cancel()
        usageResetJob = scope.launch {
            for (command in usageResetCommands) {
                try {
                    processUsageReset(command)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // A malformed command must fail only itself; never kill the worker that
                    // serializes later reset requests.
                    logNonFatal(error)
                    mainHandler.post { completeUsageReset(command, null, error) }
                }
            }
        }
        startCleanupHeartbeat()
        settingsJob?.cancel()
        settingsJob = scope.launch {
            try {
                service.dataStoreManager.settings.collect { settings ->
                    mainHandler.post {
                        try {
                            if (activeUsageResetCommands > 0) {
                                // Reset owns the service writer. Keep the latest complete
                                // settings snapshot and apply it when the barrier opens.
                                pendingSettingsSnapshot = settings
                            } else {
                                applySettingsSnapshot(settings)
                            }
                        } catch (error: Exception) {
                            logNonFatal(error)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
    }

    private fun applySettingsSnapshot(settings: Settings) {
        if (destroying) return
        val nextReset = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
        val nextDecision = AppUsageTrackingPolicy.decide(
            statisticsTrackingEnabled = settings.isAppUsageTrackingEnabled,
            hasActiveTimeBasedRules = settings.appRuleSnapshot.appRules.any { it.isActive }
        )
        val resetChanged = nextReset != resetTime
        val policyChanged = AppUsageTrackingPolicy.requiresSessionBoundary(
            trackingDecision,
            nextDecision
        )
        val packagesToResume = if (resetChanged || policyChanged) {
            rotateActiveSessions()
        } else {
            emptyList()
        }
        resetTime = nextReset
        useDayGenerationStartedAtMs = settings.useDayGenerationStartedAtMs
        useDayCalculator = ConfigurableUseDayCalculator(ZoneId.systemDefault(), nextReset)
        trackingDecision = nextDecision
        cleanupCurrentUseDay()
        if (packagesToResume.isNotEmpty() && screenOn && recordingEnabled) {
            packagesToResume.forEach {
                startSession(
                    packageName = it,
                    startedAtWallMs = System.currentTimeMillis(),
                    startedAtElapsedMs = SystemClock.elapsedRealtime(),
                    recordLaunch = false
                )
            }
        }
        if (!recordingEnabled) endAllSessions()
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (destroying || !recordingEnabled || !screenOn || event == null) return
        // A reset command owns the service-process writer until its Room transaction and
        // foreground restart commit.  Deferring visibility reconciliation keeps a heartbeat or
        // accessibility event from racing the reset transaction.
        if (activeUsageResetCommands > 0) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val visiblePackages = queryVisiblePackages(event)
        reconcileVisiblePackages(visiblePackages)
    }

    /**
     * Enqueues the service-owned reset writer.  The command barrier pauses heartbeat/event
     * writes, then the Room transaction cuts the old row, adjusts aggregates, and starts the
     * replacement foreground row and its first launch together.
     */
    private fun onUsageReset(
        packages: Set<String>,
        requestId: String = ""
    ) {
        if (packages.isEmpty()) return
        val normalizedPackages = packages.map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (normalizedPackages.isEmpty()) return

        // The UI timestamp is only a delivery hint.  All packages in one command share the
        // service-main acceptance time, so a delayed cross-process broadcast cannot replay an old
        // interval or give packages in the same reset different boundaries.
        val effectiveAtMs = UsageResetCommandPolicy.acceptedAt(System.currentTimeMillis())
        val generationStartedAtMs = useDayGenerationStartedAtMs
        val useDayId = useDayCalculator.idAt(effectiveAtMs)
        val request = UsageResetRequest(
            useDayId = useDayId,
            generationStartedAtMs = generationStartedAtMs,
            packageNames = normalizedPackages,
            resetAtMs = effectiveAtMs,
            requestId = requestId
        )
        if (UsageResetCommandPolicy.hasPendingOverlap(normalizedPackages, pendingUsageResetPackages)) {
            // Never let a second request reset the package without also recreating its visible
            // row. Reject the whole request so its caller receives a deterministic failure.
            publishUsageResetComplete(request, succeeded = false)
            return
        }
        val restarts = normalizedPackages.mapNotNull { packageName ->
            val session = activeSessions[packageName] ?: return@mapNotNull null
            pendingUsageResetPackages.add(packageName)
            UsageResetSessionRestart(
                useDayId = useDayId,
                packageName = packageName,
                startedAtMs = effectiveAtMs,
                generationStartedAtMs = generationStartedAtMs,
                activeSessionId = session.sessionId,
                persistedThroughMs = session.lastCommittedWallMs.coerceAtMost(effectiveAtMs),
                statisticsTracked = trackingDecision.recordStatistics,
                // Q111: the visible app is a fresh launch after an explicit reset.
                recordLaunch = true
            )
        }

        val command = UsageResetCommand(
            request = request,
            sessionRestarts = restarts
        )
        activeUsageResetCommands++
        if (usageResetCommands.trySend(command).isFailure) {
            activeUsageResetCommands--
            restarts.forEach { pendingUsageResetPackages.remove(it.packageName) }
            publishUsageResetComplete(request, succeeded = false)
        }
    }

    /** Runs only on [scope], never from a BroadcastReceiver or the service main thread. */
    private suspend fun processUsageReset(command: UsageResetCommand) {
        try {
            val result = usageResetRepository.resetAndStartSessions(
                request = command.request,
                restarts = command.sessionRestarts
            )
            mainHandler.post { completeUsageReset(command, result, null) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logNonFatal(error)
            mainHandler.post { completeUsageReset(command, null, error) }
        }
    }

    /** Applies the committed Room result back to the main-thread-owned active session map. */
    private fun completeUsageReset(
        command: UsageResetCommand,
        result: neth.iecal.curbox.domain.apprules.UsageResetResult?,
        failure: Throwable?
    ) {
        command.sessionRestarts.forEach { restart ->
            pendingUsageResetPackages.remove(restart.packageName)
            if (result != null && failure == null) {
                val sessionId = result.restartedSessionIds[restart.packageName] ?: 0L
                activeSessions[restart.packageName] = ActiveSession(
                    packageName = restart.packageName,
                    useDayId = restart.useDayId,
                    sessionId = sessionId,
                    lastCommittedWallMs = restart.startedAtMs,
                    lastCommittedElapsedMs = SystemClock.elapsedRealtime()
                )
            }
        }
        activeUsageResetCommands = (activeUsageResetCommands - 1).coerceAtLeast(0)
        if (activeUsageResetCommands == 0) {
            pendingSettingsSnapshot?.let { settings ->
                pendingSettingsSnapshot = null
                runCatching { applySettingsSnapshot(settings) }
                    .onFailure(::logNonFatal)
            }
        }
        if (result != null && failure == null) {
            publishUsageResetComplete(result.request, succeeded = true)
        } else if (failure != null) {
            publishUsageResetComplete(command.request, succeeded = false)
        }
        if (activeUsageResetCommands == 0) {
            // Reconcile the actual visible set after the barrier opens.  This catches an app
            // transition that occurred while the request was being committed.
            if (recordingEnabled && screenOn) mainHandler.post { resumeVisibleApplications() }
            if (activeSessions.isNotEmpty()) startHeartbeat() else stopHeartbeat()
        }
    }

    private fun publishUsageResetComplete(request: UsageResetRequest, succeeded: Boolean) {
        runCatching {
            service.sendBroadcast(
                Intent(UsageResetManager.ACTION_USAGE_RESET)
                    .setPackage(service.packageName)
                    .putStringArrayListExtra(
                        UsageResetManager.EXTRA_PACKAGES,
                        ArrayList(request.packageNames)
                    )
                    .putExtra(UsageResetManager.EXTRA_RESET_AT_MS, request.resetAtMs)
                    .putExtra(UsageResetManager.EXTRA_REQUEST_ID, request.requestId)
                    .putExtra(UsageResetManager.EXTRA_RESULT_OK, succeeded)
            )
            if (succeeded) {
                service.sendBroadcast(
                    Intent(neth.iecal.curbox.blockers.AppRuleBlocker.INTENT_ACTION_REFRESH_APP_RULES)
                        .setPackage(service.packageName)
                )
            }
        }.onFailure(::logNonFatal)
    }

    /**
     * Returns only application windows. A failed query falls back to the event package; a
     * successful empty query deliberately returns an empty set so System UI and overlays do not
     * keep charging the last application.
     */
    private fun queryVisiblePackages(event: AccessibilityEvent): Set<String> {
        return try {
            val windows = service.windows
            val normalized = windows.map { window ->
                VisibleApplicationWindow(
                    packageName = packageNameForWindow(window),
                    type = window.type,
                    // Accessibility reports application windows in the interactive list. The
                    // package set, rather than focus, determines split-screen visibility.
                    isInteractive = true
                )
            }
            VisibleApplicationPackages.fromWindows(
                windows = normalized,
                ownPackage = ownPackage,
                systemUiPackage = Constants.SYSTEM_UI_PACKAGE_NAME
            )
        } catch (error: Exception) {
            logNonFatal(error)
            fallbackPackage(event)
        }
    }

    private fun fallbackPackage(event: AccessibilityEvent): Set<String> {
        val packageName = event.packageName?.toString()?.trim().orEmpty()
        return if (packageName.isNotEmpty() && packageName != ownPackage &&
            packageName !in IGNORED_PACKAGES
        ) setOf(packageName) else emptySet()
    }

    private fun reconcileVisiblePackages(nextPackages: Set<String>) {
        if (!recordingEnabled) {
            endAllSessions()
            return
        }

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousPackages = activeSessions.keys.toSet()

        // Flush retained sessions before changing the set. This makes the preceding package's
        // contribution available before a newly visible package is evaluated.
        (previousPackages intersect nextPackages).forEach { packageName ->
            commitSession(activeSessions.getValue(packageName), nowWall, nowElapsed)
        }

        val current = activeSessions.mapValues { (_, session) ->
            TrackedForegroundSession(
                packageName = session.packageName,
                sessionId = session.sessionId,
                useDayId = session.useDayId,
                startedAtMs = session.lastCommittedWallMs
            )
        }
        val currentUseDayId = useDayCalculator.idAt(nowWall)
        val resetRestartPackages = current.values
            .filter { it.packageName in nextPackages && it.useDayId != currentUseDayId }
            .mapTo(mutableSetOf()) { it.packageName }
        val boundaryWriter = object : ForegroundSessionBoundaryWriter {
            override suspend fun finish(session: TrackedForegroundSession, endedAtMs: Long) {
                finishForReconciliation(session, endedAtMs)
            }

            override suspend fun start(
                useDayId: String,
                packageName: String,
                startedAtMs: Long
            ): Long = startSession(
                packageName = packageName,
                startedAtWallMs = startedAtMs,
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                recordLaunch = packageName !in resetRestartPackages,
                useDayId = useDayId
            )
        }

        // The reconciler serializes all finishes before starts and keeps the complete visible set.
        val result = runBlocking(Dispatchers.IO) {
            VisibleApplicationSessionReconciler(
                repository = sessionRepository,
                useDayCalculator = useDayCalculator,
                boundaryWriter = boundaryWriter
            ).reconcile(
                current = current,
                visiblePackages = nextPackages,
                nowMs = nowWall
            )
        }
        if (result.activeSessions.isEmpty()) stopHeartbeat()
        cleanupCurrentUseDay(nowWall)
    }

    private fun startSession(
        packageName: String,
        startedAtWallMs: Long,
        startedAtElapsedMs: Long,
        recordLaunch: Boolean = true,
        useDayId: String = useDayCalculator.idAt(startedAtWallMs)
    ): Long {
        activeSessions[packageName]?.let { return it.sessionId }
        val sessionId = try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.startSessionAtGeneration(
                    useDayId,
                    packageName,
                    startedAtWallMs,
                    useDayGenerationStartedAtMs,
                    trackingDecision.recordStatistics
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            0L
        }
        activeSessions[packageName] = ActiveSession(
            packageName = packageName,
            useDayId = useDayId,
            sessionId = sessionId,
            lastCommittedWallMs = startedAtWallMs,
            lastCommittedElapsedMs = startedAtElapsedMs
        )
        if (trackingDecision.recordStatistics && recordLaunch) {
            recordLaunch(packageName, startedAtWallMs)
            try {
                runBlocking(Dispatchers.IO) {
                    sessionRepository.recordLaunch(
                        useDayId = useDayId,
                        packageName = packageName,
                        launchedAtMs = startedAtWallMs,
                        generationStartedAtMs = useDayGenerationStartedAtMs
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
        startHeartbeat()
        return sessionId
    }

    private fun finishForReconciliation(
        tracked: TrackedForegroundSession,
        endedAtWallMs: Long
    ) {
        val session = activeSessions[tracked.packageName]
        if (session == null) {
            if (tracked.sessionId != 0L) {
                try {
                    runBlocking(Dispatchers.IO) {
                        sessionRepository.finishSession(tracked.sessionId, endedAtWallMs)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logNonFatal(error)
                }
            }
            return
        }

        val endWallMs = maxOf(endedAtWallMs, session.lastCommittedWallMs)
        if (session.useDayId != useDayCalculator.idAt(endWallMs)) {
            // The reconciler owns the reset boundary in this path. Commit only the old interval;
            // its subsequent start callback creates exactly one row for the new use day.
            if (!commitCheckpoint(session, session.lastCommittedWallMs, endWallMs)) return
            session.lastCommittedWallMs = endWallMs
            session.lastCommittedElapsedMs = SystemClock.elapsedRealtime()
        } else {
            if (!commitSession(session, endWallMs, SystemClock.elapsedRealtime())) return
        }
        activeSessions.remove(tracked.packageName)
        if (activeSessions.isEmpty()) stopHeartbeat()
    }

    private fun endSession(packageName: String, endedAtWallMs: Long, endedAtElapsedMs: Long) {
        val session = activeSessions[packageName] ?: return
        if (!commitSession(session, endedAtWallMs, endedAtElapsedMs)) return
        if (session.sessionId != 0L) {
            var finished = true
            try {
                runBlocking(Dispatchers.IO) {
                    sessionRepository.finishSession(
                        session.sessionId,
                        maxOf(endedAtWallMs, session.lastCommittedWallMs)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
                finished = false
            }
            if (!finished) return
        }
        activeSessions.remove(packageName)
        if (activeSessions.isEmpty()) stopHeartbeat()
    }

    private fun endAllSessions() {
        if (activeUsageResetCommands > 0 || activeSessions.isEmpty()) return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        activeSessions.keys.toList().forEach { endSession(it, nowWall, nowElapsed) }
        cleanupCurrentUseDay(nowWall)
    }

    /** Close active rows before a reset or policy change, then optionally begin fresh rows. */
    private fun rotateActiveSessions(): List<String> {
        if (activeSessions.isEmpty()) return emptyList()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val packages = activeSessions.keys.toList()
        val ended = ArrayList<String>(packages.size)
        packages.forEach {
            val before = activeSessions[it]
            endSession(it, nowWall, nowElapsed)
            if (before != null && activeSessions[it] == null) ended += it
        }
        return ended
    }

    /**
     * Commits the exact wall-clock interval since the last checkpoint. If a reset boundary lies
     * inside it, the Room session is closed at that boundary and a new row starts there.
     */
    private fun commitSession(session: ActiveSession, nowWallMs: Long, nowElapsedMs: Long): Boolean {
        val endWallMs = maxOf(nowWallMs, session.lastCommittedWallMs)
        var cursorWallMs = session.lastCommittedWallMs
        var cursorElapsedMs = session.lastCommittedElapsedMs

        while (true) {
            val boundary = useDayCalculator.windowFor(session.useDayId).last + 1L
            if (cursorWallMs < boundary && endWallMs >= boundary) {
                if (!commitCheckpoint(session, cursorWallMs, boundary)) return false
                val nextUseDayId = useDayCalculator.idAt(boundary)
                val nextId = startPersistedSession(session.packageName, boundary, nextUseDayId)
                session.useDayId = nextUseDayId
                session.sessionId = nextId
                cursorElapsedMs += (boundary - session.lastCommittedWallMs).coerceAtLeast(0L)
                cursorWallMs = boundary
                session.lastCommittedWallMs = boundary
                session.lastCommittedElapsedMs = cursorElapsedMs
                continue
            }
            break
        }

        if (endWallMs > cursorWallMs) {
            if (!commitCheckpoint(session, cursorWallMs, endWallMs)) return false
        }
        session.lastCommittedWallMs = endWallMs
        session.lastCommittedElapsedMs = maxOf(nowElapsedMs, cursorElapsedMs)
        return true
    }

    private fun startPersistedSession(packageName: String, startedAtMs: Long, useDayId: String): Long =
        try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.startSessionAtGeneration(
                    useDayId,
                    packageName,
                    startedAtMs,
                    useDayGenerationStartedAtMs,
                    trackingDecision.recordStatistics
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            0L
        }

    private fun commitCheckpoint(
        session: ActiveSession,
        startWallMs: Long,
        endWallMs: Long
    ): Boolean {
        if (session.sessionId == 0L || endWallMs < startWallMs) return false
        val usage = if (trackingDecision.recordStatistics) {
            splitIntoHourlySegments(startWallMs, endWallMs).map { segment ->
                ForegroundUsageCheckpoint(
                    date = segment.date,
                    packageName = session.packageName,
                    hour = segment.hour,
                    durationMs = segment.durationMs,
                    lastUsedMs = segment.endWall
                )
            }
        } else {
            emptyList()
        }
        try {
            return runBlocking(Dispatchers.IO) {
                sessionRepository.commitSessionCheckpoint(
                    id = session.sessionId,
                    endedAtMs = endWallMs,
                    usage = usage
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
            return false
        }
    }

    /**
     * Finishes one immutable shutdown snapshot off the service main thread. Each successful
     * checkpoint closes its ledger row and its aggregate in one Room transaction. A failed
     * checkpoint leaves the row at its previous durable boundary for startup recovery to discard;
     * no aggregate-only progress is fabricated.
     */
    private suspend fun finishShutdownSession(
        snapshot: ShutdownSessionSnapshot,
        calculator: UseDayCalculator,
        generationStartedAtMs: Long,
        statisticsTracked: Boolean
    ): Boolean {
        if (snapshot.sessionId == 0L) return false
        val endWallMs = maxOf(System.currentTimeMillis(), snapshot.lastCommittedWallMs)
        var sessionId = snapshot.sessionId
        var useDayId = snapshot.useDayId
        var cursorWallMs = snapshot.lastCommittedWallMs
        var committedAny = false

        while (true) {
            val boundary = calculator.windowFor(useDayId).last + 1L
            if (cursorWallMs < boundary && endWallMs >= boundary) {
                if (!commitShutdownCheckpoint(
                        sessionId,
                        snapshot.packageName,
                        cursorWallMs,
                        boundary,
                        statisticsTracked
                    )
                ) return false
                committedAny = true
                cursorWallMs = boundary
                if (endWallMs <= boundary) break
                useDayId = calculator.idAt(boundary)
                sessionId = sessionRepository.startSessionAtGeneration(
                    useDayId = useDayId,
                    packageName = snapshot.packageName,
                    startedAtMs = boundary,
                    generationStartedAtMs = generationStartedAtMs,
                    statisticsTracked = statisticsTracked
                )
                if (sessionId == 0L) return false
                continue
            }
            break
        }

        if (endWallMs > cursorWallMs || !committedAny) {
            if (!commitShutdownCheckpoint(
                    sessionId,
                    snapshot.packageName,
                    cursorWallMs,
                    endWallMs,
                    statisticsTracked
                )
            ) return false
        }
        return true
    }

    private suspend fun commitShutdownCheckpoint(
        sessionId: Long,
        packageName: String,
        startWallMs: Long,
        endWallMs: Long,
        statisticsTracked: Boolean
    ): Boolean {
        if (endWallMs < startWallMs) return false
        val usage = if (statisticsTracked) {
            splitIntoHourlySegments(startWallMs, endWallMs).map { segment ->
                ForegroundUsageCheckpoint(
                    date = segment.date,
                    packageName = packageName,
                    hour = segment.hour,
                    durationMs = segment.durationMs,
                    lastUsedMs = segment.endWall
                )
            }
        } else {
            emptyList()
        }
        return sessionRepository.commitSessionCheckpoint(
            id = sessionId,
            endedAtMs = endWallMs,
            usage = usage
        )
    }

    private fun recordLaunch(packageName: String, wall: Long) {
        if (!trackingDecision.recordStatistics) return
        // Calendar-keyed rows are derived history consumed by the existing usage UI. The
        // authoritative current-use-day launch event is the session start itself.
        val date = TimeTools.dayKey(Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalDate())
        try {
            runBlocking(Dispatchers.IO) {
                val existing = dao.get(date, packageName)
                dao.upsert(
                    existing?.copy(
                        launchCount = existing.launchCount + 1,
                        lastUsed = maxOf(existing.lastUsed, wall)
                    ) ?: AppUsageEntity(
                        date = date,
                        packageName = packageName,
                        launchCount = 1,
                        lastUsed = wall
                    )
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private data class Segment(
        val date: String,
        val hour: Int,
        val durationMs: Long,
        val endWall: Long
    )

    private fun splitIntoHourlySegments(startWall: Long, endWall: Long): List<Segment> {
        if (endWall <= startWall) return emptyList()
        val zone = ZoneId.systemDefault()
        val segments = ArrayList<Segment>()
        var cursor = startWall
        while (cursor < endWall) {
            val zdt = Instant.ofEpochMilli(cursor).atZone(zone)
            val nextHour = zdt.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                .toInstant().toEpochMilli()
            val segmentEnd = minOf(endWall, nextHour)
            segments += Segment(
                date = TimeTools.dayKey(zdt.toLocalDate()),
                hour = zdt.hour,
                durationMs = segmentEnd - cursor,
                endWall = segmentEnd
            )
            cursor = segmentEnd
        }
        return segments
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (destroying || !recordingEnabled || activeSessions.isEmpty() || activeUsageResetCommands > 0) return
            try {
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                activeSessions.values.toList().forEach { commitSession(it, nowWall, nowElapsed) }
                cleanupCurrentUseDay(nowWall)
            } catch (error: Exception) {
                logNonFatal(error)
            }
            if (recordingEnabled && activeSessions.isNotEmpty()) {
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
    }

    private val cleanupHeartbeat = object : Runnable {
        override fun run() {
            try {
                cleanupCurrentUseDay()
            } catch (error: Exception) {
                logNonFatal(error)
            }
            mainHandler.postDelayed(this, CLEANUP_HEARTBEAT_MS)
        }
    }

    private fun startCleanupHeartbeat() {
        mainHandler.removeCallbacks(cleanupHeartbeat)
        mainHandler.postDelayed(cleanupHeartbeat, CLEANUP_HEARTBEAT_MS)
    }

    private fun stopCleanupHeartbeat() {
        mainHandler.removeCallbacks(cleanupHeartbeat)
    }

    private fun cleanupCurrentUseDay(nowMs: Long = System.currentTimeMillis()) {
        if (activeUsageResetCommands > 0) return
        val currentUseDayId = useDayCalculator.idAt(nowMs)
        val generation = useDayGenerationStartedAtMs
        if (currentUseDayId == lastCleanupUseDayId &&
            generation == lastCleanupGenerationStartedAtMs
        ) return
        try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.cleanupBeforeUseDay(currentUseDayId, generation)
            }
            lastCleanupUseDayId = currentUseDayId
            lastCleanupGenerationStartedAtMs = generation
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    try {
                        if (activeUsageResetCommands > 0) return
                        endAllSessions()
                    } catch (error: Exception) {
                        logNonFatal(error)
                    }
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    mainHandler.postDelayed({ resumeVisibleApplications() }, 300L)
                }
            }
        }
    }

    private fun resumeVisibleApplications() {
        if (destroying || !recordingEnabled || !screenOn || activeUsageResetCommands > 0) return
        try {
            val windows = service.windows.map { window ->
                VisibleApplicationWindow(packageNameForWindow(window), window.type)
            }
            reconcileVisiblePackages(
                VisibleApplicationPackages.fromWindows(
                    windows,
                    ownPackage,
                    Constants.SYSTEM_UI_PACKAGE_NAME
                )
            )
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private fun packageNameForWindow(
        window: android.view.accessibility.AccessibilityWindowInfo
    ): String {
        val root = window.root ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        service.registerReceiver(screenReceiver, filter)
    }

    private fun registerUsageResetReceiver() {
        ContextCompat.registerReceiver(
            service,
            usageResetReceiver,
            IntentFilter(UsageResetManager.ACTION_USAGE_RESET_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val usageResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsageResetManager.ACTION_USAGE_RESET_REQUEST) return
            try {
                val packages = intent.getStringArrayListExtra(UsageResetManager.EXTRA_PACKAGES)
                    ?.toSet()
                    .orEmpty()
                val requestId = intent.getStringExtra(UsageResetManager.EXTRA_REQUEST_ID).orEmpty()
                mainHandler.post {
                    try {
                        onUsageReset(
                            packages = packages,
                            requestId = requestId
                        )
                    } catch (error: Throwable) {
                        // Keep failures from the serialized state handoff inside the receiver
                        // safety boundary as well.
                        logNonFatal(error)
                    }
                }
            } catch (error: Throwable) {
                // Broadcast callbacks have no caller that can handle a cancellation or a bad
                // extra.  Keep every failure inside the service safety boundary.
                logNonFatal(error)
            }
        }
    }

    fun onDestroy() {
        destroying = true
        val resetWasActive = activeUsageResetCommands > 0
        val shutdownSnapshots = if (!resetWasActive) {
            activeSessions.values.map {
                ShutdownSessionSnapshot(
                    packageName = it.packageName,
                    useDayId = it.useDayId,
                    sessionId = it.sessionId,
                    lastCommittedWallMs = it.lastCommittedWallMs
                )
            }
        } else {
            emptyList()
        }
        val shutdownCalculator = useDayCalculator
        val shutdownGeneration = useDayGenerationStartedAtMs
        val shutdownStatisticsTracked = trackingDecision.recordStatistics
        stopHeartbeat()
        stopCleanupHeartbeat()
        usageResetCommands.close()
        val resetWorkerStopped = try {
            // Cancel and join before touching the session ledger. A running reset either rolls
            // back its Room transaction or has fully committed, so no writer can race shutdown.
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(2_000L) {
                    usageResetJob?.cancelAndJoin()
                    settingsJob?.cancelAndJoin()
                    true
                } ?: false
            }
        } catch (error: Throwable) {
            logNonFatal(error)
            false
        }
        // Ensure a timed-out worker cannot keep writing after the service has begun shutting down.
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        activeUsageResetCommands = 0
        pendingUsageResetPackages.clear()
        pendingSettingsSnapshot = null
        if (resetWasActive && resetWorkerStopped && ::sessionRepository.isInitialized) {
            // Only discard open reset tails after the worker has definitely stopped. If the
            // bounded join timed out, leave the ledger for the next process to recover so a
            // still-running transaction cannot race this cleanup.
            try {
                val useDayIds = (activeSessions.values.map { it.useDayId } +
                    useDayCalculator.idAt(System.currentTimeMillis())).toSet()
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        useDayIds.forEach { useDayId ->
                            sessionRepository.recoverOpenSessions(useDayId)
                        }
                    }
                }
            } catch (error: Throwable) {
                logNonFatal(error)
            }
        } else if (!resetWasActive && shutdownSnapshots.isNotEmpty()) {
            // Finish the immutable snapshots before returning from onDestroy. The bounded join
            // prevents a detached writer from outliving the service while preserving the last
            // successful ledger checkpoint when Room or cancellation prevents a close.
            try {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        shutdownSnapshots.forEach { snapshot ->
                            try {
                                finishShutdownSession(
                                    snapshot = snapshot,
                                    calculator = shutdownCalculator,
                                    generationStartedAtMs = shutdownGeneration,
                                    statisticsTracked = shutdownStatisticsTracked
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                logNonFatal(error)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                logNonFatal(error)
            }
        }
        try {
            service.unregisterReceiver(screenReceiver)
        } catch (error: Exception) {
            logNonFatal(error)
        }
        try {
            service.unregisterReceiver(usageResetReceiver)
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private fun safeResetTime(hour: Int, minute: Int): UseDayResetTime =
        runCatching { UseDayResetTime(hour, minute) }.getOrDefault(UseDayResetTime())

    private fun logNonFatal(error: Throwable) {
        if (::crashLogger.isInitialized) {
            runCatching {
                crashLogger.logNonFatalError(
                    if (error is Exception) error else Exception(error)
                )
            }
        }
    }
}
