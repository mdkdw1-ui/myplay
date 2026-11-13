package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object GroqChapterExtractor {
    private const val TAG = "GroqChapters"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL = "openai/gpt-oss-120b"

    suspend fun generateTimestamps(
        description: String,
        durationSec: Long
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) return@withContext ""

        val cleanDesc = description.take(2000)
        val systemPrompt = """너는 유튜브 음악 모음 영상의 타임스탬프를 만드는 전문가야.
주어진 설명란과 영상 길이를 분석해서, 곡이 바뀌는 지점의 타임스탬프를 유추해서 생성해.
규칙:
1. 반드시 "00:00:00"으로 시작
2. 최소 10개 이상 타임스탬프
3. 형식은 "HH:MM:SS 곡제목" 또는 "MM:SS 곡제목"
4. 곡 제목은 설명란에 있는 가수들의 유명한 곡으로 유추
5. 타임스탬프 목록만 출력 (다른 말 하지 말 것)"""

        val userPrompt = """[영상 길이] ${durationSec}초 (${durationSec / 60}분)
[설명란]
$cleanDesc

위 영상은 음악 모음 영상이야. 전체 길이에 맞춰 곡들의 타임스탬프를 만들어줘."""

        try {
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
                put("max_tokens", 4000) // ★ 중요: Reasoning 토큰 때문에 넉넉히 설정
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            // Cloudflare 차단 방지용 User-Agent
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "HTTP ${conn.responseCode}")
                return@withContext ""
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content") ?: ""

            // "00:00:00" 또는 "00:00" 으로 시작하는 줄만 필터링
            val lines = content.lines()
                .map { it.trim() }
                .filter { it.matches(Regex("^(\\d{1,2}:)?\\d{1,2}:\\d{2}(:\\d{2})?\\s+.+$")) }

            if (lines.isEmpty()) return@withContext ""
            lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            ""
        }
    }
}
