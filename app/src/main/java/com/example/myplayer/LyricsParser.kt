package com.example.myplayer

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class LyricLine(val startMs: Long, val text: String)

object LyricsParser {

    private const val TAG = "LyricsParser"

    suspend fun fetchLyrics(vttUrl: String): List<LyricLine> {
        if (vttUrl.isBlank()) {
            Log.d(TAG, "empty url")
            return emptyList()
        }
        return try {
            val fixed = ensureVtt(vttUrl)
            Log.d(TAG, "fetch: ${fixed.take(120)}")

            val url = URL(fixed)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code, len=${conn.contentLengthLong}")

            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "err body: ${err?.take(200)}")
                return emptyList()
            }

            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            Log.d(TAG, "raw len=${raw.length}, head=${raw.take(100)}")

            val parsed = parse(raw)
            Log.d(TAG, "parsed ${parsed.size} lines")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            emptyList()
        }
    }

    private fun ensureVtt(url: String): String =
        if (url.contains("fmt=")) url.replace(Regex("fmt=[a-zA-Z0-9]+"), "fmt=vtt")
        else if (url.contains("?")) "$url&fmt=vtt" else "$url?fmt=vtt"

    private fun parse(raw: String): List<LyricLine> {
        // JSON 응답 (YouTube XML→JSON 형식) 감지
        if (raw.trimStart().startsWith("{")) {
            return parseJsonTimedText(raw)
        }

        val out = mutableListOf<LyricLine>()
        val lines = raw.split("\n")
        var i = 0
        while (i < lines.size && !lines[i].contains("-->")) i++
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val start = line.split("-->")[0].trim()
                val ms = parseTime(start)
                i++
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val t = lines[i].trim().replace(Regex("<[^>]+>"), "")
                    if (t.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(t)
                    }
                    i++
                }
                if (sb.isNotBlank() && ms >= 0) {
                    out.add(LyricLine(ms, sb.toString()))
                }
            } else i++
        }
        return out.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /** JSON 형식 timedtext 파싱 */
    private fun parseJsonTimedText(raw: String): List<LyricLine> {
        return try {
            val json = org.json.JSONObject(raw)
            val events = json.optJSONArray("events") ?: return emptyList()
            val out = mutableListOf<LyricLine>()
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val startMs = e.optLong("tStartMs", -1L)
                val segs = e.optJSONArray("segs") ?: continue
                val sb = StringBuilder()
                for (j in 0 until segs.length()) {
                    sb.append(segs.getJSONObject(j).optString("utf8"))
                }
                val text = sb.toString().trim()
                if (text.isNotBlank() && startMs >= 0) {
                    out.add(LyricLine(startMs, text))
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "json parse err: ${e.message}")
            emptyList()
        }
    }

    private fun parseTime(ts: String): Long {
        return try {
            val clean = ts.substringBefore(" ")
            val parts = clean.split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val sp = parts[2].split(".")
                    val s = sp[0].toLong()
                    val ms = sp.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
                    (h * 3600 + m * 60 + s) * 1000 + ms
                }
                2 -> {
                    val m = parts[0].toLong()
                    val sp = parts[1].split(".")
                    val s = sp[0].toLong()
                    val ms = sp.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0
                    (m * 60 + s) * 1000 + ms
                }
                else -> -1
            }
        } catch (e: Exception) { -1 }
    }
}
