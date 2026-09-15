package com.example.myplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VideoNoteEntity)

    @Query("SELECT * FROM video_notes WHERE videoId = :videoId ORDER BY timestampMs ASC")
    fun getForVideo(videoId: String): Flow<List<VideoNoteEntity>>

    @Query("SELECT * FROM video_notes ORDER BY createdAt DESC")
    fun getAll(): Flow<List<VideoNoteEntity>>

    @Query("DELETE FROM video_notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM video_notes WHERE videoId = :videoId")
    suspend fun deleteForVideo(videoId: String)
}
