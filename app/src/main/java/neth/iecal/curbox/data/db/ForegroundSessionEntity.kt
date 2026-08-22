package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.Index
import neth.iecal.curbox.data.models.ForegroundSession

@Entity(
    tableName = "foreground_sessions",
    indices = [Index(value = ["useDayId"]), Index(value = ["packageName", "useDayId"])]
)
data class ForegroundSessionEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val useDayId: String,
    val packageName: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val useDayGenerationStartedAtMs: Long = 0L,
    val statisticsTracked: Boolean = true
)

fun ForegroundSessionEntity.toDomain(): ForegroundSession = ForegroundSession(
    id = id,
    useDayId = useDayId,
    packageName = packageName,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
    statisticsTracked = statisticsTracked
)

fun ForegroundSession.toEntity(): ForegroundSessionEntity = ForegroundSessionEntity(
    id = id,
    useDayId = useDayId,
    packageName = packageName,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    useDayGenerationStartedAtMs = useDayGenerationStartedAtMs,
    statisticsTracked = statisticsTracked
)
