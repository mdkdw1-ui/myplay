package com.example.myplayer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {

    @Query("SELECT * FROM saved_playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<SavedPlaylistEntity>>

    @Query("SELECT * FROM saved_playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getItems(playlistId: Long): List<SavedPlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: SavedPlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: SavedPlaylistItemEntity): Long

    @Query("DELETE FROM saved_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM saved_playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteItems(playlistId: Long)

    @Query("UPDATE saved_playlists SET itemCount = :count WHERE id = :playlistId")
    suspend fun updateCount(playlistId: Long, count: Int)
}
