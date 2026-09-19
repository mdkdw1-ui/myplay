package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 가수명 추출:
 * 1) YouTube 뮤직 메타데이터 (musicArtistName)
 * 2) 제목 파싱 ("가수 - 곡명")
 * 3) 채널명 (폴백)
 */
object ArtistExtractor {

    private const val TAG = "ArtistExtractor"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val PLAYER = "https://www.youtube.com/youtubei/v1/player"

    /**
     * 비디오 ID로 가수명 추출
     */
    suspend fun extractArtist(videoId: String, title: String, channel: String): String {
        // 1순위: YouTube player API 메타데이터
        try {
            val artist = fetchMusicArtist(videoId)
            if (artist.isNotBlank()) {
                Log.d(TAG, "music metadata: $artist")
                return artist
            }
        } catch (e: Exception) {
            Log.e(TAG, "player err: ${e.message}", e)
        }

        // 2순위: 제목 파싱
        val fromTitle = parseArtistFromTitle(title)
        if (fromTitle.isNotBlank()) {
            Log.d(TAG, "from title: $fromTitle")
            return fromTitle
        }

        // 3순위: 채널명 (fallback)
        Log.d(TAG, "fallback channel: $channel")
        return cleanChannelName(channel)
    }

    /** YouTube player API에서 musicArtistName 추출 */
    private fun fetchMusicArtist(videoId: String): String {
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
                put("videoId", videoId)
            }
            val conn = URL("$PLAYER?key=$API_KEY&prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) return ""
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            // 1) videoDetails.musicArtistName 또는 author
            json.optJSONObject("videoDetails")?.let { vd ->
                // musicArtistName (뮤직비디오인 경우)
                val musicArtist = vd.optString("musicArtistName")
                if (musicArtist.isNotBlank()) return musicArtist
            }

            // 2) microformat.microformatDataRenderer.tags
            json.optJSONObject("microformat")
                ?.optJSONObject("microformatDataRenderer")
                ?.optJSONArray("tags")?.let { tags ->
                    // 태그에 "Artist - Topic" 형식 있으면 추출
                    for (i in 0 until tags.length()) {
                        val tag = tags.optString(i)
                        if (tag.endsWith(" - Topic")) {
                            return tag.removeSuffix(" - Topic")
                        }
                    }
                }

            // 3) videoDetails.author (채널명)
            // 이건 3순위로 미룸

            return ""
        } catch (e: Exception) {
            Log.e(TAG, "fetchMusicArtist err: ${e.message}", e)
            return ""
        }
    }

    /**
     * 제목에서 가수 추출
     * 예: "서문탁 - 사랑, 결코 시들지 않는" → "서문탁"
     * 예: "아이유(IU) - Love wins all" → "아이유"
     * 예: "[MV] 서문탁 - 사랑..." → "서문탁"
     */
    private fun parseArtistFromTitle(title: String): String {
        if (title.isBlank()) return ""
        var t = title.trim()

        // 앞의 [MV], (Official), 【】 등 제거
        t = t.replace(Regex("^\\[.*?\\]\\s*"), "")
        t = t.replace(Regex("^\\(.*?\\)\\s*"), "")
        t = t.replace(Regex("^【.*?】\\s*"), "")
        t = t.trim()

        // "가수 - 곡명" 패턴 (하이픈 앞)
        val dashMatch = Regex("^([^-–—]+?)\\s*[-–—]\\s*.+$").find(t)
        if (dashMatch != null) {
            var artist = dashMatch.groupValues[1].trim()
            // "feat.", "ft." 등 제거
            artist = artist.split(Regex("(?i)\\s*(feat\\.?|ft\\.?|with)\\s+"))[0].trim()
            // 괄호 안 영문 제거 (아이유(IU) → 아이유)
            artist = artist.replace(Regex("\\([^)]*\\)"), "").trim()
            if (artist.length in 1..50 && artist.isNotBlank()) {
                return artist
            }
        }

        // "가수 - 곡명" 실패 → 빈 문자열
        return ""
    }

    /** 채널명 정리 (가수로 부적합한 접미사 제거) */
    private fun cleanChannelName(channel: String): String {
        if (channel.isBlank()) return ""
        var c = channel

        // "- Topic", "Official", "VEVO" 등 제거
        val suffixes = listOf(
            " - Topic",
            " Official",
            " OFFICIAL",
            " VEVO",
            " Music",
            " MUSIC",
            " Official Channel",
            " Official YouTube Channel"
        )
        for (suf in suffixes) {
            if (c.endsWith(suf)) {
                c = c.removeSuffix(suf).trim()
            }
        }

        return c
    }
}
