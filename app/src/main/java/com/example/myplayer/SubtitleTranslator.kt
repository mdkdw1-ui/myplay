package com.example.myplayer

import android.content.Context
import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SubtitleTranslator {

    private const val TAG = "SubtitleTranslator"

    fun cacheDir(ctx: Context): File = File(ctx.filesDir, "subs").apply { mkdirs() }

    private data class Cue(
        val start: String,
        val end: String,
        val text: String
    )

    private fun stripHtml(input: String): String {
        if (input.isBlank()) return ""
        return try {
            val noHtml = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Html.fromHtml(input, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(input).toString()
            }
            noHtml.replace(Regex("\\s+"), " ").trim()
        } catch (e: Exception) {
            input.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }

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

    private suspend fun downloadVtt(url: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(ensureVtt(url)).openConnection() as HttpURLConnection
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
     * ★ 라우팅:
     * - 기본: Groq (모든 언어, 빠름)
     * - 일본어 & "use_textra" pref ON: TexTra (고품질, 느림)
     * - TexTra 실패 시: Groq 폴백
     */
    private suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        apiKey: String,
        ctx: Context? = null
    ): List<String> {
        // 일본어 + 사용자 설정 ON → TexTra
        val useTexTra = ctx?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            ?.getBoolean("use_textra", false) ?: false

        if (sourceLang.startsWith("ja") && useTexTra) {
            Log.d(TAG, "routing to TexTra (source=$sourceLang, pref=on)")
            val r = TexTraTranslator.translateBatch(texts, "generalNT_ja_ko")
            if (r != null && r.size == texts.size) {
                return r
            }
            Log.w(TAG, "TexTra failed, fallback to Groq")
        }
        // 그 외: Groq (빠름)
        return translateWithGroq(texts, apiKey)
    }

    private suspend fun translateWithGroq(texts: List<String>, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            try {
                val numbered = texts.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
                val prompt = """
다음 문장들을 자연스러운 한국어로 번역해줘.

**규칙:**
1. 원문 언어는 상관없음 (영어/일본어/중국어 등 뭐든)
2. 번호 순서를 그대로 유지
3. 각 줄은 "번호. 번역문" 형식으로
4. 설명 없이 번역만
5. 문장은 자연스럽게 (직역 X)

$numbered
""".trimIndent()

                val body = JSONObject().apply {
                    put("model", "openai/gpt-oss-120b")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a professional Korean translator. Translate any language to natural Korean.")
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
                while (result.size < texts.size) result.add("")
                result.take(texts.size)
            } catch (e: Exception) {
                Log.e(TAG, "Groq err: ${e.message}", e)
                texts
            }
        }

    suspend fun translateToVtt(
        ctx: Context,
        videoId: String,
        vttUrl: String,
        targetLang: String = "ko",
        sourceLang: String = "auto"
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(cacheDir(ctx), "${videoId}_${sourceLang}_${targetLang}.vtt")
            if (cacheFile.exists() && cacheFile.length() > 100) {
                Log.d(TAG, "cache hit: ${cacheFile.absolutePath}")
                return@withContext cacheFile
            }

            val raw = downloadVtt(vttUrl)
            if (raw.isBlank()) return@withContext null

            val cues = parseVtt(raw)
            if (cues.isEmpty()) return@withContext null

            val apiKey = BuildConfig.GROQ_API_KEY

            val translated = mutableListOf<String>()
            val batchSize = 30  // Groq 기준 (30줄씩)
            for (i in cues.indices step batchSize) {
                val batch = cues.subList(i, minOf(i + batchSize, cues.size))
                val texts = batch.map { it.text }
                val results = translateBatch(texts, sourceLang, apiKey, ctx)
                translated.addAll(results)
                Log.d(TAG, "batch ${i / batchSize + 1} done (${results.size})")
            }

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

    fun clearCache(ctx: Context) {
        cacheDir(ctx).listFiles()?.forEach { it.delete() }
    }
}
