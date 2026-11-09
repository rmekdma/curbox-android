package neth.iecal.curbox.learning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import neth.iecal.curbox.R
import neth.iecal.todo.StoredLearning
import neth.iecal.todo.Subject
import neth.iecal.todo.ReportState
import neth.iecal.todo.learningDisplayReports
import neth.iecal.todo.totalLearningMinutes
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
        val monitorChild = current.monitor.children.firstOrNull { it.name == child }
        val screenChild = current.state.children.firstOrNull { it.name == child }
        val subjects = monitorChild?.userIds?.keys.orEmpty() + screenChild?.userIds?.keys.orEmpty()
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val reports = learningDisplayReports(current.state, current.monitor, child, subjects, today)
        fun failureText(failure: neth.iecal.todo.LearningFailure): String = context.getString(when (failure) {
            neth.iecal.todo.LearningFailure.NETWORK -> R.string.learning_network_error
            neth.iecal.todo.LearningFailure.SERVER -> R.string.learning_server_error
            neth.iecal.todo.LearningFailure.INVALID_DATA -> R.string.learning_invalid_error
            neth.iecal.todo.LearningFailure.STORAGE -> R.string.learning_storage_error
            neth.iecal.todo.LearningFailure.AUTHENTICATION -> R.string.learning_login_required
        })
        val rows = subjects.map { subject ->
            val key = child to subject
            val goal = current.state.goalsByAccount[current.state.accountId]?.get(key) ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            val errors = listOfNotNull(
                current.state.reports[key]?.takeIf { it.failed }?.failure?.let { context.getString(R.string.learning_manual_error, failureText(it)) },
                current.monitor.reports[key]?.takeIf { it.failed }?.failure?.let { context.getString(R.string.learning_automatic_error, failureText(it)) },
                current.monitor.catalogFailureFor(child)?.let(::failureText)
            )
            LearningNotificationRow(context.getString(when (subject) { Subject.KOREAN -> R.string.learning_korean; Subject.MATH -> R.string.learning_math; Subject.ENGLISH -> R.string.learning_english }), goal, reports[subject], errors)
        }
        val pending = current.monitor.catalogFailureFor(child)?.let(::failureText) ?: context.getString(R.string.learning_unknown)
        fun line(row: LearningNotificationRow, expanded: Boolean) = learningNotificationLine(row, expanded,
            context.getString(R.string.learning_notification_complete),
            { goal -> context.getString(R.string.learning_unknown_progress, goal) },
            { count, goal -> context.getString(R.string.learning_notification_progress, count, goal) },
            { minutes -> context.getString(R.string.learning_minutes, minutes) })
        val compact = if (rows.isEmpty()) pending else rows.joinToString(" · ") { line(it, false) }
        val details = if (rows.isEmpty()) pending else rows.joinToString("\n") { line(it, true) }
        val total = totalLearningMinutes(reports.values)
        val totalText = if (total == null) context.getString(R.string.learning_unknown_minutes) else context.getString(R.string.learning_minutes, total)
        val notification = NotificationCompat.Builder(context, "learning_achievements")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(child)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText(compact).setStyle(NotificationCompat.BigTextStyle().setBigContentTitle("$child  $totalText").bigText(details))
            .setOngoing(true).setSilent(child != achievementChild).build()
        if (permission.value == expected && expected.granted && notifications.areNotificationsEnabled() && (child != achievementChild || stillValid())) notifications.notify(tag(child), 0x4c454152, notification)
        }
    }
}

internal data class LearningNotificationRow(val label: String, val goal: Int, val report: ReportState?, val errors: List<String> = emptyList())

internal fun learningNotificationLine(
    row: LearningNotificationRow, expanded: Boolean, check: String,
    unknown: (Int) -> String, progress: (Int, Int) -> String, minutes: (Int) -> String
): String {
    val value = row.report?.value
    val complete = row.goal == 0 || (value != null && row.report.failed == false && value.count >= row.goal)
    val parts = mutableListOf(row.label)
    if (complete) parts.add(check)
    if (row.goal != 0 && (!complete || expanded)) parts.add(if (value == null) unknown(row.goal) else progress(value.count, row.goal))
    if (expanded && value != null) parts.add(minutes(value.minutes))
    if (expanded) parts.addAll(row.errors)
    return parts.joinToString(" ")
}
