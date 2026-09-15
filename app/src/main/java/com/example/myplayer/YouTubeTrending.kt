package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeTrending {
    private const val TAG = "YouTubeTrending"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val BROWSE = "https://www.youtube.com/youtubei/v1/browse"
    var lastDebug: String = ""

    suspend fun fetch(): List<VideoItem> = withContext(Dispatchers.IO) {
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
                put("browseId", "FEtrending")
            }
            val url = URL("$BROWSE?key=$API_KEY&prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) { lastDebug = "HTTP ${conn.responseCode}"; return@withContext emptyList() }
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            collect(json, out)
            lastDebug = "found ${out.size}"
        } catch (e: Exception) { lastDebug = "err: ${e.message}"; }
        out.distinctBy { it.videoId }.take(20)
    }

    private fun collect(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                node.optJSONObject("gridVideoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "videoRenderer" || k == "gridVideoRenderer") continue
                    collect(node.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
    }

    private fun parseVideo(v: JSONObject): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = extractText(v.optJSONObject("title"))
        val channel = extractText(v.optJSONObject("ownerText")).ifBlank { extractText(v.optJSONObject("longBylineText")) }.ifBlank { extractText(v.optJSONObject("shortBylineText")) }
        val thumbnail = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(v.optJSONObject("lengthText"))
        val viewCount = extractText(v.optJSONObject("viewCountText")).ifBlank { extractText(v.optJSONObject("shortViewCountText")) }
        val uploadDate = extractText(v.optJSONObject("publishedTimeText"))
        return VideoItem(videoId, title, channel, thumbnail, duration, viewCount, uploadDate)
    }
}
