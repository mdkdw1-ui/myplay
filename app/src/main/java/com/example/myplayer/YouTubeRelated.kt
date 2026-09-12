package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeRelated {

    private const val TAG = "YouTubeRelated"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/next"

    suspend fun fetch(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<VideoItem>()
        try {
            val url = URL("$ENDPOINT?key=$API_KEY&prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("videoId", videoId)
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code")
                return@withContext emptyList()
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            collectRelated(json, results, videoId)
            Log.d(TAG, "found ${results.size} related")
        } catch (e: Exception) {
            Log.e(TAG, "error: ${e.message}", e)
        }
        results
    }

    private fun collectRelated(node: Any?, out: MutableList<VideoItem>, excludeId: String) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("compactVideoRenderer")?.let { vr ->
                    parseCompact(vr, excludeId)?.let { out.add(it) }
                }
                node.optJSONObject("videoRenderer")?.let { vr ->
                    parseCompact(vr, excludeId)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectRelated(node.opt(keys.next()), out, excludeId)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectRelated(node.opt(i), out, excludeId)
                }
            }
        }
    }

    private fun parseCompact(v: JSONObject, excludeId: String): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        if (videoId == excludeId) return null

        val title = v.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: v.optJSONObject("title")?.optString("simpleText")
            ?: ""

        val channel = v.optJSONObject("shortBylineText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: v.optJSONObject("longBylineText")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
            ?: ""

        val thumbnail = v.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs -> thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        val duration = v.optJSONObject("lengthText")?.optString("simpleText") ?: ""

        return VideoItem(videoId, title, channel, thumbnail, duration)
    }
}
