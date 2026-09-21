package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_playlists")
data class SavedPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val itemCount: Int
)
