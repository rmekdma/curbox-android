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

    @Query("DELETE FROM foreground_launch_events WHERE useDayId < :currentUseDayId")
    suspend fun deleteBeforeUseDay(currentUseDayId: String): Int
}
