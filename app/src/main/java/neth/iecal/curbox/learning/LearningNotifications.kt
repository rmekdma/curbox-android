package neth.iecal.curbox.learning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import neth.iecal.curbox.R
import neth.iecal.todo.StoredLearning
import neth.iecal.todo.Subject
import neth.iecal.todo.registeredChildren
import neth.iecal.todo.catalogFailureFor

/** Serializes notification effects from replacement service connections. */
object LearningNotifications {
    suspend fun publish(context: Context, store: LearningStorage, committed: StoredLearning,
        permission: kotlinx.coroutines.flow.StateFlow<neth.iecal.todo.NotificationPermission>, expected: neth.iecal.todo.NotificationPermission,
        achievementChild: String? = null, achievementSubject: Subject? = null,
        stillValid: suspend () -> Boolean = { true }) =
        neth.iecal.todo.OrderedLearningPublisher.publish(store, committed, permission, expected) publisher@{ current ->
        if (achievementChild != null && (!stillValid() || current.monitor.latestAchievements[current.monitor.accountId to achievementChild] != achievementSubject ||
                current.monitor.reportVersions[achievementChild to achievementSubject] != committed.monitor.reportVersions[achievementChild to achievementSubject])) return@publisher
        val notifications = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val children = if (current.monitor.accountId == current.state.accountId) current.monitor.registeredChildren().keys else emptySet()
        fun tag(child: String) = "learning:" + java.util.UUID.nameUUIDFromBytes("${current.monitor.accountId}:$child".toByteArray())
        val tags = children.map(::tag).toSet()
        for (active in manager.activeNotifications) {
            if (active.tag?.startsWith("learning:") == true && active.tag !in tags) manager.cancel(active.tag, active.id)
        }
        if (children.isEmpty()) return@publisher
        manager.createNotificationChannel(NotificationChannel("learning_achievements", context.getString(R.string.learning_notification_channel), NotificationManager.IMPORTANCE_HIGH))
        for (child in children) {
        val details = current.monitor.children.firstOrNull { it.name == child }?.userIds?.keys.orEmpty().joinToString("\n") { subject ->
            val label = context.getString(when (subject) { Subject.KOREAN -> R.string.learning_korean; Subject.MATH -> R.string.learning_math; Subject.ENGLISH -> R.string.learning_english })
            val key = child to subject
            val report = current.monitor.reports[key]
            val goal = current.state.goalsByAccount[current.state.accountId]?.get(key) ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val reportValue = report?.value
            val value = when {
                goal == 0 -> context.getString(R.string.learning_checked_complete)
                reportValue != null -> {
                    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    val status = if (report.date != today) context.getString(R.string.learning_unknown)
                        else context.getString(if (reportValue.count >= goal) R.string.learning_checked_complete else R.string.learning_incomplete)
                    context.getString(R.string.learning_monitor_value, reportValue.count,
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.checkedAt ?: 0))) + " " + status
                }
                else -> context.getString(R.string.learning_unknown)
            }
            val failure = when (report?.failure ?: current.monitor.catalogFailureFor(child)) {
                neth.iecal.todo.LearningFailure.NETWORK -> context.getString(R.string.learning_network_error)
                neth.iecal.todo.LearningFailure.SERVER -> context.getString(R.string.learning_server_error)
                neth.iecal.todo.LearningFailure.INVALID_DATA -> context.getString(R.string.learning_invalid_error)
                neth.iecal.todo.LearningFailure.STORAGE -> context.getString(R.string.learning_storage_error)
                neth.iecal.todo.LearningFailure.AUTHENTICATION -> context.getString(R.string.learning_login_required)
                null -> ""
            }
            "$label: $value $failure".trim()
        }
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val latest = current.monitor.latestAchievements[current.monitor.accountId to child]
            ?.takeIf { current.monitor.reports[child to it]?.date == today }
        val title = if (latest == null) child else context.getString(R.string.learning_achievement_title, child, context.getString(when (latest) {
            Subject.KOREAN -> R.string.learning_korean; Subject.MATH -> R.string.learning_math; Subject.ENGLISH -> R.string.learning_english
        }))
        val notification = NotificationCompat.Builder(context, "learning_achievements")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText(details).setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true).setSilent(child != achievementChild).build()
        if (permission.value == expected && expected.granted && notifications.areNotificationsEnabled() && (child != achievementChild || stillValid())) notifications.notify(tag(child), 0x4c454152, notification)
        }
    }
}
