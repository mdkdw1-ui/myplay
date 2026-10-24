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
        if (title.isBlank()) return@withContext null

        // 제목 정리 (feat., MV, [가사] 등 제거)
        val cleanTitle = cleanTitle(title)
        val cleanArtist = cleanArtist(artist)
        Log.d(TAG, "fetch: artist='$cleanArtist', title='$cleanTitle'")

        // ★★★ 1차: artist + title (가장 정확)
        if (cleanArtist.isNotBlank()) {
            tryFetch(cleanArtist, cleanTitle)?.let { return@withContext it }
        }

        // ★★★ 2차: artist 없이 title만 (YouTube 채널명이 가수와 다를 때)
        tryFetch("", cleanTitle)?.let { return@withContext it }

        // ★★★ 3차: title에서 artist 파싱 시도
        val parsedArtist = parseArtistFromTitle(title)
        if (parsedArtist.isNotBlank() && parsedArtist != cleanArtist) {
            Log.d(TAG, "title에서 artist 파싱: '$parsedArtist'")
            tryFetch(parsedArtist, cleanTitle)?.let { return@withContext it }
        }

        // ★★★ 4차: suggest API (가수명 유사 검색)
        try {
            val url = "https://api.lyrics.ovh/suggest/" + URLEncoder.encode("$cleanArtist $cleanTitle", "UTF-8")
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

    /** ★ 단순 lyrics.ovh 호출 */
    private suspend fun tryFetch(artist: String, title: String): String? {
        return try {
            val url = if (artist.isBlank()) {
                "https://api.lyrics.ovh/v1/" + URLEncoder.encode(title, "UTF-8")
            } else {
                "https://api.lyrics.ovh/v1/" +
                    URLEncoder.encode(artist, "UTF-8") + "/" +
                    URLEncoder.encode(title, "UTF-8")
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MyPlayer/1.0")
            val code = conn.responseCode
            Log.d(TAG, "tryFetch artist='$artist' title='$title' → HTTP $code")
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(resp)
                val lyrics = json.optString("lyrics", "").trim()
                if (lyrics.isNotBlank()) {
                    Log.d(TAG, "✅ found ${lyrics.length} chars")
                    return lyrics
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "tryFetch err: ${e.message}")
            null
        }
    }

    /** ★ artist 정리 (YouTube 채널 접미사 제거) */
    private fun cleanArtist(artist: String): String {
        if (artist.isBlank()) return ""
        var a = artist.trim()
        val suffixes = listOf(
            " - Topic", " - Official", "Official", "OFFICIAL",
            " Music", " MUSIC", " VEVO", "VEVO",
            " (Official)", "(Official)", " Channel", " official",
            " - 주제"
        )
        for (s in suffixes) {
            if (a.endsWith(s, ignoreCase = true)) a = a.dropLast(s.length).trim()
        }
        // ★ YouTube 채널 패턴 제거 (예: "1theK (원더케이)")
        a = a.replace(Regex("\\(.*?\\)"), "").trim()
        a = a.replace(Regex("\\[.*?\\]"), "").trim()
        return a
    }

    /** ★ title에서 "artist - title" 파싱 */
    private fun parseArtistFromTitle(title: String): String {
        if (title.isBlank()) return ""
        var t = title.trim()

        // 대괄호/소괄호 앞부분 제거
        t = t.replace(Regex("^\\[.*?\\]\\s*"), "")
        t = t.replace(Regex("^\\(.*?\\)\\s*"), "")

        // "artist - song" 패턴
        val dash = t.split(Regex("\\s*[-–—]\\s*"))
        if (dash.size >= 2) {
            val artist = dash[0].trim()
                .split(Regex("(?i)\\s+(feat\\.?|ft\\.?|with)\\s+"))[0]
                .trim()
            if (artist.length in 2..40) return artist
        }
        return ""
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
