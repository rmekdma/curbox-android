package neth.iecal.todo

import com.google.gson.JsonParser

/** A subject uses its own user identifier and daily report format. */
enum class Subject { KOREAN, MATH, ENGLISH }
data class DailyReport(val count: Int, val minutes: Int, val seconds: Double? = null)

object TodoReportParser {
    /** Rejects incomplete reports; only a valid empty activity list means zero. */
    fun parse(subject: Subject, json: String): DailyReport {
        val report = JsonParser.parseString(json).asJsonObject
        val history = report.getAsJsonObject("contentsHistory")
        val activities = history.getAsJsonArray(if (subject == Subject.MATH) "daily" else "dailyCourse")
        val categories = report.getAsJsonObject("learningSecondsByCategory")
        var seconds = 0.0
        for ((key, value) in categories.entrySet()) {
            if (key == "etc") continue
            require(value.isJsonPrimitive && (value.asJsonPrimitive.isNumber || value.asJsonPrimitive.isString))
            val number = value.asString.toDouble()
            require(number.isFinite() && number >= 0)
            seconds += number
        }
        require(seconds.isFinite() && seconds <= Int.MAX_VALUE.toDouble() * 60)
        return DailyReport(activities.size(), kotlin.math.ceil(seconds / 60).toInt(), seconds)
    }
}
