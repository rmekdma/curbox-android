package neth.iecal.curbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ForegroundSessionDao {
    @Insert
    suspend fun insert(session: ForegroundSessionEntity): Long

    @Query("UPDATE foreground_sessions SET endedAtMs = :endedAtMs WHERE id = :id AND endedAtMs IS NULL")
    suspend fun finish(id: Long, endedAtMs: Long): Int

    @Query("UPDATE foreground_sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun updateEnd(id: Long, endedAtMs: Long): Int

    @Query("DELETE FROM foreground_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("SELECT * FROM foreground_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ForegroundSessionEntity?

    @Query("SELECT * FROM foreground_sessions WHERE useDayId = :useDayId ORDER BY startedAtMs")
    suspend fun getForUseDay(useDayId: String): List<ForegroundSessionEntity>

    @Query("""
        SELECT * FROM foreground_sessions
        WHERE useDayId = :useDayId
          AND (:generationStartedAtMs <= 0 OR useDayGenerationStartedAtMs >= :generationStartedAtMs)
        ORDER BY startedAtMs
    """)
    suspend fun getForUseDaySinceGeneration(
        useDayId: String,
        generationStartedAtMs: Long
    ): List<ForegroundSessionEntity>

    @Query("""
        UPDATE foreground_sessions
        SET endedAtMs = :endedAtMs
        WHERE id = :id
          AND startedAtMs < :endedAtMs
          AND (endedAtMs IS NULL OR endedAtMs > :endedAtMs)
    """)
    suspend fun cutAt(id: Long, endedAtMs: Long): Int

    @Query("UPDATE foreground_sessions SET endedAtMs = :endedAtMs WHERE useDayId = :useDayId AND endedAtMs IS NULL")
    suspend fun finishOpenForUseDay(useDayId: String, endedAtMs: Long): Int

    @Query("UPDATE foreground_sessions SET endedAtMs = startedAtMs WHERE useDayId = :useDayId AND endedAtMs IS NULL")
    suspend fun discardOpenForUseDay(useDayId: String): Int

    @Query("DELETE FROM foreground_sessions WHERE useDayId < :currentUseDayId")
    suspend fun deleteBeforeUseDay(currentUseDayId: String): Int

    @Query("""
        DELETE FROM foreground_sessions
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
