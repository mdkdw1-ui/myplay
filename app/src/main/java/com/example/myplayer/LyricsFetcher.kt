package com.example.myplayer

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 가사 검색 (YouTube 자막 없을 때)
 * - lyrics.ovh API (무료)
 * - 가수명 + 곡명으로 검색
 */
object LyricsFetcher {

    private const val TAG = "LyricsFetcher"

    /** 가사 텍스트 반환 (실패 시 null) */
    suspend fun fetch(artist: String, title: String): String? = withContext(Dispatchers.IO) {
        if (artist.isBlank() || title.isBlank()) return@withContext null

        // 제목 정리 (feat., MV, [가사] 등 제거)
        val cleanTitle = cleanTitle(title)
        Log.d(TAG, "fetch: artist='$artist', title='$cleanTitle'")

        // 1차: lyrics.ovh
        try {
            val url = "https://api.lyrics.ovh/v1/" +
                    URLEncoder.encode(artist, "UTF-8") + "/" +
                    URLEncoder.encode(cleanTitle, "UTF-8")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MyPlayer/1.0")
            val code = conn.responseCode
            Log.d(TAG, "lyrics.ovh HTTP $code")
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(resp)
                val lyrics = json.optString("lyrics", "").trim()
                if (lyrics.isNotBlank()) {
                    Log.d(TAG, "found ${lyrics.length} chars")
                    return@withContext lyrics
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "lyrics.ovh err: ${e.message}", e)
        }

        // 2차: 제목만으로 시도 (artist 매칭 실패 대응)
        try {
            val url = "https://api.lyrics.ovh/suggest/" + URLEncoder.encode("$artist $cleanTitle", "UTF-8")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MyPlayer/1.0")
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(resp)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val first = data.getJSONObject(0)
                    val a = first.optJSONObject("artist")?.optString("name") ?: ""
                    val t = first.optString("title", "")
                    if (a.isNotBlank() && t.isNotBlank()) {
                        val url2 = "https://api.lyrics.ovh/v1/" +
                                URLEncoder.encode(a, "UTF-8") + "/" +
                                URLEncoder.encode(t, "UTF-8")
                        val conn2 = URL(url2).openConnection() as HttpURLConnection
                        conn2.setRequestProperty("User-Agent", "MyPlayer/1.0")
                        if (conn2.responseCode in 200..299) {
                            val resp2 = conn2.inputStream.bufferedReader().use(BufferedReader::readText)
                            val lyrics = JSONObject(resp2).optString("lyrics", "").trim()
                            if (lyrics.isNotBlank()) {
                                Log.d(TAG, "suggest fallback: ${lyrics.length} chars")
                                return@withContext lyrics
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "suggest err: ${e.message}", e)
        }

        null
    }

    private fun cleanTitle(title: String): String {
        var t = title
        // [가사/Lyrics], (Official), MV 등 제거
        t = t.replace(Regex("\\[[^\\]]*\\]"), "")
        t = t.replace(Regex("\\([^)]*\\)"), "")
        t = t.replace(Regex("(?i)\\b(MV|M/V|Official|Lyrics|가사|뮤직비디오)\\b"), "")
        // "가수 - 곡명" → "곡명"
        val dash = t.split("-", "–", "—")
        if (dash.size >= 2) t = dash.last()
        t = t.replace("...", "").replace("…", "")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
