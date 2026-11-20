package neth.iecal.todo

internal class LearningTextResources(private val context: android.content.Context) {
    fun learningText(key: neth.iecal.todo.LearningText, vararg args: Any): String {
        val resource = when (key) {
            neth.iecal.todo.LearningText.ACCESSIBILITY_NEEDED -> R.string.learning_accessibility_needed
            neth.iecal.todo.LearningText.NOTIFICATIONS_NEEDED -> R.string.learning_notifications_needed
            neth.iecal.todo.LearningText.SERVICE_UNKNOWN -> R.string.learning_service_unknown
            neth.iecal.todo.LearningText.ACCESSIBILITY_SETTINGS -> R.string.learning_accessibility_settings
            neth.iecal.todo.LearningText.NOTIFICATION_SETTINGS -> R.string.learning_notification_settings
            neth.iecal.todo.LearningText.TITLE -> if (neth.iecal.todo.BuildConfig.LEARNING_FAKE_ONLY) R.string.learning_demo_title else R.string.learning_title
            neth.iecal.todo.LearningText.EMAIL -> R.string.learning_email
            neth.iecal.todo.LearningText.PASSWORD -> R.string.learning_password
            neth.iecal.todo.LearningText.SIGN_IN -> R.string.learning_sign_in
            neth.iecal.todo.LearningText.CHANGE_ACCOUNT -> R.string.learning_change_account
            neth.iecal.todo.LearningText.CANCEL -> R.string.learning_cancel
            neth.iecal.todo.LearningText.SUBJECT_SETTINGS -> R.string.learning_subject_settings
            neth.iecal.todo.LearningText.CONFIRM -> R.string.learning_confirm
            neth.iecal.todo.LearningText.WATCHING -> R.string.learning_watching
            neth.iecal.todo.LearningText.NOT_WATCHING -> R.string.learning_not_watching
            neth.iecal.todo.LearningText.SETTINGS_CHANGED -> R.string.learning_settings_changed
            neth.iecal.todo.LearningText.MONITOR_PROGRESS -> R.string.learning_monitor_progress
            neth.iecal.todo.LearningText.MANUAL_ERROR -> R.string.learning_manual_error
            neth.iecal.todo.LearningText.AUTOMATIC_ERROR -> R.string.learning_automatic_error
            neth.iecal.todo.LearningText.PROGRESS_COUNT -> R.string.learning_notification_progress
            neth.iecal.todo.LearningText.ZERO_GOAL -> R.string.learning_zero_goal
            neth.iecal.todo.LearningText.RETRY -> R.string.learning_retry
            neth.iecal.todo.LearningText.UNKNOWN_PROGRESS -> R.string.learning_unknown_progress
            neth.iecal.todo.LearningText.UNKNOWN -> R.string.learning_unknown
            neth.iecal.todo.LearningText.EMPTY -> R.string.learning_empty
            neth.iecal.todo.LearningText.ERROR -> R.string.learning_error
            neth.iecal.todo.LearningText.NETWORK_ERROR -> R.string.learning_network_error
            neth.iecal.todo.LearningText.SERVER_ERROR -> R.string.learning_server_error
            neth.iecal.todo.LearningText.INVALID_ERROR -> R.string.learning_invalid_error
            neth.iecal.todo.LearningText.STORAGE_ERROR -> R.string.learning_storage_error
            neth.iecal.todo.LearningText.LOGIN_REQUIRED -> R.string.learning_login_required
            neth.iecal.todo.LearningText.SUBJECT_KOREAN -> R.string.learning_korean
            neth.iecal.todo.LearningText.SUBJECT_MATH -> R.string.learning_math
            neth.iecal.todo.LearningText.SUBJECT_ENGLISH -> R.string.learning_english
            neth.iecal.todo.LearningText.REFRESH -> R.string.learning_refresh
            neth.iecal.todo.LearningText.GOAL -> R.string.learning_goal
            neth.iecal.todo.LearningText.SAVE_GOAL -> R.string.learning_save_goal
            neth.iecal.todo.LearningText.INVALID_GOAL -> R.string.learning_invalid_goal
            neth.iecal.todo.LearningText.COMPLETE -> R.string.learning_complete
            neth.iecal.todo.LearningText.INCOMPLETE -> R.string.learning_incomplete
            neth.iecal.todo.LearningText.MONITOR_START -> R.string.learning_monitor_start
            neth.iecal.todo.LearningText.MONITOR_STOP -> R.string.learning_monitor_stop
            neth.iecal.todo.LearningText.MONITOR_STATUS -> R.string.learning_monitor_status
            neth.iecal.todo.LearningText.INTERVAL -> R.string.learning_interval
            neth.iecal.todo.LearningText.GENERAL_SETTINGS -> R.string.learning_general_settings
            neth.iecal.todo.LearningText.ALERTS -> R.string.learning_alerts
            neth.iecal.todo.LearningText.SOUND -> R.string.learning_sound
            neth.iecal.todo.LearningText.SOUND_DEFAULT -> R.string.learning_sound_default
            neth.iecal.todo.LearningText.SOUND_SILENT -> R.string.learning_sound_silent
            neth.iecal.todo.LearningText.SOUND_SELECTED -> R.string.learning_sound_selected
            neth.iecal.todo.LearningText.SAVE_INTERVAL -> R.string.learning_save_interval
            neth.iecal.todo.LearningText.INVALID_INTERVAL -> R.string.learning_invalid_interval
        }
        return context.getString(resource, *args)
    }

}
