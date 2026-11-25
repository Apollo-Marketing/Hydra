package com.hydra.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterDao {

    @Query("SELECT * FROM filters WHERE removedAtSec IS NULL ORDER BY installedAtSec DESC LIMIT 1")
    fun observeActive(): Flow<FilterEntity?>

    @Query("SELECT * FROM filters WHERE removedAtSec IS NULL ORDER BY installedAtSec DESC LIMIT 1")
    suspend fun getActive(): FilterEntity?

    @Query("SELECT * FROM filter_pauses ORDER BY startSec ASC")
    fun observePauses(): Flow<List<FilterPauseEntity>>

    @Query("SELECT * FROM filter_pauses WHERE filterId = :filterId ORDER BY startSec ASC")
    suspend fun getPauses(filterId: Long): List<FilterPauseEntity>

    @Insert
    suspend fun insertFilter(filter: FilterEntity): Long

    @Insert
    suspend fun insertPause(pause: FilterPauseEntity): Long

    @Query("UPDATE filters SET removedAtSec = :atSec WHERE removedAtSec IS NULL")
    suspend fun endActiveFilters(atSec: Long)

    @Query("UPDATE filter_pauses SET endSec = :atSec WHERE endSec IS NULL")
    suspend fun closeOpenPauses(atSec: Long)

    @Query("SELECT COUNT(*) FROM filter_pauses WHERE filterId = :filterId AND endSec IS NULL")
    suspend fun openPauseCount(filterId: Long): Int
}
