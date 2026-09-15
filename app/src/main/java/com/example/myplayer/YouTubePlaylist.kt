package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubePlaylist {

    private const val TAG = "YouTubePlaylist"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val BROWSE = "https://www.youtube.com/youtubei/v1/browse"

    data class PlaylistPage(
        val title: String,
        val videos: List<VideoItem>,
        val continuation: String?
    )

    suspend fun fetch(playlistId: String): PlaylistPage = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        var title = ""
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
                put("browseId", "VL$playlistId")
            }
            val response = post(BROWSE, body.toString())
            if (response != null) {
                val json = JSONObject(response)
                title = json.optJSONObject("metadata")
                    ?.optJSONObject("playlistMetadataRenderer")
                    ?.optString("title") ?: ""
                collect(json, videos)
                cont = findContinuation(json)
            }
        } catch (e: Exception) { Log.e(TAG, "err: ${e.message}", e) }
        PlaylistPage(title, videos, cont)
    }

    suspend fun fetchMore(continuation: String): PlaylistPage = withContext(Dispatchers.IO) {
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
            val response = post(BROWSE, body.toString())
                ?: return@withContext PlaylistPage("", emptyList(), null)
            val json = JSONObject(response)
            collect(json, videos)
            cont = findContinuation(json)
        } catch (e: Exception) { }
        PlaylistPage("", videos, cont)
    }

    private fun post(urlStr: String, bodyStr: String): String? {
        val url = URL("$urlStr?key=$API_KEY&prettyPrint=false")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Origin", "https://www.youtube.com")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        if (conn.responseCode !in 200..299) return null
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun collect(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistVideoRenderer")?.let { pvr ->
                    parsePlaylistVideo(pvr)?.let { v -> out.add(v) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "playlistVideoRenderer") continue
                    collect(node.opt(k), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun parsePlaylistVideo(v: JSONObject): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = v.optJSONObject("title")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text") ?: ""
        val channel = v.optJSONObject("shortBylineText")?.optJSONArray("runs")
            ?.optJSONObject(0)?.optString("text") ?: ""
        val thumbnail = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = v.optJSONObject("lengthText")?.optString("simpleText") ?: ""
        return VideoItem(videoId, title, channel, thumbnail, duration, "", "")
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")?.optString("token")
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

    fun extractPlaylistId(url: String): String? {
        val m = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)
        return m?.groupValues?.get(1)
    }
}
