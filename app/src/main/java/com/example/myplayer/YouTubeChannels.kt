package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChannelItem(
    val channelId: String,
    val name: String,
    val thumbnail: String,
    val subscribers: String
)

object YouTubeChannels {

    private const val TAG = "YouTubeChannels"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search"
    var lastDebug: String = ""

    suspend fun search(query: String): List<ChannelItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ChannelItem>()
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
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) return@withContext emptyList()

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            collectChannels(json, results, query)
            lastDebug = "HTTP $code, found ${results.size} for '$query'"
            Log.d(TAG, lastDebug)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        results
    }

    private fun collectChannels(node: Any?, out: MutableList<ChannelItem>, baseName: String) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("channelRenderer")?.let { cr ->
                    parseChannelRenderer(cr)?.let { out.add(it) }
                }
                node.optJSONObject("videoRenderer")?.let { vr ->
                    parseFromVideo(vr)?.let { out.add(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectChannels(node.opt(keys.next()), out, baseName)
            }
            is JSONArray -> for (i in 0 until node.length()) collectChannels(node.opt(i), out, baseName)
        }
    }

    private fun parseChannelRenderer(c: JSONObject): ChannelItem? {
        val id = c.optString("channelId").takeIf { it.isNotEmpty() } ?: return null
        val name = extractText(c.optJSONObject("title")) ?: return null
        val thumb = c.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
        val subs = extractText(c.optJSONObject("videoCountText"))
            ?: extractText(c.optJSONObject("subscriberCountText")) ?: ""
        return ChannelItem(id, name, thumb, subs)
    }

    private fun parseFromVideo(v: JSONObject): ChannelItem? {
        val ownerText = v.optJSONObject("ownerText")
            ?: v.optJSONObject("longBylineText")
            ?: v.optJSONObject("shortBylineText")
            ?: return null

        val run = ownerText.optJSONArray("runs")?.optJSONObject(0) ?: return null
        val name = run.optString("text").takeIf { it.isNotEmpty() } ?: return null

        val channelId = run.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return ChannelItem(channelId, name, "", "")
    }

    private fun extractText(obj: JSONObject?): String? {
        if (obj == null) return null
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
    }
}
