package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_playlist_items")
data class SavedPlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val position: Int
)
