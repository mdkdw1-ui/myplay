package com.example.myplayer

import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeTranscript {

    private const val TAG = "YouTubeTranscript"

    suspend fun fetchText(vttUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val fixedUrl = ensureVtt(vttUrl)
            val conn = URL(fixedUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return@withContext ""

            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parseVtt(raw)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            ""
        }
    }

    private fun ensureVtt(url: String): String =
        if (url.contains("fmt=")) url.replace(Regex("fmt=[a-zA-Z0-9]+"), "fmt=vtt")
        else if (url.contains("?")) "$url&fmt=vtt" else "$url?fmt=vtt"

    private fun parseVtt(raw: String): String {
        val lines = raw.split("\n")
        val out = StringBuilder()
        val seen = HashSet<String>()

        var i = 0
        while (i < lines.size && !lines[i].contains("-->")) i++

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
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
                if (text.isNotEmpty() && seen.add(text)) {
                    out.append(text).append(" ")
                }
            } else {
                i++
            }
        }

        return cleanup(out.toString())
    }

    private fun cleanup(text: String): String {
        val words = text.split(" ")
        val result = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            var count = 1
            while (i + count < words.size && words[i + count] == w) count++
            result.add(w)
            i += count
        }
        return result.joinToString(" ").replace(Regex("\\s+"), " ").trim()
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
