package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val watchedAt: Long
)
