package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

data class Chapter(
    val startMs: Long,
    var title: String
)

object YouTubeChapters {

    private const val YOUTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val YOUTUBE_NEXT_ENDPOINT = "https://www.youtube.com/youtubei/v1/next"

    suspend fun fetchAuto(videoId: String): List<Chapter> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() || videoId.startsWith("local:")) return@withContext emptyList()
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

            val url = URL("$YOUTUBE_NEXT_ENDPOINT?key=$YOUTUBE_API_KEY&prettyPrint=false")
            val conn = url.openConnection() as HttpURLConnection
            CookieUtil.apply(conn)
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

            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            val out = mutableListOf<Chapter>()
            collectAutoChapters(json, out)

            out.distinctBy { it.startMs }
                .sortedBy { it.startMs }
                .takeIf { it.size >= 2 }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun collectAutoChapters(node: Any?, out: MutableList<Chapter>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("macroMarkersListItemRenderer")?.let { item ->
                    val title = item.optJSONObject("title")
                        ?.optString("simpleText", "")
                        ?.trim()
                        .orEmpty()
                    val seconds = item.optJSONObject("onTap")
                        ?.optJSONObject("watchEndpoint")
                        ?.optDouble("startTimeSeconds", -1.0) ?: -1.0

                    if (title.isNotBlank() && seconds >= 0) {
                        out.add(Chapter((seconds * 1000).toLong(), title))
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    collectAutoChapters(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectAutoChapters(node.opt(i), out)
                }
            }
        }
    }

    // "00:00 제목" 또는 "0:00 제목" 또는 "1:23:45 제목"
    private val PATTERN = Pattern.compile(
        "^\\s*(?:[-*•·]\\s*)?\\[?((?:\\d{1,3}:)?\\d{1,2}:\\d{2})\\]?\\s*(?:[-–—:·|]\\s*)?(.+)$",
        Pattern.MULTILINE
    )

    fun parse(description: String): List<Chapter> {
        if (description.isBlank()) return emptyList()
        val out = mutableListOf<Chapter>()

        for (line in description.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.length < 5) continue
            val m = PATTERN.matcher(trimmed)
            if (!m.find()) continue

            val timePart = m.group(1)
            val ms = parseTimestamp(timePart) ?: continue
            val title = m.group(2).trim()
                .trimStart('-', '–', '—', ':', '·', '|', ' ')
                .trim()

            if (title.isNotBlank() && ms >= 0) {
                out.add(Chapter(ms, title))
            }
        }

        // 정렬 + 중복 제거 + 최소 2개 이상일 때만 유효
        val sorted = out.distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (sorted.size >= 2) sorted else emptyList()
    }

    private fun parseTimestamp(ts: String): Long? {
        val parts = ts.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> null
        }
    }
}
