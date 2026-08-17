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

    @Query("SELECT * FROM foreground_sessions WHERE useDayId = :useDayId ORDER BY startedAtMs")
    suspend fun getForUseDay(useDayId: String): List<ForegroundSessionEntity>

    @Query("UPDATE foreground_sessions SET endedAtMs = :endedAtMs WHERE useDayId = :useDayId AND endedAtMs IS NULL")
    suspend fun finishOpenForUseDay(useDayId: String, endedAtMs: Long): Int

    @Query("UPDATE foreground_sessions SET endedAtMs = startedAtMs WHERE useDayId = :useDayId AND endedAtMs IS NULL")
    suspend fun discardOpenForUseDay(useDayId: String): Int

    @Query("DELETE FROM foreground_sessions WHERE useDayId < :currentUseDayId")
    suspend fun deleteBeforeUseDay(currentUseDayId: String): Int
}
