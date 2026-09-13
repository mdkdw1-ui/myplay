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

    // 마지막 호출 디버그용 (실패 시 확인 가능)
    var lastDebug: String = ""

    suspend fun fetch(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<VideoItem>()
        try {
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
                put("videoId", videoId)
            }

            val url = URL("$ENDPOINT?key=$API_KEY&prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip"
            )
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            lastDebug = "HTTP $code"
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) return@withContext emptyList()

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            Log.d(TAG, "resp len=${response.length}")

            val json = JSONObject(response)

            // 재귀 파싱 (compactVideoRenderer / videoRenderer / lockupViewModel)
            collect(json, results, videoId)

            // 중복 제거
            val seen = HashSet<String>()
            val unique = results.filter { seen.add(it.videoId) }

            lastDebug = "found ${unique.size} / raw ${results.size}"
            Log.d(TAG, lastDebug)
            unique
        } catch (e: Exception) {
            lastDebug = "ERR: ${e.message}"
            Log.e(TAG, "err: ${e.message}", e)
            emptyList()
        }
    }

    private fun collect(node: Any?, out: MutableList<VideoItem>, exclude: String) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("compactVideoRenderer")?.let {
                    parseVideo(it, exclude)?.let { v -> out.add(v) }
                }
                node.optJSONObject("videoRenderer")?.let {
                    parseVideo(it, exclude)?.let { v -> out.add(v) }
                }
                node.optJSONObject("lockupViewModel")?.let {
                    parseLockup(it, exclude)?.let { v -> out.add(v) }
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, exclude)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, exclude)
        }
    }

    private fun parseVideo(v: JSONObject, exclude: String): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        if (videoId == exclude) return null

        val title = v.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: v.optJSONObject("title")?.optString("simpleText") ?: ""

        val channel = v.optJSONObject("shortBylineText")
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

    private fun parseLockup(lv: JSONObject, exclude: String): VideoItem? {
        val videoId = lv.optString("contentId").takeIf { it.isNotEmpty() } ?: return null
        if (videoId == exclude) return null

        val meta = lv.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")
            ?: meta?.optJSONObject("title")?.optString("simpleText") ?: ""

        val channel = meta?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
            ?.optJSONObject(0)
            ?.optJSONArray("metadataParts")
            ?.optJSONObject(0)
            ?.optJSONObject("text")
            ?.optString("content") ?: ""

        val thumbnail = lv.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        return VideoItem(videoId, title, channel, thumbnail, "")
    }
}
