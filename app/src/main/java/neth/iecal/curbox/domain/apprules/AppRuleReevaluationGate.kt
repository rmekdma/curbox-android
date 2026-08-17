package neth.iecal.curbox.domain.apprules

/** Keeps the next applicable package check from being hidden by the warning throttle. */
class AppRuleReevaluationGate {
    private var overrideChanged = false

    fun markOverrideChanged() {
        overrideChanged = true
    }

    fun consumeIfApplicable(hasApplicableRules: Boolean): Boolean {
        if (!hasApplicableRules) return false
        val shouldBypass = overrideChanged
        overrideChanged = false
        return shouldBypass
    }
}
