package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeArtist {

    private const val TAG = "YouTubeArtist"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search"

    suspend fun fetchSongs(artistName: String, excludeVideoId: String = ""): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<VideoItem>()
            if (artistName.isBlank()) return@withContext emptyList()

            try {
                val queries = listOf("$artistName 노래", "$artistName 곡", artistName)
                for (q in queries) {
                    if (out.size >= 30) break
                    val list = search(q)
                    for (v in list) {
                        if (v.videoId == excludeVideoId) continue
                        if (out.any { it.videoId == v.videoId }) continue
                        out.add(v)
                    }
                }
                Log.d(TAG, "artist '$artistName' -> ${out.size}")
            } catch (e: Exception) {
                Log.e(TAG, "err: ${e.message}", e)
            }
            out.take(30)
        }

    private suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoItem>()
        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("query", query)
                put("params", "EgIQAQ%3D%3D")
            }
            val conn = URL("$ENDPOINT?key=$API_KEY&prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            collect(json, out)
        } catch (e: Exception) {
            Log.e(TAG, "search err: ${e.message}", e)
        }
        out
    }

    private fun collect(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parse(it, out) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "videoRenderer") continue
                    collect(node.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun isLiveOrUpcoming(v: JSONObject): Boolean {
        val badges = v.optJSONArray("badges")
        if (badges != null) {
            for (i in 0 until badges.length()) {
                val b = badges.optJSONObject(i)?.optJSONObject("metadataBadgeRenderer") ?: continue
                val label = b.optString("label").lowercase()
                if (label.contains("live") || label.contains("upcoming") ||
                    label.contains("예정") || label.contains("실시간")) return true
            }
        }
        val overlays = v.optJSONArray("thumbnailOverlays")
        if (overlays != null) {
            for (i in 0 until overlays.length()) {
                val o = overlays.optJSONObject(i) ?: continue
                val ts = o.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
                val style = ts.optString("style")
                if (style == "LIVE" || style == "UPCOMING") return true
            }
        }
        return false
    }

    private fun parseDurationSec(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun parse(v: JSONObject, out: MutableList<VideoItem>) {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return
        if (isLiveOrUpcoming(v)) return

        val title = extractText(v.optJSONObject("title"))
        val channel = extractText(v.optJSONObject("ownerText"))
            .ifBlank { extractText(v.optJSONObject("longBylineText")) }
        val thumb = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(v.optJSONObject("lengthText"))

        val sec = parseDurationSec(duration)
        if (sec in 1..29) return

        out.add(VideoItem(videoId, title, channel, thumb, duration, "", ""))
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
    }
}
