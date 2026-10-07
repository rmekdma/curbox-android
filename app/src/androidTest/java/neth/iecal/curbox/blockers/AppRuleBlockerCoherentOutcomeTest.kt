package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.RoomUsageResetRepository
import neth.iecal.curbox.data.models.AppRule
import neth.iecal.curbox.data.models.AppRuleGuardianDenial
import neth.iecal.curbox.data.models.AppRuleSnapshot
import neth.iecal.curbox.data.models.ForegroundSession
import neth.iecal.curbox.domain.apprules.AppRuleEnforcement
import neth.iecal.curbox.domain.apprules.AppRuleScope
import neth.iecal.curbox.domain.apprules.AppRuleSnapshotCoordinator
import neth.iecal.curbox.domain.apprules.CurrentUseDaySessionRepository
import neth.iecal.curbox.domain.apprules.DecisionOutcome
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.GuardianApprovalActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Type
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AppRuleBlockerCoherentOutcomeTest {
    @Test
    fun guardianDenialUsesRuleDetailsFromTheEnforcementSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = RecordingService().also {
            it.attach(context)
            it.lastBackPressTimeStamp = 0L
        }
        val nowMs = System.currentTimeMillis()
        val blocker = AppRuleBlocker().apply {
            wallClockMsProvider = { nowMs }
            elapsedRealtimeMsProvider = { SystemClock.elapsedRealtime() }
            screenInteractiveProvider = { true }
            keyguardLockedProvider = { false }
            activeWindowSnapshotProvider = {
                AppRuleBlocker.ActiveWindowSnapshot(packageName = TARGET_PACKAGE)
            }
            applicationWindowSnapshotProvider = {
                AppRuleBlocker.ApplicationWindowSnapshot(
                    packages = setOf(TARGET_PACKAGE),
                    hasApplicationWindow = true,
                    hasUnknownApplicationWindow = false
                )
            }
        }
        val repository = EmptySessionRepository()
        setField(blocker, "service", service)
        setField(blocker, "sessionRepository", repository)
        setField(blocker, "enforcement", AppRuleEnforcement(repository))
        setField(blocker, "setupReady", true)
        setField(blocker, "launchablePackages", setOf(TARGET_PACKAGE))

        val coordinator = getField(blocker, "snapshot") as AppRuleSnapshotCoordinator
        assertTrue(coordinator.accept(snapshotWithRuleName("Enforced rule name")))
        val enforcementOutcomes = CopyOnWriteArrayList<DecisionOutcome.EnforcementOutcome>()
        blocker.evaluationResultObserver = {
            // Simulate a newer UI snapshot becoming visible before the worker publishes the
            // enforcement result for the immutable snapshot it already evaluated.
            assertTrue(coordinator.accept(snapshotWithRuleName("Later rule name")))
        }
        blocker.decisionOutcomeSinkObserver = { outcome ->
            if (outcome is DecisionOutcome.EnforcementOutcome &&
                outcome.packageDecisions.any { it.packageName == TARGET_PACKAGE }
            ) {
                enforcementOutcomes += outcome
            }
        }

        try {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            try {
                event.packageName = TARGET_PACKAGE
                blocker.doAppRuleCheck(event)
            } finally {
                event.recycle()
            }

            assertTrue("the worker must publish the enforcement result", awaitCondition {
                enforcementOutcomes.isNotEmpty()
            })
            val packageDecision = enforcementOutcomes.single().packageDecisions.single()
            assertEquals(listOf(RULE_ID), packageDecision.denyingRuleIds)
            val evaluatedDenial = packageDecision.evaluation.denyingRules.single()
            assertEquals("Enforced rule name", packageDecision.denyingRuleNames[RULE_ID])
            assertEquals(RULE_ID, evaluatedDenial.ruleId)

            assertTrue("the blocker must launch the guardian", awaitCondition {
                service.startedActivities.any {
                    it.component?.className == GuardianApprovalActivity::class.java.name
                }
            })
            val intent = service.startedActivities.last {
                it.component?.className == GuardianApprovalActivity::class.java.name
            }
            val denial = Gson().fromJson<List<AppRuleGuardianDenial>>(
                intent.getStringExtra(GuardianApprovalActivity.EXTRA_DENIALS),
                DENIAL_LIST_TYPE
            ).single()

            assertEquals("Enforced rule name", denial.ruleName)
            assertEquals(packageDecision.denyingRuleNames[RULE_ID], denial.ruleName)
            assertEquals(evaluatedDenial.ruleId, denial.ruleId)
            val expectedReason = service.getString(
                R.string.app_rules_warning_status_no_condition,
                evaluatedDenial.earnedAllowanceMillis / 60_000L,
                evaluatedDenial.directAllowanceMillis / 60_000L,
                (evaluatedDenial.remainingMillis / 60_000L).coerceAtLeast(0L)
            )
            assertEquals(expectedReason, denial.reason)
        } finally {
            blocker.onDestroy()
        }
    }

    private fun snapshotWithRuleName(ruleName: String) = AppRuleSnapshot(
        appRules = listOf(
            AppRule(
                id = RULE_ID,
                name = ruleName,
                weekdays = (0..6).toSet(),
                startMinute = 0,
                endMinute = 0,
                scope = AppRuleScope(includeAllApps = true),
                allowedMinutes = 0
            )
        )
    )

    private class RecordingService : BaseBlockingService() {
        val startedActivities = CopyOnWriteArrayList<Intent>()

        fun attach(context: Context) {
            attachBaseContext(context)
        }

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }
    }

    private class EmptySessionRepository : CurrentUseDaySessionRepository {
        override suspend fun startSession(useDayId: String, packageName: String, startedAtMs: Long) = 1L
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
        if (target is AppRuleBlocker && name == "sessionRepository") {
            val service = target.javaClass.getDeclaredField("service").apply {
                isAccessible = true
            }.get(target) as BaseBlockingService
            target.javaClass.getDeclaredField("usageResetRepository").apply {
                isAccessible = true
                set(target, RoomUsageResetRepository(AppDatabase.getInstance(service)))
            }
        }
    }

    private fun getField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(10L)
        }
        return condition()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.coherent"
        const val RULE_ID = "denial"
        val DENIAL_LIST_TYPE: Type = object : TypeToken<List<AppRuleGuardianDenial>>() {}.type
    }
}
