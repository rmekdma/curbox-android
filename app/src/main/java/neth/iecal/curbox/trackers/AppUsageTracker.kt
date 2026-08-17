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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageDao
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.data.db.RoomCurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UseDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AppUsageTracker {

    companion object {
        private const val HEARTBEAT_MS = 20_000L
        private val IGNORED_PACKAGES = setOf("com.android.systemui")
    }

    private lateinit var service: BaseBlockingService
    private lateinit var dao: AppUsageDao
    private lateinit var sessionRepository: CurrentUseDaySessionRepository

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ownPackage = ""
    private var currentPackage: String? = null
    private var sessionStartElapsed = 0L
    private var sessionStartWall = 0L
    private var lastCommitElapsed = 0L
    private var currentSessionId = 0L
    private var currentUseDayId = ""
    private var screenOn = true
    @Volatile private var trackingEnabled = true
    @Volatile private var enforcementLedgerRequired = false

    private val recordingEnabled: Boolean
        get() = trackingEnabled || enforcementLedgerRequired

    fun setup(service: BaseBlockingService) {
        this.service = service
        this.ownPackage = service.packageName
        val database = AppDatabase.getInstance(service)
        this.dao = database.appUsageDao()
        this.sessionRepository = RoomCurrentUseDaySessionRepository(database.foregroundSessionDao())
        runCatching {
            val now = System.currentTimeMillis()
            runBlocking(Dispatchers.IO) {
                sessionRepository.finishOpenSessions(UseDay.idAt(now), now)
            }
        }
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = powerManager.isInteractive
        registerScreenReceiver()
        scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val enabled = settings.isAppUsageTrackingEnabled
                trackingEnabled = enabled
                enforcementLedgerRequired = settings.appRuleSnapshot.appRules.any { it.isActive }
                if (!recordingEnabled) mainHandler.post { discardCurrentSession() }
            }
        }
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (!recordingEnabled) return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!screenOn) return

        val activePackage = try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
        val foreground = activePackage ?: event.packageName?.toString() ?: return

        if (foreground.isEmpty()) return
        if (foreground == ownPackage || foreground in IGNORED_PACKAGES) {
            // Curbox and System UI are not billable apps. They still end the one foreground
            // session tracked by this first slice so time is not charged while either surface
            // is on top of the previous app.
            endCurrentSession()
            return
        }
        if (foreground == currentPackage) return

        switchTo(foreground)
    }

    private fun switchTo(packageName: String) {
        if (!recordingEnabled) return
        endCurrentSession()

        val nowElapsed = SystemClock.elapsedRealtime()
        currentPackage = packageName
        sessionStartElapsed = nowElapsed
        sessionStartWall = System.currentTimeMillis()
        lastCommitElapsed = nowElapsed
        currentUseDayId = UseDay.idAt(sessionStartWall)
        currentSessionId = try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.startSession(currentUseDayId, packageName, sessionStartWall)
            }
        } catch (_: Exception) {
            0L
        }

        if (trackingEnabled) recordLaunch(packageName, sessionStartWall)
        startHeartbeat()
    }

    private fun endCurrentSession() {
        if (currentPackage == null) return
        commit(SystemClock.elapsedRealtime())
        val sessionId = currentSessionId
        val endedAt = System.currentTimeMillis()
        if (sessionId != 0L) {
            try {
                runBlocking(Dispatchers.IO) {
                    sessionRepository.finishSession(sessionId, endedAt)
                }
            } catch (_: Exception) {
            }
        }
        currentPackage = null
        currentSessionId = 0L
        currentUseDayId = ""
        stopHeartbeat()
    }

    private fun discardCurrentSession() {
        endCurrentSession()
    }

    private fun commit(nowElapsed: Long) {
        val packageName = currentPackage ?: return
        if (nowElapsed <= lastCommitElapsed) return

        val startWall = sessionStartWall + (lastCommitElapsed - sessionStartElapsed)
        val endWall = sessionStartWall + (nowElapsed - sessionStartElapsed)
        val boundary = UseDay.windowFor(currentUseDayId).last + 1
        // Rotate exactly at the reset instant as well. Without the equality case, a foreground
        // app that remains open across 04:00 would keep its old use-day id until the next
        // heartbeat or window transition.
        if (endWall > boundary || (endWall == boundary && startWall < boundary)) {
            persistCommit(packageName, startWall, boundary)
            persistSessionEnd(boundary)
            val newUseDayId = UseDay.idAt(boundary)
            currentUseDayId = newUseDayId
            sessionStartWall = boundary
            sessionStartElapsed += boundary - startWall
            lastCommitElapsed = sessionStartElapsed
            currentSessionId = startSession(packageName, boundary, newUseDayId)
            commit(nowElapsed)
            return
        }

        persistCommit(packageName, startWall, endWall)
        persistSessionEnd(endWall)
        lastCommitElapsed = nowElapsed
    }

    private fun persistCommit(packageName: String, startWall: Long, endWall: Long) {
        val segments = splitIntoHourlySegments(startWall, endWall)
        if (!trackingEnabled) return
        scope.launch {
            segments.forEach { addUsage(it.date, packageName, it.hour, it.durationMs, it.endWall) }
        }
    }

    private fun persistSessionEnd(endWall: Long) {
        if (currentSessionId == 0L) return
        try {
            runBlocking(Dispatchers.IO) {
                sessionRepository.updateSessionEnd(currentSessionId, endWall)
            }
        } catch (_: Exception) {
        }
    }

    private fun startSession(packageName: String, startedAt: Long, useDayId: String): Long = try {
        runBlocking(Dispatchers.IO) {
            sessionRepository.startSession(useDayId, packageName, startedAt)
        }
    } catch (_: Exception) {
        0L
    }

    private fun recordLaunch(packageName: String, wall: Long) {
        val date = TimeTools.dayKey(LocalDate.now())
        scope.launch {
            if (!trackingEnabled) return@launch
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
    }

    private suspend fun addUsage(date: String, packageName: String, hour: Int, durationMs: Long, wall: Long) {
        if (durationMs <= 0 || !trackingEnabled) return
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

    private data class Segment(val date: String, val hour: Int, val durationMs: Long, val endWall: Long)

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
            segments.add(
                Segment(
                    date = TimeTools.dayKey(zdt.toLocalDate()),
                    hour = zdt.hour,
                    durationMs = segmentEnd - cursor,
                    endWall = segmentEnd
                )
            )
            cursor = segmentEnd
        }
        return segments
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!recordingEnabled) return
            commit(SystemClock.elapsedRealtime())
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun startHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeat)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    endCurrentSession()
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    mainHandler.postDelayed({ resumeForegroundApp() }, 300)
                }
            }
        }
    }

    private fun resumeForegroundApp() {
        if (!recordingEnabled || !screenOn || currentPackage != null) return
        val active = try {
            service.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        } ?: return
        if (active.isEmpty() || active == ownPackage || active in IGNORED_PACKAGES) return
        switchTo(active)
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
        try {
            endCurrentSession()
        } catch (_: Exception) {
        }
        try {
            service.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
    }

    private fun parseHourly(serialized: String?): LongArray {
        val result = LongArray(24)
        if (serialized.isNullOrEmpty()) return result
        val parts = serialized.split(',')
        for (i in 0 until minOf(24, parts.size)) {
            result[i] = parts[i].toLongOrNull() ?: 0L
        }
        return result
    }

    private fun serializeHourly(hourly: LongArray): String = hourly.joinToString(",")
}
