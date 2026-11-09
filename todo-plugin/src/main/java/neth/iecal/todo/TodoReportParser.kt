package neth.iecal.todo

import com.google.gson.JsonParser

/** A subject uses its own user identifier and daily report format. */
enum class Subject { KOREAN, MATH, ENGLISH }
data class DailyReport(val count: Int)

object TodoReportParser {
    /** Rejects incomplete reports; only a valid empty activity list means zero. */
    fun parse(subject: Subject, json: String): DailyReport {
        val report = JsonParser.parseString(json).asJsonObject
        val history = report.getAsJsonObject("contentsHistory")
        val activities = history.getAsJsonArray(if (subject == Subject.MATH) "daily" else "dailyCourse")
        return DailyReport(activities.size())
    }
}
