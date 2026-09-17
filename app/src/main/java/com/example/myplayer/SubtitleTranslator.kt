package com.example.myplayer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube 영어 자막 → Groq AI 번역 → 로컬 VTT
 * (YouTube 429 우회)
 */
object SubtitleTranslator {

    private const val TAG = "SubtitleTranslator"

    /** 번역 캐시 폴더 */
    fun cacheDir(ctx: Context): File = File(ctx.filesDir, "subs").apply { mkdirs() }

    /**
     * VTT 한 줄씩 파싱해서 문장 배열로 반환
     */
    private data class Cue(
        val start: String,
        val end: String,
        val text: String
    )

    private fun parseVtt(raw: String): List<Cue> {
        val out = mutableListOf<Cue>()
        val lines = raw.split("\n")
        var i = 0
        while (i < lines.size && !lines[i].contains("-->")) i++
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val parts = line.split("-->").map { it.trim() }
                val start = parts.getOrNull(0) ?: ""
                val end = (parts.getOrNull(1) ?: "").substringBefore(" ")
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
                if (sb.isNotEmpty() && start.isNotEmpty()) {
                    out.add(Cue(start, end, sb.toString()))
                }
            } else {
                i++
            }
        }
        return out
    }

    /**
     * VTT 다운로드
     */
    private suspend fun downloadVtt(url: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return@withContext ""
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (e: Exception) {
            Log.e(TAG, "download err: ${e.message}", e)
            ""
        }
    }

    /**
     * Groq로 문장 묶음 번역
     */
    private suspend fun translateBatch(texts: List<String>, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            try {
                val numbered = texts.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                val prompt = """
다음 영어 문장들을 자연스러운 한국어로 번역해줘.

**규칙:**
1. 번호 순서를 그대로 유지
2. 각 줄은 "번호. 번역문" 형식으로
3. 설명 없이 번역만
4. 문장은 자연스럽게 (직역 X)

$numbered
""".trimIndent()

                val body = JSONObject().apply {
                    put("model", "openai/gpt-oss-120b")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a professional Korean translator.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                    put("temperature", 0.3)
                    put("max_tokens", 4000)
                }

                val conn = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 40000
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                if (conn.responseCode !in 200..299) {
                    Log.e(TAG, "Groq HTTP ${conn.responseCode}")
                    return@withContext texts
                }

                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(response)
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content") ?: ""

                // 파싱: "1. 번역\n2. 번역..."
                val result = mutableListOf<String>()
                val lines = content.split("\n").filter { it.isNotBlank() }
                for (line in lines) {
                    val m = Regex("^\\s*\\d+[.)]\\s*(.+)$").find(line.trim())
                    if (m != null) {
                        result.add(m.groupValues[1].trim())
                    } else {
                        result.add(line.trim())
                    }
                }

                // 개수 맞추기
                while (result.size < texts.size) result.add("")
                result.take(texts.size)
            } catch (e: Exception) {
                Log.e(TAG, "translate err: ${e.message}", e)
                texts
            }
        }

    /**
     * 메인: VTT URL → 번역된 로컬 VTT 경로
     */
    suspend fun translateToVtt(
        ctx: Context,
        videoId: String,
        vttUrl: String,
        targetLang: String = "ko"
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(cacheDir(ctx), "${videoId}_${targetLang}.vtt")
            if (cacheFile.exists() && cacheFile.length() > 100) {
                Log.d(TAG, "cache hit: ${cacheFile.absolutePath}")
                return@withContext cacheFile
            }

            // 1. 영어 VTT 다운로드
            val raw = downloadVtt(ensureVtt(vttUrl))
            if (raw.isBlank()) return@withContext null

            // 2. 파싱
            val cues = parseVtt(raw)
            if (cues.isEmpty()) return@withContext null

            // 3. Groq API 키
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) {
                Log.e(TAG, "no GROQ_API_KEY")
                return@withContext null
            }

            // 4. 배치 번역 (한 번에 30줄)
            val translated = mutableListOf<String>()
            val batchSize = 30
            for (i in cues.indices step batchSize) {
                val batch = cues.subList(i, minOf(i + batchSize, cues.size))
                val texts = batch.map { it.text }
                val results = translateBatch(texts, apiKey)
                translated.addAll(results)
            }

            // 5. VTT 생성
            val sb = StringBuilder()
            sb.append("WEBVTT\n\n")
            for (i in cues.indices) {
                val cue = cues[i]
                val ko = translated.getOrNull(i) ?: cue.text
                sb.append("${cue.start} --> ${cue.end}\n")
                sb.append("$ko\n\n")
            }

            cacheFile.writeText(sb.toString())
            Log.d(TAG, "saved: ${cacheFile.absolutePath} (${cues.size} cues)")
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            null
        }
    }

    private fun ensureVtt(url: String): String =
        if (url.contains("fmt=")) url.replace(Regex("fmt=[a-zA-Z0-9]+"), "fmt=vtt")
        else if (url.contains("?")) "$url&fmt=vtt" else "$url?fmt=vtt"

    /** 캐시 삭제 */
    fun clearCache(ctx: Context) {
        cacheDir(ctx).listFiles()?.forEach { it.delete() }
    }
}
