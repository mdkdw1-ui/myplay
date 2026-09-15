package com.example.myplayer

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_notes",
    indices = [Index(value = ["videoId"])]
)
data class VideoNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val videoTitle: String,
    val timestampMs: Long,
    val note: String,
    val createdAt: Long
)
