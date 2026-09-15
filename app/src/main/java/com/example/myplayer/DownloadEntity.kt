package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val filePath: String,
    val sizeBytes: Long,
    val downloadedAt: Long
)
