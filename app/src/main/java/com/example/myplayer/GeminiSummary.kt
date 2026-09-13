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

    // 안정 버전 (목록에서 확인됨)
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

        // API 키 검증 (디버그)
        if (apiKey.isBlank()) {
            return@withContext "⚠️ API 키가 설정되지 않았습니다.\n" +
                    "GitHub Secrets에 GEMINI_API_KEY를 등록했는지 확인하세요."
        }

        val keyPreview = if (apiKey.length > 8)
            "${apiKey.take(4)}...${apiKey.takeLast(4)} (len=${apiKey.length})"
        else "len=${apiKey.length}"
        Log.d(TAG, "API key: $keyPreview")

        if (transcript.isBlank()) return@withContext ""

        try {
            val clean = stripHtml(transcript).take(30000)
            if (clean.isBlank()) return@withContext ""

            val prompt = """
다음은 유튜브 영상의 자막 또는 설명입니다. **한국어로** 핵심 내용을 3~5문장으로 요약해주세요.

규칙:
- 인사말/광고/구독요청/해시태그는 제외
- 사실과 핵심 정보만
- 자연스러운 한국어 문장
- 앞에 "요약:" 같은 머리말 붙이지 말기
- 불릿 포인트 금지, 문장으로

[영상 제목]
$title

[자막/설명]
$clean
""".trimIndent()

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 1000)
                })
            }

            val url = URL("$BASE/$MODEL:generateContent")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            // ★ 핵심: URL 쿼리 대신 헤더로 API 키 전달
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 40000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code  url=$BASE/$MODEL:generateContent")

            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                Log.e(TAG, "err body: $err")

                // 에러 원인 상세 표시
                val hint = when {
                    err.contains("API_KEY_INVALID") -> "API 키가 유효하지 않습니다"
                    err.contains("PERMISSION_DENIED") -> "이 키에 권한이 없습니다"
                    err.contains("QUOTA") -> "무료 할당량 초과"
                    code == 404 -> "모델 또는 엔드포인트를 찾을 수 없음"
                    else -> ""
                }
                return@withContext "요약 실패 (HTTP $code)\n$hint\n$err"
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim() ?: ""

            if (text.isBlank()) {
                // 안전 필터에 걸린 경우
                val finishReason = json.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optString("finishReason") ?: "UNKNOWN"
                return@withContext "요약 결과 없음 (finishReason=$finishReason)"
            }

            Log.d(TAG, "summary len=${text.length}")
            text
        } catch (e: Exception) {
            Log.e(TAG, "exception: ${e.message}", e)
            "요약 실패: ${e.message}"
        }
    }
}
