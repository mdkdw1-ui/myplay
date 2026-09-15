package com.example.myplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity)

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC")
    fun getAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): HistoryEntity?

    /** 재생 위치만 갱신 (기록은 유지) */
    @Query("UPDATE watch_history SET positionMs = :posMs, durationMs = :durMs WHERE videoId = :videoId")
    suspend fun updatePosition(videoId: String, posMs: Long, durMs: Long)


    @Query("SELECT * FROM watch_history WHERE title LIKE '%' || :q || '%' OR channel LIKE '%' || :q || '%' ORDER BY watchedAt DESC")
    fun search(q: String): Flow<List<HistoryEntity>>

    @Query("DELETE FROM watch_history")
    suspend fun clearAll()

    @Query("DELETE FROM watch_history WHERE videoId = :videoId")
    suspend fun deleteById(videoId: String)
}
