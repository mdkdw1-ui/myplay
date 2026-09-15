package com.example.myplayer

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TranscriptLine(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object YouTubeTranscript {

    private const val TAG = "YouTubeTranscript"

    /** 텍스트만 (기존) */
    suspend fun fetchText(vttUrl: String): String {
        val lines = fetchLines(vttUrl)
        return lines.joinToString(" ") { it.text }
    }

    /** 타임스탬프 포함 라인 리스트 (신규) */
    suspend fun fetchLines(vttUrl: String): List<TranscriptLine> = withContext(Dispatchers.IO) {
        try {
            val fixedUrl = ensureVtt(vttUrl)
            val conn = URL(fixedUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return@withContext emptyList()

            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parseVtt(raw)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            emptyList()
        }
    }

    private fun ensureVtt(url: String): String =
        if (url.contains("fmt=")) url.replace(Regex("fmt=[a-zA-Z0-9]+"), "fmt=vtt")
        else if (url.contains("?")) "$url&fmt=vtt" else "$url?fmt=vtt"

    private fun parseVtt(raw: String): List<TranscriptLine> {
        val out = mutableListOf<TranscriptLine>()
        val lines = raw.split("\n")

        var i = 0
        while (i < lines.size && !lines[i].contains("-->")) i++

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                // 00:00:01.234 --> 00:00:04.567
                val parts = line.split("-->").map { it.trim() }
                val startMs = parseTimestamp(parts.getOrNull(0) ?: "")
                val endMs = parseTimestamp(parts.getOrNull(1) ?: "")

                i++
                val textBuf = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    var t = lines[i].trim()
                    t = t.replace(Regex("<[^>]+>"), "")
                    if (t.isNotBlank()) {
                        if (textBuf.isNotEmpty()) textBuf.append(" ")
                        textBuf.append(t)
                    }
                    i++
                }
                val text = textBuf.toString().trim()
                if (text.isNotEmpty() && startMs >= 0) {
                    out.add(TranscriptLine(startMs, endMs, text))
                }
            } else {
                i++
            }
        }

        // 중복 제거 (같은 시작 시간)
        return out.distinctBy { it.startMs }.sortedBy { it.startMs }
    }

    /** "00:01:23.456" 또는 "01:23.456" 형식 파싱 */
    private fun parseTimestamp(ts: String): Long {
        try {
            val clean = ts.substringBefore(" ")  // "--> 뒤 여분 제거"
            val parts = clean.split(":").map { it.trim() }
            return when (parts.size) {
                3 -> {
                    val h = parts[0].toLongOrNull() ?: 0
                    val m = parts[1].toLongOrNull() ?: 0
                    val sPart = parts[2].split(".")
                    val s = sPart.getOrNull(0)?.toLongOrNull() ?: 0
                    val ms = sPart.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
                    (h * 3600 + m * 60 + s) * 1000 + ms
                }
                2 -> {
                    val m = parts[0].toLongOrNull() ?: 0
                    val sPart = parts[1].split(".")
                    val s = sPart.getOrNull(0)?.toLongOrNull() ?: 0
                    val ms = sPart.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
                    (m * 60 + s) * 1000 + ms
                }
                else -> -1L
            }
        } catch (e: Exception) {
            return -1L
        }
    }

    fun summarize(text: String, maxChars: Int = 400): String {
        if (text.isBlank()) return ""
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return trimmed
        val sentences = trimmed.split(Regex("(?<=[.!?。？！])\\s+"))
        val sb = StringBuilder()
        for (s in sentences) {
            if (sb.length + s.length + 1 > maxChars) break
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(s)
        }
        val result = sb.toString().trim()
        return if (result.isEmpty()) trimmed.take(maxChars) + "..." else result + " ..."
    }
}
