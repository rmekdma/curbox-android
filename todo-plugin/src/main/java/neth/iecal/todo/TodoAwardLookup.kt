package neth.iecal.todo

/** Looks up all subscribed subject reports for one child and date, failing if any duration is unavailable. */
class TodoAwardLookup(private val api: TodoApi) {
    fun minutes(session: TodoSession, childName: String, date: String, expectedSubjects: Map<Subject, String>? = null): Long {
        val child = api.catalog(session).singleOrNull { it.name == childName }
            ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
        if (child.userIds.isEmpty()) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
        if (expectedSubjects != null && child.userIds != expectedSubjects) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
        var seconds = java.math.BigDecimal.ZERO
        try {
            for ((subject, userId) in child.userIds) {
                val durations = api.report(session, subject, userId, date).learningSecondsByCategory
                    ?: throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                for ((category, duration) in durations) {
                    if (category == "etc") continue
                    if (duration.signum() < 0) throw TodoApiException(kind = LearningFailure.INVALID_DATA)
                    seconds = seconds.add(duration)
                }
            }
        } catch (e: ArithmeticException) {
            throw TodoApiException(kind = LearningFailure.INVALID_DATA)
        }
        return try {
            seconds.divide(java.math.BigDecimal.valueOf(60), 0, java.math.RoundingMode.HALF_UP).longValueExact()
        } catch (e: ArithmeticException) {
            throw TodoApiException(kind = LearningFailure.INVALID_DATA)
        }
    }
}
