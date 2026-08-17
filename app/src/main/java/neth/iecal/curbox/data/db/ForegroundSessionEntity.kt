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
    val endedAtMs: Long? = null
)

fun ForegroundSessionEntity.toDomain(): ForegroundSession = ForegroundSession(
    id = id,
    useDayId = useDayId,
    packageName = packageName,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs
)

fun ForegroundSession.toEntity(): ForegroundSessionEntity = ForegroundSessionEntity(
    id = id,
    useDayId = useDayId,
    packageName = packageName,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs
)
