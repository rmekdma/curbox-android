package neth.iecal.curbox.domain.apprules

import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the next applicable package check from being hidden by the warning throttle. */
class AppRuleReevaluationGate {
    private val overrideChanged = AtomicBoolean(false)

    fun markOverrideChanged() {
        overrideChanged.set(true)
    }

    fun consumeIfApplicable(hasApplicableRules: Boolean): Boolean {
        if (!hasApplicableRules) return false
        return overrideChanged.compareAndSet(true, false)
    }
}
