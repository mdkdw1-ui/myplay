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

object GeminiSummary {

    private const val TAG = "GeminiSummary"
    private const val MODEL = "gemini-3.6-flash"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

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
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    suspend fun summarize(title: String, transcript: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) return@withContext "⚠️ API 키가 설정되지 않았습니다."
        if (transcript.isBlank()) return@withContext ""

        try {
            val clean = stripHtml(transcript).take(30000)
            if (clean.isBlank()) return@withContext ""

            val result = callGemini(apiKey, buildPrompt(title, clean))
                ?: return@withContext "요약을 생성할 수 없습니다."

            // ★ 영어로 나온 경우 자동 재번역
            if (isMostlyEnglish(result)) {
                Log.d(TAG, "summary was English, re-translating")
                val translated = callGemini(apiKey, translatePrompt(result))
                return@withContext translated ?: result
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "exception: ${e.message}", e)
            "요약 실패: ${e.message}"
        }
    }

    private fun buildPrompt(title: String, content: String): String = """
너는 유튜브 영상을 한국어로 요약하는 전문가야.

**반드시 지킬 규칙:**
1. **답변은 무조건 한국어로만** 작성 (영어 사용 금지)
2. 원문이 영어/일본어 등이어도 반드시 한국어로 번역해서 요약
3. 핵심 내용을 3~5문장으로 자연스럽게 요약
4. 인사말, 광고, 구독요청, 해시태그는 제외
5. 사실과 핵심 정보만 포함
6. "요약:", "다음은" 같은 머리말 붙이지 말기
7. 불릿 포인트(•, -, *) 사용 금지, 줄글로
8. 문장은 반드시 완전하게 끝맺기 (중간에 자르지 말 것)

[영상 제목]
$title

[원문 내용]
$content

위 내용을 한국어로 3~5문장 요약해줘.
""".trimIndent()

    private fun translatePrompt(text: String): String = """
다음 영어 텍스트를 자연스러운 한국어로 번역해줘. 내용은 유튜브 영상 요약이야.
번역만 하고 다른 설명은 붙이지 마. 문장은 완전하게 끝맺어.

$text
""".trimIndent()

    /**
     * 간단한 영어 감지: 알파벳 비율이 높고 한글이 거의 없으면 영어로 판단
     */
    private fun isMostlyEnglish(text: String): Boolean {
        if (text.isBlank()) return false
        val korean = text.count { it in '\uAC00'..'\uD7A3' }
        val english = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        // 한글이 거의 없고 알파벳이 30자 이상이면 영어
        return korean < 5 && english > 30
    }

    private fun callGemini(apiKey: String, prompt: String): String? {
        return try {
            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 2048)   // ★ 여유있게
                    put("topP", 0.95)
                })
            }

            val url = URL("$BASE/$MODEL:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                Log.e(TAG, "err: $err")
                return "요약 실패 (HTTP $code)\n$err"
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            val finishReason = candidate?.optString("finishReason") ?: ""
            Log.d(TAG, "finishReason=$finishReason")

            val text = candidate
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim() ?: ""

            if (text.isBlank()) return null

            // MAX_TOKENS로 잘린 경우
            if (finishReason == "MAX_TOKENS") {
                // 문장 끝까지 잘라서 반환
                return text.substringBeforeLast('.', text).trim() + "."
            }

            text
        } catch (e: Exception) {
            Log.e(TAG, "callGemini exception: ${e.message}", e)
            "요약 실패: ${e.message}"
        }
    }
}
