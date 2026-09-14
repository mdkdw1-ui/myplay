package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val savedAt: Long
)
