package neth.iecal.curbox.data.db

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "foreground_launch_events",
    indices = [Index(value = ["useDayId"]), Index(value = ["packageName", "useDayId"])]
)
data class ForegroundLaunchEntity(
    @androidx.room.PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val useDayId: String,
    val packageName: String,
    val launchedAtMs: Long,
    val useDayGenerationStartedAtMs: Long = 0L
)
