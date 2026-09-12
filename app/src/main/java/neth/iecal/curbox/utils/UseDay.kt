package neth.iecal.curbox.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A validated local clock time at which a use day starts. */
data class UseDayResetTime(
    val hour: Int = UseDay.DEFAULT_RESET_HOUR,
    val minute: Int = UseDay.DEFAULT_RESET_MINUTE
) {
    init {
        require(hour in 0..23) { "reset hour must be between 0 and 23" }
        require(minute in 0..59) { "reset minute must be between 0 and 59" }
    }

    val minutesSinceMidnight: Int
        get() = hour * 60 + minute

    val localTime: LocalTime
        get() = LocalTime.of(hour, minute)
}

/**
 * Public clock seam used by the tracker and evaluator. Keeping the calculation behind this
 * interface lets JVM tests exercise reset boundaries without depending on Android settings or
 * the device default zone.
 */
interface UseDayCalculator {
    val zone: ZoneId
        get() = ZoneId.systemDefault()
    val resetTime: UseDayResetTime
        get() = UseDayResetTime()

    fun idAt(nowMs: Long): String

    /** The returned range is inclusive at the start and exclusive at the end. */
    fun windowFor(useDayId: String): LongRange

    /** Returns the millisecond of the next use-day reset boundary strictly after [nowMs]. */
    fun nextResetBoundaryAfter(nowMs: Long): Long {
        val currentWindow = windowFor(idAt(nowMs))
        return if (currentWindow.last < Long.MAX_VALUE) currentWindow.last + 1L else Long.MAX_VALUE
    }
}

class ConfigurableUseDayCalculator(
    override val zone: ZoneId = ZoneId.systemDefault(),
    override val resetTime: UseDayResetTime = UseDayResetTime()
) : UseDayCalculator {
    /** Positional convenience for callers whose first argument is the reset clock. */
    constructor(
        reset: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault()
    ) : this(zone, reset)

    override fun idAt(nowMs: Long): String = UseDay.idAt(nowMs, zone, resetTime)

    override fun windowFor(useDayId: String): LongRange = UseDay.windowFor(useDayId, zone, resetTime)
}

/** Use-day calculation with a backwards-compatible local 04:00 default. */
object UseDay {
    const val DEFAULT_RESET_HOUR = 4
    const val DEFAULT_RESET_MINUTE = 0
    const val DEFAULT_RESET_MINUTES = DEFAULT_RESET_HOUR * 60 + DEFAULT_RESET_MINUTE
    val DEFAULT_RESET_TIME: UseDayResetTime
        get() = UseDayResetTime(DEFAULT_RESET_HOUR, DEFAULT_RESET_MINUTE)

    private val ID_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    fun idAt(
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        resetHour: Int = DEFAULT_RESET_HOUR,
        resetMinute: Int = DEFAULT_RESET_MINUTE
    ): String {
        val reset = checkedResetTime(resetHour, resetMinute)
        return idAt(nowMs, zone, reset)
    }

    fun idAt(
        nowMs: Long,
        zone: ZoneId,
        resetTime: UseDayResetTime
    ): String {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val useDayDate = if (local.toLocalTime() < resetTime.localTime) {
            local.toLocalDate().minusDays(1)
        } else {
            local.toLocalDate()
        }
        return useDayDate.format(ID_FORMAT)
    }

    fun idAt(
        nowMs: Long,
        resetTime: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = idAt(nowMs, zone, resetTime)

    fun windowFor(
        useDayId: String,
        zone: ZoneId = ZoneId.systemDefault(),
        resetHour: Int = DEFAULT_RESET_HOUR,
        resetMinute: Int = DEFAULT_RESET_MINUTE
    ): LongRange {
        return windowFor(useDayId, zone, checkedResetTime(resetHour, resetMinute))
    }

    fun windowFor(
        useDayId: String,
        zone: ZoneId,
        resetTime: UseDayResetTime
    ): LongRange {
        val date = LocalDate.parse(useDayId, ID_FORMAT)
        val start = ZonedDateTime.of(date, resetTime.localTime, zone)
            .toInstant().toEpochMilli()
        val end = ZonedDateTime.of(date.plusDays(1), resetTime.localTime, zone)
            .toInstant().toEpochMilli()
        return start until end
    }

    fun windowFor(
        useDayId: String,
        resetTime: UseDayResetTime,
        zone: ZoneId = ZoneId.systemDefault()
    ): LongRange = windowFor(useDayId, zone, resetTime)

    fun calculator(
        zone: ZoneId = ZoneId.systemDefault(),
        resetTime: UseDayResetTime = UseDayResetTime()
    ): UseDayCalculator = ConfigurableUseDayCalculator(zone, resetTime)

    private fun checkedResetTime(hour: Int, minute: Int): UseDayResetTime =
        UseDayResetTime(hour, minute)
}
