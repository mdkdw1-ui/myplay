package com.example.myplayer

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtistExtractor {

    private const val TAG = "ArtistExtractor"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val PLAYER = "https://www.youtube.com/youtubei/v1/player"

    suspend fun extract(videoId: String, title: String, channel: String): String =
        withContext(Dispatchers.IO) {
            try {
                val api = fetchPlayerApi(videoId)
                if (api.isNotBlank()) {
                    Log.d(TAG, "API: $api")
                    return@withContext api
                }
            } catch (e: Exception) {
                Log.e(TAG, "player err", e)
            }

            val fromTitle = parseFromTitle(title)
            if (fromTitle.isNotBlank()) {
                Log.d(TAG, "Title: $fromTitle")
                return@withContext fromTitle
            }

            val fromChannel = cleanChannel(channel)
            Log.d(TAG, "Channel: $fromChannel")
            fromChannel
        }

    private fun fetchPlayerApi(videoId: String): String {
        return try {
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
            val conn = URL("$PLAYER?key=$API_KEY&prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) return ""
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            json.optJSONObject("videoDetails")?.let { vd ->
                val musicArtist = vd.optString("musicArtistName")
                if (musicArtist.isNotBlank()) return musicArtist
            }

            json.optJSONObject("microformat")
                ?.optJSONObject("microformatDataRenderer")
                ?.optJSONArray("tags")?.let { tags ->
                    for (i in 0 until tags.length()) {
                        val tag = tags.optString(i)
                        if (tag.endsWith(" - Topic")) {
                            return tag.removeSuffix(" - Topic").trim()
                        }
                    }
                }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseFromTitle(title: String): String {
        if (title.isBlank()) return ""
        var t = title.trim()

        t = t.replace(Regex("^\\[.*?\\]\\s*"), "")
        t = t.replace(Regex("^\\(.*?\\)\\s*"), "")
        t = t.replace(Regex("^【.*?】\\s*"), "")
        t = t.replace(Regex("(?i)^MV\\s*[:\\-]\\s*"), "")
        t = t.trim()

        val dashMatch = Regex("^([^-–—]+?)\\s*[-–—]\\s*.+$").find(t)
        if (dashMatch != null) {
            var artist = dashMatch.groupValues[1].trim()
            artist = artist.split(Regex("(?i)\\s+(feat\\.?|ft\\.?|with)\\s+"))[0].trim()
            artist = artist.replace(Regex("\\([^)]*\\)"), "").trim()
            artist = artist.replace(Regex("\\[[^\\]]*\\]"), "").trim()
            if (artist.length in 1..50 && artist.isNotBlank()) {
                return artist
            }
        }
        return ""
    }

    private fun cleanChannel(channel: String): String {
        if (channel.isBlank()) return ""
        var c = channel
        val suffixes = listOf(
            " - Topic", " Official", " OFFICIAL", " VEVO",
            " Music", " MUSIC", " Official Channel", " Official YouTube Channel"
        )
        for (s in suffixes) {
            if (c.endsWith(s)) c = c.removeSuffix(s).trim()
        }
        return c
    }
}
