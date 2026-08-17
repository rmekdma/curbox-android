package neth.iecal.curbox.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The first vertical slice uses a local 04:00 boundary. */
object UseDay {
    const val DEFAULT_RESET_HOUR = 4
    const val DEFAULT_RESET_MINUTE = 0

    private val ID_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    fun idAt(
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        resetHour: Int = DEFAULT_RESET_HOUR,
        resetMinute: Int = DEFAULT_RESET_MINUTE
    ): String {
        require(resetHour in 0..23) { "resetHour must be between 0 and 23" }
        require(resetMinute in 0..59) { "resetMinute must be between 0 and 59" }
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val reset = LocalTime.of(resetHour, resetMinute)
        val useDayDate = if (local.toLocalTime() < reset) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
        return useDayDate.format(ID_FORMAT)
    }

    fun windowFor(
        useDayId: String,
        zone: ZoneId = ZoneId.systemDefault(),
        resetHour: Int = DEFAULT_RESET_HOUR,
        resetMinute: Int = DEFAULT_RESET_MINUTE
    ): LongRange {
        require(resetHour in 0..23) { "resetHour must be between 0 and 23" }
        require(resetMinute in 0..59) { "resetMinute must be between 0 and 59" }
        val date = LocalDate.parse(useDayId, ID_FORMAT)
        val start = ZonedDateTime.of(date, LocalTime.of(resetHour, resetMinute), zone)
            .toInstant().toEpochMilli()
        val end = ZonedDateTime.of(date.plusDays(1), LocalTime.of(resetHour, resetMinute), zone)
            .toInstant().toEpochMilli()
        return start until end
    }
}
