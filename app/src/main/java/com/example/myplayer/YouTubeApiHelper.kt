package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTubeApiHelper {
    private const val TAG = "YtApi"

    val INVIDIOUS = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://iv.melmac.space",
        "https://invidious.f5.si",
        "https://invidious.privacyredirect.com",
        "https://yt.artemislena.eu"
    )

    val PIPED = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaweso.me"
    )

    // ═══════════════════════════════════════════════
    // HTTP GET (JSON)
    // ═══════════════════════════════════════════════
    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "application/json")
            CookieUtil.apply(conn)
            if (conn.responseCode !in 200..299) {
                Log.d(TAG, "HTTP ${conn.responseCode} $url")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            JSONObject(text)
        } catch (e: Exception) {
            Log.d(TAG, "err: ${e.message?.take(80)}")
            null
        }
    }

    // ═══════════════════════════════════════════════
    // 검색 (Invidious → Piped)
    // ═══════════════════════════════════════════════
    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")

        // Invidious
        for (inst in INVIDIOUS) {
            val json = getJson("$inst/api/v1/search?q=$q&type=video") ?: continue
            try {
                val out = mutableListOf<VideoItem>()
                for (i in 0 until json.length()) {
                    val v = json.optJSONObject(i) ?: continue
                    val id = v.optString("videoId", "")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = v.optString("videoThumbnails", "").ifBlank {
                            v.optString("thumbnail", "")
                        },
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) {
                    Log.d(TAG, "inv search: ${out.size}개")
                    return@withContext out
                }
            } catch (e: Exception) { }
        }

        // Piped
        for (inst in PIPED) {
            val json = getJson("$inst/search?q=$q&filter=videos") ?: continue
            try {
                val items = json.optJSONArray("items") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until items.length()) {
                    val v = items.optJSONObject(i) ?: continue
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = v.optString("uploadedDate", "")
                    ))
                }
                if (out.isNotEmpty()) {
                    Log.d(TAG, "piped search: ${out.size}개")
                    return@withContext out
                }
            } catch (e: Exception) { }
        }

        emptyList()
    }

    // ═══════════════════════════════════════════════
    // 채널 검색 (Invidious → Piped)
    // ═══════════════════════════════════════════════
    suspend fun searchChannels(query: String): List<ChannelItem> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")

        for (inst in INVIDIOUS) {
            val json = getJson("$inst/api/v1/search?q=$q&type=channel") ?: continue
            try {
                val out = mutableListOf<ChannelItem>()
                for (i in 0 until json.length()) {
                    val v = json.optJSONObject(i) ?: continue
                    val id = v.optString("authorId", "")
                    if (id.isBlank()) continue
                    out.add(ChannelItem(
                        channelId = id,
                        name = v.optString("author", ""),
                        thumbnail = v.optString("authorThumbnails", ""),
                        subscribers = fmtNum(v.optLong("subCount", 0)) + "명"
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        for (inst in PIPED) {
            val json = getJson("$inst/search?q=$q&filter=channels") ?: continue
            try {
                val items = json.optJSONArray("items") ?: continue
                val out = mutableListOf<ChannelItem>()
                for (i in 0 until items.length()) {
                    val v = items.optJSONObject(i) ?: continue
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/channel/", "")
                    if (id.isBlank()) continue
                    out.add(ChannelItem(
                        channelId = id,
                        name = v.optString("name", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        subscribers = v.optString("subscribers", "")
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        emptyList()
    }

    // ═══════════════════════════════════════════════
    // 채널 영상 (Invidious → Piped)
    // ═══════════════════════════════════════════════
    suspend fun channelVideos(channelId: String): ChannelVideosPage = withContext(Dispatchers.IO) {
        // Invidious
        for (inst in INVIDIOUS) {
            val json = getJson("$inst/api/v1/channels/$channelId/videos?page=1") ?: continue
            try {
                val videos = json.optJSONArray("videos") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    out.add(VideoItem(
                        videoId = v.optString("videoId", ""),
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = v.optString("videoThumbnails", ""),
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) {
                    return@withContext ChannelVideosPage(out, null)
                }
            } catch (e: Exception) { }
        }

        // Piped
        for (inst in PIPED) {
            val json = getJson("$inst/channel/$channelId") ?: continue
            try {
                val rel = json.optJSONArray("relatedStreams") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rel.length()) {
                    val v = rel.getJSONObject(i)
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) return@withContext ChannelVideosPage(out, null)
            } catch (e: Exception) { }
        }

        ChannelVideosPage(emptyList(), null)
    }

    // ═══════════════════════════════════════════════
    // 관련 영상 (Invidious recommendedVideos)
    // ═══════════════════════════════════════════════
    suspend fun related(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        for (inst in INVIDIOUS) {
            val json = getJson("$inst/api/v1/videos/$videoId") ?: continue
            try {
                val rec = json.optJSONArray("recommendedVideos") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rec.length()) {
                    val v = rec.getJSONObject(i)
                    out.add(VideoItem(
                        videoId = v.optString("videoId", ""),
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = v.optString("videoThumbnails", ""),
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        // Piped relatedStreams
        for (inst in PIPED) {
            val json = getJson("$inst/streams/$videoId") ?: continue
            try {
                val rel = json.optJSONArray("relatedStreams") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rel.length()) {
                    val v = rel.getJSONObject(i)
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        emptyList()
    }

    // ═══════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════
    fun fmtSec(s: Int): String {
        if (s <= 0) return ""
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }

    fun fmtViews(v: Long): String {
        return when {
            v >= 100_000_000 -> "%.1f억회".format(v / 100_000_000.0)
            v >= 10_000 -> "%.1f만회".format(v / 10_000.0)
            v >= 1_000 -> "%.1f천회".format(v / 1_000.0)
            v > 0 -> "${v}회"
            else -> ""
        }
    }

    fun fmtNum(n: Long): String {
        return when {
            n >= 100_000_000 -> "%.1f억".format(n / 100_000_000.0)
            n >= 10_000 -> "%.1f만".format(n / 10_000.0)
            n >= 1_000 -> "%.1f천".format(n / 1_000.0)
            else -> "$n"
        }
    }
}
