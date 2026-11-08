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
        val checkRanges = mutableListOf<IntRange>()
        val details = learningNotificationDetails(current.monitor, child,
            { subject -> context.getString(when (subject) { Subject.KOREAN -> R.string.learning_korean; Subject.MATH -> R.string.learning_math; Subject.ENGLISH -> R.string.learning_english }) },
            { failure -> context.getString(when (failure) {
                neth.iecal.todo.LearningFailure.NETWORK -> R.string.learning_network_error
                neth.iecal.todo.LearningFailure.SERVER -> R.string.learning_server_error
                neth.iecal.todo.LearningFailure.INVALID_DATA -> R.string.learning_invalid_error
                neth.iecal.todo.LearningFailure.STORAGE -> R.string.learning_storage_error
                neth.iecal.todo.LearningFailure.AUTHENTICATION -> R.string.learning_login_required
            }) }, { start, end -> checkRanges.add(start until end) }) { subject ->
            val key = child to subject
            val report = current.monitor.reports[key]
            val goal = current.state.goalsByAccount[current.state.accountId]?.get(key) ?: when (subject) { Subject.KOREAN -> 6; Subject.MATH -> 5; Subject.ENGLISH -> 8 }
            learningNotificationValue(report, goal, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                context.getString(R.string.learning_unknown), context.getString(R.string.learning_notification_complete)) { count, target, complete ->
                context.getString(if (complete) R.string.learning_notification_progress_complete else R.string.learning_notification_progress, count, target)
            }
        }
        val styledDetails = android.text.SpannableString(details).apply {
            checkRanges.forEach { range ->
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(46, 125, 50)), range.first, range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val notification = NotificationCompat.Builder(context, "learning_achievements")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(child)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText(styledDetails).setStyle(NotificationCompat.BigTextStyle().bigText(styledDetails))
            .setOngoing(true).setSilent(child != achievementChild).build()
        if (permission.value == expected && expected.granted && notifications.areNotificationsEnabled() && (child != achievementChild || stillValid())) notifications.notify(tag(child), 0x4c454152, notification)
        }
    }
}

internal fun learningNotificationDetails(
    monitor: neth.iecal.todo.MonitorState, child: String,
    label: (Subject) -> String, failureText: (neth.iecal.todo.LearningFailure) -> String,
    checkRange: (Int, Int) -> Unit = { _, _ -> }, value: (Subject) -> String
): String {
    val subjects = monitor.children.firstOrNull { it.name == child }?.userIds?.keys.orEmpty()
    if (subjects.isEmpty()) return monitor.catalogFailureFor(child)?.let(failureText).orEmpty()
    val result = StringBuilder()
    for (subject in subjects) {
        if (result.isNotEmpty()) result.append('\n')
        val failure = (monitor.reports[child to subject]?.failure ?: monitor.catalogFailureFor(child))?.let(failureText).orEmpty()
        result.append(label(subject)).append(": ")
        val progress = value(subject)
        val start = result.length
        result.append(progress)
        progress.forEachIndexed { index, char -> if (char == '✓') checkRange(start + index, start + index + 1) }
        if (failure.isNotEmpty()) result.append(' ').append(failure)
    }
    return result.toString()
}

internal fun learningNotificationValue(
    report: neth.iecal.todo.ReportState?, goal: Int, today: String, unknown: String, complete: String,
    progress: (Int, Int, Boolean) -> String
): String {
    val value = report?.value
    return when {
        goal == 0 -> complete
        value == null || report.date != today -> unknown
        else -> progress(value.count, goal, value.count >= goal)
    }
}
