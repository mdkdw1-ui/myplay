package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val watchedAt: Long,
    val positionMs: Long = 0L,   // ★ 마지막 재생 위치
    val durationMs: Long = 0L    // ★ 영상 총 길이
) {
    val progressPercent: Int
        get() = if (durationMs <= 0) 0
        else ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)
}
