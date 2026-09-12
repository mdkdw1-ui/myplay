package com.example.myplayer

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

object YouTubeSearch {

    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?key=$API_KEY"

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<VideoItem>()
        try {
            val url = URL(ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("androidSdkVersion", 30)
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("query", query)
                put("params", "EgIQAQ%3D%3D")
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) return@withContext emptyList()

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            val sections = json.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return@withContext emptyList()

            for (i in 0 until sections.length()) {
                val itemSection = sections.getJSONObject(i)
                    .optJSONObject("itemSectionRenderer") ?: continue
                val items = itemSection.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val videoRenderer = items.getJSONObject(j)
                        .optJSONObject("videoRenderer") ?: continue
                    parseVideo(videoRenderer)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        results
    }

    private fun parseVideo(v: JSONObject): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = v.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: "(제목 없음)"
        val channel = v.optJSONObject("ownerText")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""
        val thumbnail = v.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { thumbs -> thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = v.optJSONObject("lengthText")?.optString("simpleText") ?: ""
        return VideoItem(videoId, title, channel, thumbnail, duration)
    }
}
