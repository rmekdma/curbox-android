package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.AutoDnd
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.anti_stimulants.MindfulMessage
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppRuleBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.ReelScriptRunner
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.trackers.ReelUsageTracker
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.trackers.WebsiteUsageTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val focusModeBlocker = FocusModeBlocker()
    private val autoDnd = AutoDnd()
    private val reelBlocker = ReelBlocker()
    private val reelScriptRunner = ReelScriptRunner()
    private var keywordBlocker = KeywordBlocker()
    private val uiHider = UiHider()
    private val nodePicker = NodePicker()
    private val antiUninstallBlocker = AntiUninstallBlocker()

    private var grayScaleFilter = GrayScaleFilter()

    // Usage tracking, which used to live in its own accessibility service, now
    // runs here so the user only has to grant one service.
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val reelUsageTracker = ReelUsageTracker()
    private val mindfulMessage = MindfulMessage()
    private val websiteUsageTracker = WebsiteUsageTracker()
    private val appUsageTracker = AppUsageTracker()
    private val appRuleBlocker = AppRuleBlocker()
    private var appUsageTrackerReady = false
    private var appRuleBlockerReady = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }
    private val websiteObservationChannel = Channel<WebsiteObservation?>(Channel.CONFLATED)

    private lateinit var crashLogger: CrashLogger

    fun syncDndState() {
        val autoDndActive = autoDnd.isDndRequested()
        val manualFocusDndActive = focusModeBlocker.isDndRequested()
        neth.iecal.curbox.utils.DndHelper.applyDndState(this, autoDndActive || manualFocusDndActive)
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (e: Exception) {
            Log.e("Shizuku", "Failed to bind Shizuku in non-provider process", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        try {
            if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
                antiUninstallBlocker.doAntiUninstallCheck(event)
            }
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
        } catch (t: Throwable) {
            Log.e("error", t.message ?: "Unknown error")
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            // AppUsageTracker.onEvent is synchronous at this seam: it checkpoints the previous
            // foreground sessions before the rule evaluator reads them. Keep these calls ordered
            // and independently contained so a storage failure cannot kill later events.
            // Flush the previous session before evaluating the newly foregrounded package.
            if (appUsageTrackerReady) appUsageTracker.onEvent(event)
        } catch (t: Throwable) {
            Log.e("Usage Tracking error", t.toString())
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            // This must remain after the usage flush above. Contributor earning and target
            // consumption both use the current-use-day raw session ledger.
            if (appRuleBlockerReady) appRuleBlocker.doAppRuleCheck(event)
        } catch (t: Throwable) {
            Log.e("App rule check error", t.toString())
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            mindfulMessage.onEvent(event)
        } catch (t: Throwable) {
            Log.e("Mindful message error", t.toString())
            crashLogger.logNonFatalError(Exception(t))
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    websiteUsageTracker.onEvent(event)
                    val reelComparator = reelScriptRunner.detect(event)
                    reelUsageTracker.onEvent(event, reelComparator)
                    reelsCountTracker.onEvent(event, reelComparator)
                    reelBlocker.doViewBlockerCheck(event, reelComparator)
                    keywordBlocker.checkIfUnsupportedBrowser(event)
                    if (BuildConfig.SUPPORTS_UI_HIDER) {
                        uiHider.doUiHiderCheck(event)
                    }
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            for (observation in websiteObservationChannel) {
                try {
                    keywordBlocker.onWebsiteObserved(observation)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("KeywordBlocker", "Website observation check failed", t)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        focusModeBlocker.setupFocusMode(this)
        autoDnd.setup(this)
        reelBlocker.setupBlocker(this)
        reelScriptRunner.setup(this)
        keywordBlocker.setupBlocker(this)
        grayScaleFilter.setup(this)
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupBlocker(this)
            nodePicker.setupBlocker(this)
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            antiUninstallBlocker.setupBlocker(this)
        }

        reelUsageTracker.setup(this)
        reelsCountTracker.setup(this, reelsOverlayManager, reelUsageTracker::onReelCounted)
        mindfulMessage.setup(this)
        websiteUsageTracker.setup(this) { observation ->
            websiteObservationChannel.trySend(observation)
        }
        try {
            appUsageTracker.setup(this)
            appUsageTrackerReady = true
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
            Log.e("AppUsageTracker", "Setup failed", t)
        }
        try {
            appRuleBlocker.setup(this)
            appRuleBlockerReady = true
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
            Log.e("AppRuleBlocker", "Setup failed", t)
            // setup may have started its settings collector before a later initialization step
            // failed. Stop that partial instance even though the ready flag was not published.
            runCatching { appRuleBlocker.onDestroy() }
        }
        neth.iecal.curbox.utils.UsageStatsCleaner.watch(this)

        focusModeBlocker.setupReceivers()
        try {
            appRuleBlocker.setupReceivers()
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
            Log.e("AppRuleBlocker", "Receiver setup failed", t)
        }
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        grayScaleFilter.setupReceivers()
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupReceivers()
            nodePicker.setupReceivers()
        }
        reelsCountTracker.setupReceivers()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.please_provide_draw_over_other_apps),
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Each feature owns independent receiver and worker state. Keep one faulty cleanup from
        // skipping the app-rule scheduler, channels, or the remaining service components.
        cleanupFeature("AppRuleBlocker") {
            if (appRuleBlockerReady) appRuleBlocker.onDestroy()
        }
        cleanupFeature("FocusModeBlocker") { focusModeBlocker.removeReceivers() }
        cleanupFeature("AutoDnd") { autoDnd.stop() }
        cleanupFeature("ReelBlocker") { reelBlocker.removeReceivers() }
        cleanupFeature("KeywordBlocker") { keywordBlocker.removeReceivers() }
        cleanupFeature("GrayScaleFilter") { grayScaleFilter.unregisterReceivers() }
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            cleanupFeature("UiHider") { uiHider.removeReceivers() }
            cleanupFeature("NodePicker") { nodePicker.removeReceivers() }
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            cleanupFeature("AntiUninstallBlocker") { antiUninstallBlocker.onDestroy() }
        }
        cleanupFeature("MindfulMessage") { mindfulMessage.onDestroy() }
        cleanupFeature("ReelsCountTracker") { reelsCountTracker.onDestroy() }
        cleanupFeature("ReelUsageTracker") { reelUsageTracker.onDestroy() }
        cleanupFeature("WebsiteUsageTracker") { websiteUsageTracker.onDestroy() }
        if (appUsageTrackerReady) {
            cleanupFeature("AppUsageTracker") { appUsageTracker.onDestroy() }
        }
        cleanupFeature("EventChannel") { eventChannel.close() }
        cleanupFeature("WebsiteObservationChannel") { websiteObservationChannel.close() }
        cleanupFeature("ServiceScope") { serviceScope.cancel() }
    }

    private fun cleanupFeature(name: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            runCatching {
                if (::crashLogger.isInitialized) {
                    crashLogger.logNonFatalError(
                        if (error is Exception) error else Exception(error)
                    )
                }
            }
            Log.e("AppBlockerService", "$name cleanup failed", error)
        }
    }
}
