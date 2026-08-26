package neth.iecal.curbox.ui.activity

internal object WarningActivityStopPolicy {
    fun shouldFinish(
        isChangingConfigurations: Boolean,
        isAwaitingOneShotSystemResult: Boolean
    ): Boolean = !isChangingConfigurations && !isAwaitingOneShotSystemResult
}
