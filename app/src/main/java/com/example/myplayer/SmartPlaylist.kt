package com.example.myplayer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmartPlaylist(
    val name: String,
    val emoji: String,
    val videos: List<VideoItem>
)

object SmartPlaylistGenerator {

    private const val TAG = "SmartPlaylist"

    /**
     * 시청 기록 기반 자동 플레이리스트 생성
     * - 로컬 휴리스틱 (LLM 없이 빠르게)
     */
    suspend fun generate(history: List<HistoryEntity>): List<SmartPlaylist> =
        withContext(Dispatchers.IO) {
            if (history.isEmpty()) return@withContext emptyList()

            val out = mutableListOf<SmartPlaylist>()

            // 1) 자주 본 채널 (Top 3)
            val byChannel = history
                .filter { it.channel.isNotBlank() }
                .groupBy { it.channel }
                .mapValues { it.value.size }
                .entries.sortedByDescending { it.value }
                .take(3)

            for ((ch, cnt) in byChannel) {
                val videos = history.filter { it.channel == ch }
                    .map {
                        VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
                    }
                    .distinctBy { it.videoId }
                if (videos.size >= 2) {
                    out.add(SmartPlaylist("자주 본: $ch", "⭐", videos.take(20)))
                }
            }

            // 2) 짧은 영상 (10분 이하)
            val shortVideos = history.filter {
                val d = it.durationMs
                d in 1..600_000
            }.map {
                VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
            }.distinctBy { it.videoId }

            if (shortVideos.size >= 3) {
                out.add(SmartPlaylist("짧은 영상 모음", "⚡", shortVideos.take(20)))
            }

            // 3) 미완주 영상 (중간에 끊은 것)
            val unfinished = history.filter {
                it.durationMs > 0 && it.positionMs > 30_000 && it.positionMs < it.durationMs - 60_000
            }.map {
                VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
            }.distinctBy { it.videoId }

            if (unfinished.size >= 2) {
                out.add(SmartPlaylist("이어볼 영상", "⏸", unfinished.take(20)))
            }

            // 4) 최근 7일 (시간순)
            val weekMs = 7L * 24 * 3600 * 1000
            val recent = history
                .filter { System.currentTimeMillis() - it.watchedAt < weekMs }
                .sortedByDescending { it.watchedAt }
                .map {
                    VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
                }
                .distinctBy { it.videoId }

            if (recent.size >= 3) {
                out.add(SmartPlaylist("최근 1주일", "🆕", recent.take(20)))
            }

            // 5) 완주한 영상
            val completed = history.filter {
                it.durationMs > 0 && it.positionMs >= it.durationMs - 30_000
            }.map {
                VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
            }.distinctBy { it.videoId }

            if (completed.size >= 3) {
                out.add(SmartPlaylist("끝까지 본 영상", "✅", completed.take(20)))
            }

            out
        }
}
