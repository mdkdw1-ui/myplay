package com.example.myplayer

import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Groq API (Llama 3.3 70B) 로 요약
 * Gemini 대비: 하루 14,400회 무료, 훨씬 빠름
 */
object GeminiSummary {

    private const val TAG = "AiSummary"
    private const val MODEL = "openai/gpt-oss-120b"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

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
            input.replace(Regex("<[^>]+>"), " ")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
                .replace(Regex("\\s+"), " ").trim()
        }
    }

    suspend fun summarize(title: String, transcript: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) return@withContext "⚠️ API 키가 설정되지 않았습니다."
        if (transcript.isBlank()) return@withContext ""

        try {
            val clean = stripHtml(transcript).take(3000)
            if (clean.isBlank()) return@withContext ""

            val systemPrompt = """
너는 유튜브 영상을 한국어로 요약하는 전문가야.

**반드시 지킬 규칙:**
1. **답변은 무조건 한국어로만** (영어 사용 금지)
2. 원문이 영어/일본어여도 반드시 한국어로 번역해서 요약
3. 핵심 내용을 3~5문장으로 자연스럽게 요약
4. 인사말/광고/구독요청/해시태그 제외
5. 사실과 핵심 정보만
6. "요약:", "다음은" 같은 머리말 붙이지 말기
7. 불릿 포인트 금지, 줄글로
8. 문장은 완전하게 끝맺기
""".trimIndent()

            val userPrompt = """
[영상 제목]
$title

[자막/설명]
$clean

위 내용을 한국어로 3~5문장 요약해줘.
""".trimIndent()

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
                put("temperature", 0.3)
                put("max_tokens", 800)
            }

            val url = URL(ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                Log.e(TAG, "err: $err")
                val hint = when {
                    code == 429 -> "무료 사용량 초과. 잠시 후 다시 시도"
                    code == 401 -> "API 키가 유효하지 않음"
                    else -> ""
                }
                return@withContext "요약 실패 (HTTP $code)\n$hint\n${err.take(200)}"
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            val text = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim() ?: ""

            Log.d(TAG, "summary len=${text.length}")
            text.ifBlank { "요약을 생성할 수 없습니다." }
        } catch (e: Exception) {
            Log.e(TAG, "exception: ${e.message}", e)
            "요약 실패: ${e.message}"
        }
    }
}
