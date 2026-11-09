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
import neth.iecal.todo.learningSubjects
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
        val statusChannel = NotificationChannel("learning_status_v2", context.getString(R.string.learning_notification_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
        manager.createNotificationChannel(statusChannel)
        for (child in children) {
        val subjects = learningSubjects(current.state, current.monitor, child).keys
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
            val goal = current.state.goalsByAccount[current.state.accountId]?.get(key) ?: subject.defaultGoal
            val errors = listOfNotNull(
                current.state.reports[key]?.takeIf { it.failed }?.failure?.let { context.getString(R.string.learning_manual_error, failureText(it)) },
                current.monitor.reports[key]?.takeIf { it.failed }?.failure?.let { context.getString(R.string.learning_automatic_error, failureText(it)) },
                current.monitor.catalogFailureFor(child)?.let(::failureText)
            )
            LearningNotificationRow(context.getString(when (subject) { Subject.KOREAN -> R.string.learning_korean; Subject.MATH -> R.string.learning_math; Subject.ENGLISH -> R.string.learning_english }), goal, reports[subject], errors)
        }
        val pending = current.monitor.catalogFailureFor(child)?.let(::failureText) ?: context.getString(R.string.learning_unknown)
        fun line(row: LearningNotificationRow) = learningNotificationLine(row,
            context.getString(R.string.learning_notification_complete),
            { goal -> context.getString(R.string.learning_unknown_progress, goal) },
            { count, goal -> context.getString(R.string.learning_notification_progress, count, goal) })
        val compact = if (rows.isEmpty()) pending else rows.joinToString(" · ") { line(it) }
        val alert = child == achievementChild && allowsLearningAlert(committed.monitor, current.monitor)
        val sound = current.monitor.alertSound
        val channelId = if (!alert) statusChannel.id else if (sound == null) "learning_achievements" else {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sound.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            "learning_achievement_${digest.take(24)}"
        }
        if (alert) {
            val channel = NotificationChannel(channelId, context.getString(R.string.learning_notification_channel), NotificationManager.IMPORTANCE_HIGH)
            if (sound != null) channel.setSound(if (sound == "silent") null else android.net.Uri.parse(sound), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            manager.createNotificationChannel(channel)
        }
        fun remote(): android.widget.RemoteViews {
            val view = android.widget.RemoteViews(context.packageName, R.layout.learning_notification_compact)
            view.setTextViewText(R.id.learning_notification_title, child)
            val rowIds = intArrayOf(R.id.learning_notification_row_0, R.id.learning_notification_row_1, R.id.learning_notification_row_2)
            val labelIds = intArrayOf(R.id.learning_notification_label_0, R.id.learning_notification_label_1, R.id.learning_notification_label_2)
            val checkIds = intArrayOf(R.id.learning_notification_check_0, R.id.learning_notification_check_1, R.id.learning_notification_check_2)
            val valueIds = intArrayOf(R.id.learning_notification_value_0, R.id.learning_notification_value_1, R.id.learning_notification_value_2)
            if (rows.isEmpty()) { view.setViewVisibility(R.id.learning_notification_pending, android.view.View.VISIBLE); view.setTextViewText(R.id.learning_notification_pending, pending) }
            rows.take(3).forEachIndexed { index, row ->
                val value = row.report?.value
                val complete = row.goal == 0 || (value != null && !row.report.failed && value.count >= row.goal)
                val raw = line(row)
                val suffix = raw.removePrefix(row.label).replace(context.getString(R.string.learning_notification_complete), "").trim()
                view.setViewVisibility(rowIds[index], android.view.View.VISIBLE)
                view.setTextViewText(labelIds[index], row.label)
                view.setViewVisibility(checkIds[index], if (complete) android.view.View.VISIBLE else android.view.View.GONE)
                view.setContentDescription(checkIds[index], context.getString(R.string.learning_checked_complete))
                view.setTextViewText(valueIds[index], if (suffix.isEmpty()) "" else " $suffix")
            }
            return view
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(child)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText(compact).setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remote()).setCustomHeadsUpContentView(remote())
            .setOngoing(true).setSilent(!alert).build()
        val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.read() }
        if (latest.monitor.alertSettingsRevision != current.monitor.alertSettingsRevision) continue
        if (permission.value == expected && expected.granted && notifications.areNotificationsEnabled() && (child != achievementChild || stillValid())) notifications.notify(tag(child), 0x4c454152, notification)
        }
    }
}

internal fun allowsLearningAlert(committed: neth.iecal.todo.MonitorState, current: neth.iecal.todo.MonitorState): Boolean =
    current.completionAlerts != false && committed.alertSettingsRevision == current.alertSettingsRevision &&
        committed.completionAlerts == current.completionAlerts && committed.alertSound == current.alertSound

internal data class LearningNotificationRow(val label: String, val goal: Int, val report: ReportState?, val errors: List<String> = emptyList())

internal fun learningNotificationLine(
    row: LearningNotificationRow, check: String,
    unknown: (Int) -> String, progress: (Int, Int) -> String
): String {
    val value = row.report?.value
    val complete = row.goal == 0 || (value != null && row.report.failed == false && value.count >= row.goal)
    val parts = mutableListOf(row.label)
    if (complete) parts.add(check)
    if (row.goal != 0 && !complete) parts.add(if (value == null) unknown(row.goal) else progress(value.count, row.goal))
    return parts.joinToString(" ")
}
