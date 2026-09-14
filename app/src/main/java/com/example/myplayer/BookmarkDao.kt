package com.example.myplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BookmarkEntity)

    @Query("SELECT * FROM bookmarks ORDER BY savedAt DESC")
    fun getAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE videoId = :videoId)")
    suspend fun isBookmarked(videoId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE videoId = :videoId)")
    fun isBookmarkedFlow(videoId: String): Flow<Boolean>

    @Query("DELETE FROM bookmarks WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM bookmarks")
    suspend fun clearAll()
}
