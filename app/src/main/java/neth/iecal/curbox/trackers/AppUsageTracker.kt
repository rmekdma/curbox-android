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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageDao
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.AppUsageTrackingPolicy
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.ForegroundSessionBoundaryWriter
import neth.iecal.curbox.domain.apprules.TrackedForegroundSession
import neth.iecal.curbox.domain.apprules.VisibleApplicationPackages
import neth.iecal.curbox.domain.apprules.VisibleApplicationWindow
import neth.iecal.curbox.domain.apprules.VisibleApplicationSessionReconciler
import neth.iecal.curbox.services.BaseBlockingService
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var screenOn = true

    private data class ActiveSession(
        val packageName: String,
        var useDayId: String,
        var sessionId: Long,
        var lastCommittedWallMs: Long,
        var lastCommittedElapsedMs: Long
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
            database.foregroundLaunchDao()
        )

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
        startCleanupHeartbeat()
        settingsJob?.cancel()
        settingsJob = scope.launch {
            try {
                service.dataStoreManager.settings.collect { settings ->
                    val nextReset = safeResetTime(settings.useDayResetHour, settings.useDayResetMinute)
                    val nextDecision = AppUsageTrackingPolicy.decide(
                        statisticsTrackingEnabled = settings.isAppUsageTrackingEnabled,
                        hasActiveTimeBasedRules = settings.appRuleSnapshot.appRules.any { it.isActive }
                    )
                    mainHandler.post {
                        try {
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

    fun onEvent(event: AccessibilityEvent?) {
        if (!recordingEnabled || !screenOn || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val visiblePackages = queryVisiblePackages(event)
        reconcileVisiblePackages(visiblePackages)
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
                runBlocking(Dispatchers.IO) {
                    sessionRepository.finishSession(tracked.sessionId, endedAtWallMs)
                }
            }
            return
        }

        val endWallMs = maxOf(endedAtWallMs, session.lastCommittedWallMs)
        if (session.useDayId != useDayCalculator.idAt(endWallMs)) {
            // The reconciler owns the reset boundary in this path. Flush only the old interval;
            // its subsequent start callback creates exactly one row for the new use day.
            persistInterval(session.packageName, session.lastCommittedWallMs, endWallMs)
            persistSessionEnd(session.sessionId, endWallMs)
            session.lastCommittedWallMs = endWallMs
            session.lastCommittedElapsedMs = SystemClock.elapsedRealtime()
        } else {
            commitSession(session, endWallMs, SystemClock.elapsedRealtime())
        }
        activeSessions.remove(tracked.packageName)
        if (activeSessions.isEmpty()) stopHeartbeat()
    }

    private fun endSession(packageName: String, endedAtWallMs: Long, endedAtElapsedMs: Long) {
        val session = activeSessions[packageName] ?: return
        commitSession(session, endedAtWallMs, endedAtElapsedMs)
        if (session.sessionId != 0L) {
            try {
                runBlocking(Dispatchers.IO) {
                    sessionRepository.finishSession(session.sessionId, endedAtWallMs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logNonFatal(error)
            }
        }
        activeSessions.remove(packageName)
        if (activeSessions.isEmpty()) stopHeartbeat()
    }

    private fun endAllSessions() {
        if (activeSessions.isEmpty()) return
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
        packages.forEach { endSession(it, nowWall, nowElapsed) }
        return packages
    }

    /**
     * Commits the exact wall-clock interval since the last checkpoint. If a reset boundary lies
     * inside it, the Room session is closed at that boundary and a new row starts there.
     */
    private fun commitSession(session: ActiveSession, nowWallMs: Long, nowElapsedMs: Long) {
        val endWallMs = maxOf(nowWallMs, session.lastCommittedWallMs)
        var cursorWallMs = session.lastCommittedWallMs
        var cursorElapsedMs = session.lastCommittedElapsedMs

        while (true) {
            val boundary = useDayCalculator.windowFor(session.useDayId).last + 1L
            if (cursorWallMs < boundary && endWallMs >= boundary) {
                persistInterval(session.packageName, cursorWallMs, boundary)
                persistSessionEnd(session.sessionId, boundary)
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
            persistInterval(session.packageName, cursorWallMs, endWallMs)
            persistSessionEnd(session.sessionId, endWallMs)
        }
        session.lastCommittedWallMs = endWallMs
        session.lastCommittedElapsedMs = maxOf(nowElapsedMs, cursorElapsedMs)
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

    private fun persistSessionEnd(sessionId: Long, endedAtMs: Long) {
        if (sessionId == 0L) return
        try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.updateSessionEnd(sessionId, endedAtMs)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private fun persistInterval(packageName: String, startWallMs: Long, endWallMs: Long) {
        if (endWallMs <= startWallMs || !trackingDecision.recordStatistics) return
        try {
            runBlocking(Dispatchers.IO) {
                splitIntoHourlySegments(startWallMs, endWallMs).forEach { segment ->
                    addUsage(
                        date = segment.date,
                        packageName = packageName,
                        hour = segment.hour,
                        durationMs = segment.durationMs,
                        wall = segment.endWall
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private suspend fun addUsage(
        date: String,
        packageName: String,
        hour: Int,
        durationMs: Long,
        wall: Long
    ) {
        if (durationMs <= 0L) return
        val existing = dao.get(date, packageName)
        val hourly = parseHourly(existing?.hourlyUsage)
        hourly[hour] += durationMs
        dao.upsert(
            AppUsageEntity(
                date = date,
                packageName = packageName,
                totalTime = (existing?.totalTime ?: 0L) + durationMs,
                hourlyUsage = serializeHourly(hourly),
                launchCount = existing?.launchCount ?: 0,
                lastUsed = maxOf(existing?.lastUsed ?: 0L, wall)
            )
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
            if (!recordingEnabled || activeSessions.isEmpty()) return
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
        if (!recordingEnabled || !screenOn) return
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

    fun onDestroy() {
        stopHeartbeat()
        stopCleanupHeartbeat()
        try {
            endAllSessions()
        } catch (error: Exception) {
            logNonFatal(error)
        }
        settingsJob?.cancel()
        scope.cancel()
        try {
            service.unregisterReceiver(screenReceiver)
        } catch (error: Exception) {
            logNonFatal(error)
        }
    }

    private fun parseHourly(serialized: String?): LongArray {
        val result = LongArray(24)
        if (serialized.isNullOrEmpty()) return result
        val parts = serialized.split(',')
        for (i in 0 until minOf(24, parts.size)) result[i] = parts[i].toLongOrNull() ?: 0L
        return result
    }

    private fun serializeHourly(hourly: LongArray): String = hourly.joinToString(",")

    private fun safeResetTime(hour: Int, minute: Int): UseDayResetTime =
        runCatching { UseDayResetTime(hour, minute) }.getOrDefault(UseDayResetTime())

    private fun logNonFatal(error: Exception) {
        if (::crashLogger.isInitialized) crashLogger.logNonFatalError(error)
    }
}
