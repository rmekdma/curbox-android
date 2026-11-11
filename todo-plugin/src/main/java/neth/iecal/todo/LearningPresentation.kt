package neth.iecal.todo

/** Uses commit order so clock changes cannot revive an older subscription. */
fun learningCatalog(state: LearningState, monitor: MonitorState): List<TodoChild> {
    val useMonitor = state.accountId != null && monitor.accountId == state.accountId &&
        (monitor.monitorCatalogOrder ?: 0) > (monitor.manualCatalogOrder ?: 0)
    return if (useMonitor) monitor.children else state.children
}

fun learningSubjects(state: LearningState, monitor: MonitorState, child: String): Map<Subject, String> =
    learningCatalog(state, monitor).firstOrNull { it.name == child }?.userIds.orEmpty()

/** Selects the latest report from today for each requested subject, scoped to the active account. */
fun learningDisplayReports(state: LearningState, monitor: MonitorState, child: String, subjects: Set<Subject>, today: String): Map<Subject, ReportState?> =
    subjects.associateWith { subject ->
        val key = child to subject
        val manual = state.reports[key]
        val monitored = monitor.reports[key].takeIf { state.accountId != null && monitor.accountId == state.accountId }
        latestDisplayReport(manual, monitored, today)
    }

internal fun latestDisplayReport(manual: ReportState?, monitor: ReportState?, today: String): ReportState? =
    listOfNotNull(manual, monitor).filter { it.value != null && it.date == today }
        .maxByOrNull { it.checkedAt ?: Long.MIN_VALUE }
