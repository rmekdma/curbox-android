package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleAppGroup
import neth.iecal.curbox.data.models.AppRuleScope
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.AppRulesEvaluation
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.services.BaseBlockingService
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 0 contract for AR010. It deliberately does not add ordering protection to production.
 */
@RunWith(AndroidJUnit4::class)
class AppRuleBlockerRefreshOrderingRedTest {
    @Test
    fun latestRefreshCannotBeFollowedByDelayedStaleSettingsPublication() = runBlocking {
        val blocker = AppRuleBlocker()
        val service = RecordingService().also {
            it.attach(InstrumentationRegistry.getInstrumentation().targetContext)
            it.lastBackPressTimeStamp = 0L
        }
        val repository = EmptySessionRepository()
        val snapshotCoordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        val refreshMutex = getField(blocker, "refreshMutex") as Mutex
        val recheckGeneration = getField(blocker, "recheckGeneration") as AtomicLong
        val timeline = CopyOnWriteArrayList<String>()
        val visibleChecks = CopyOnWriteArrayList<AppRulesEvaluation>()
        val initialSnapshot = snapshot(allowedMinutes = 1L, ruleName = "initial")
        val staleSnapshot = snapshot(allowedMinutes = 0L, ruleName = "stale")
        val latestSnapshot = snapshot(allowedMinutes = 10L, ruleName = "latest")

        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))
        setField(blocker, "currentForegroundPackage", TARGET_PACKAGE)
        blocker.wallClockMsProvider = { NOW_MS }
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
        blocker.evaluationResultObserver = { evaluation -> visibleChecks += evaluation }
        snapshotCoordinator.accept(initialSnapshot)

        val staleEmissionReceived = CompletableDeferred<Unit>()
        val releaseStaleEmission = CompletableDeferred<Unit>()
        val staleEmission = launch(Dispatchers.Default) {
            timeline += "stale settings emission received"
            staleEmissionReceived.complete(Unit)
            releaseStaleEmission.await()
            publishSharedPublicationPath(
                blocker = blocker,
                refreshMutex = refreshMutex,
                source = "stale settings emission",
                settings = Settings(appRuleSnapshot = staleSnapshot),
                timeline = timeline
            )
        }

        try {
            staleEmissionReceived.await()
            timeline += "latest refresh starts after stale emission is delayed"
            async(Dispatchers.Default) {
                publishSharedPublicationPath(
                    blocker = blocker,
                    refreshMutex = refreshMutex,
                    source = "latest refresh",
                    settings = Settings(appRuleSnapshot = latestSnapshot),
                    timeline = timeline
                )
            }.await()

            val snapshotAfterLatestRefresh = snapshotCoordinator.snapshot()
            val generationAfterLatestRefresh = recheckGeneration.get()
            if (snapshotAfterLatestRefresh != latestSnapshot.normalized()) {
                throw AssertionError(
                    "latest refresh did not publish before stale emission; timeline=$timeline"
                )
            }
            if (generationAfterLatestRefresh <= 0L) {
                throw AssertionError(
                    "latest refresh did not advance the runtime generation; timeline=$timeline"
                )
            }
            timeline += "latest refresh published generation=$generationAfterLatestRefresh"

            releaseStaleEmission.complete(Unit)
            staleEmission.join()
            val finalSnapshot = snapshotCoordinator.snapshot()
            val finalGeneration = recheckGeneration.get()
            timeline += "stale emission published final generation=$finalGeneration"

            // The production receiver and settings collector both post this same reconciliation
            // after their shared publication critical section. Invoke the real reconciliation
            // method synchronously so the result is deterministic and observable here.
            invokePrivate(blocker, "checkCurrentlyVisibleApplications")
            timeline += "visible reconciliation allowed=" +
                visibleChecks.singleOrNull()?.isAllowed +
                " warningActivities=${service.startedActivities.size}"

            val failures = mutableListOf<String>()
            if (finalSnapshot != latestSnapshot.normalized()) {
                failures += "final snapshot regressed to ${snapshotLabel(finalSnapshot)}"
            }
            if (finalGeneration != generationAfterLatestRefresh) {
                failures += "final generation $finalGeneration differs from latest generation " +
                    generationAfterLatestRefresh
            }
            if (visibleChecks.size != 1) {
                failures += "expected one visible check, observed ${visibleChecks.size}"
            }
            if (visibleChecks.singleOrNull()?.isAllowed != true) {
                failures += "visible reconciliation used stale denial instead of latest allow"
            }
            if (service.startedActivities.isNotEmpty()) {
                failures += "visible user outcome launched ${service.startedActivities.size} " +
                    "warning activity instead of allowing the latest snapshot"
            }

            if (failures.isNotEmpty()) {
                throw AssertionError(
                    "AR010 deterministic interleaving reproduced:\n" +
                        failures.joinToString(separator = "\n") { "- $it" } +
                        "\nforced timeline:\n" +
                        timeline.joinToString(separator = "\n") { "- $it" }
                )
            }
        } finally {
            releaseStaleEmission.complete(Unit)
            staleEmission.join()
            blocker.onDestroy()
        }
    }

    /**
     * Both production publication endpoints converge on this exact critical section:
     * `refreshMutex.withLock { applySettingsSnapshot(settings) }`. The settings collector calls
     * it at AppRuleBlocker.kt:209-212, and the refresh receiver calls it at :583-588. The receiver's
     * package/settings reads happen before this snapshot apply, while its visible reconciliation
     * is posted after the lock, so neither endpoint adds an ordering identity to this path.
     */
    private suspend fun publishSharedPublicationPath(
        blocker: AppRuleBlocker,
        refreshMutex: Mutex,
        source: String,
        settings: Settings,
        timeline: MutableList<String>
    ) {
        refreshMutex.withLock {
            timeline += "$source acquired publication lock"
            val changed = invokePrivateResult(
                blocker,
                "applySettingsSnapshot",
                settings
            ) as Boolean
            timeline += "$source applied changed=$changed"
        }
    }

    private fun snapshot(allowedMinutes: Long, ruleName: String): AppRuleSnapshot {
        val group = AppRuleAppGroup(
            id = GROUP_ID,
            name = "Target",
            selectedPackages = listOf(TARGET_PACKAGE)
        )
        return AppRuleSnapshot(
            appGroups = listOf(group),
            appRules = listOf(
                AppRule(
                    id = RULE_ID,
                    name = ruleName,
                    weekdays = (0..6).toSet(),
                    startMinute = 0,
                    endMinute = 0,
                    scope = AppRuleScope.forGroup(GROUP_ID),
                    allowedMinutes = allowedMinutes
                )
            )
        )
    }

    private fun snapshotLabel(snapshot: AppRuleSnapshot): String =
        snapshot.appRules.singleOrNull()?.name ?: "<empty>"

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }

        override fun getSystemService(name: String): Any? = null

        override fun getWindows(): MutableList<AccessibilityWindowInfo> = mutableListOf()
    }

    private class EmptySessionRepository : CurrentUseDaySessionRepository {
        override suspend fun startSession(
            useDayId: String,
            packageName: String,
            startedAtMs: Long
        ): Long = 1L

        override suspend fun finishSession(id: Long, endedAtMs: Long) = Unit

        override suspend fun updateSessionEnd(id: Long, endedAtMs: Long) = Unit

        override suspend fun sessionsForUseDay(useDayId: String): List<ForegroundSession> = emptyList()

        override suspend fun finishOpenSessions(useDayId: String, endedAtMs: Long) = Unit
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun invokePrivate(target: Any, name: String, vararg args: Any?) {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun invokePrivateResult(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.reader"
        const val GROUP_ID = "target-group"
        const val RULE_ID = "target-rule"
        const val NOW_MS = 1_756_642_800_000L
    }
}
