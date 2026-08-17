package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ForegroundLaunchDao {
    @Insert
    suspend fun insert(event: ForegroundLaunchEntity): Long

    @Query("SELECT * FROM foreground_launch_events WHERE useDayId = :useDayId ORDER BY launchedAtMs")
    suspend fun getForUseDay(useDayId: String): List<ForegroundLaunchEntity>

    @Query("""
        SELECT * FROM foreground_launch_events
        WHERE useDayId = :useDayId
          AND (:generationStartedAtMs <= 0 OR useDayGenerationStartedAtMs >= :generationStartedAtMs)
        ORDER BY launchedAtMs
    """)
    suspend fun getForUseDaySinceGeneration(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundLaunchEntity>

    @Query("DELETE FROM foreground_launch_events WHERE useDayId < :currentUseDayId")
    suspend fun deleteBeforeUseDay(currentUseDayId: String): Int

    @Query("""
        DELETE FROM foreground_launch_events
        WHERE useDayId < :currentUseDayId
           OR (
               useDayId = :currentUseDayId
               AND :generationStartedAtMs > 0
               AND useDayGenerationStartedAtMs < :generationStartedAtMs
           )
    """)
    suspend fun deleteBeforeUseDayGeneration(
        currentUseDayId: String,
        generationStartedAtMs: Long
    ): Int
}
