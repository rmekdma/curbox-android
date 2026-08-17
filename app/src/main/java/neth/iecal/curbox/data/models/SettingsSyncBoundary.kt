package neth.iecal.curbox.data.models

/** Removes device local guardian material before a Settings snapshot is uploaded. */
object SettingsSyncBoundary {
    fun forUpload(settings: Settings): Settings = settings.copy(
        guardianAuthConfig = GuardianAuthConfig(),
        appRuleOverrideState = AppRuleOverrideState()
    )
}
