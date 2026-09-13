package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val duration: String = ""
)

data class SearchPage(
    val videos: List<VideoItem>,
    val continuation: String?
)

object YouTubeSearch {

    private const val TAG = "YouTubeSearch"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search"

    suspend fun search(query: String): List<VideoItem> = searchPage(query).videos

    suspend fun searchPage(query: String): SearchPage = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        var cont: String? = null
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
            }

            val response = post(ENDPOINT, body.toString()) ?: return@withContext SearchPage(emptyList(), null)
            val json = JSONObject(response)
            collectVideos(json, videos)
            cont = findContinuation(json)
            Log.d(TAG, "search '$query' -> ${videos.size}, cont=${cont != null}")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        SearchPage(videos, cont)
    }

    suspend fun searchMore(continuation: String): SearchPage = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        var cont: String? = null
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
                put("continuation", continuation)
            }

            val response = post(ENDPOINT, body.toString()) ?: return@withContext SearchPage(emptyList(), null)
            val json = JSONObject(response)
            collectVideos(json, videos)
            cont = findContinuation(json)
            Log.d(TAG, "more -> ${videos.size}, cont=${cont != null}")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        SearchPage(videos, cont)
    }

    private fun post(urlStr: String, bodyStr: String): String? {
        val url = URL("$urlStr?key=$API_KEY&prettyPrint=false")
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
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) return null
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out)
        }
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrEmpty()) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = findContinuation(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = findContinuation(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun parseVideo(v: JSONObject): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = v.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: v.optJSONObject("title")?.optString("simpleText") ?: ""
        val channel = v.optJSONObject("ownerText")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: v.optJSONObject("longBylineText")
                ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
        val thumbnail = v.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = v.optJSONObject("lengthText")?.optString("simpleText") ?: ""
        return VideoItem(videoId, title, channel, thumbnail, duration)
    }
}
