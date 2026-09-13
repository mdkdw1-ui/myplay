package com.example.myplayer

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
    private const val MODEL = "gemini-2.0-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    /**
     * 자막 또는 설명을 Gemini로 요약
     * @param title 영상 제목
     * @param transcript 자막/설명 텍스트
     * @return 요약문 (3~5줄)
     */
    suspend fun summarize(title: String, transcript: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            return@withContext "⚠️ API 키가 설정되지 않았습니다. GitHub Secrets에 GEMINI_API_KEY를 등록하세요."
        }
        if (transcript.isBlank()) {
            return@withContext ""
        }

        try {
            // 너무 길면 잘라냄 (Gemini 컨텍스트 초과 방지)
            val trimmed = transcript.take(30000)

            val prompt = """
다음은 유튜브 영상의 자막 또는 설명입니다. **한국어로** 핵심 내용을 3~5문장으로 요약해주세요.

규칙:
- 인사말/광고/구독요청은 제외
- 사실과 핵심 정보만
- 자연스러운 한국어 문장
- 앞에 "요약:" 같은 머리말 붙이지 말기
- 불릿 포인트 사용 금지, 문장으로

[영상 제목]
$title

[자막/설명]
$trimmed
""".trimIndent()

            val body = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 500)
                })
            }

            val url = URL("$ENDPOINT?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                Log.e(TAG, "err body: $err")
                return@withContext "요약 실패 (HTTP $code)"
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

            Log.d(TAG, "summary len=${text.length}")
            text
        } catch (e: Exception) {
            Log.e(TAG, "exception: ${e.message}", e)
            "요약 실패: ${e.message}"
        }
    }
}
