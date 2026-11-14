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

    // 1순위 모델, 2순위 폴백
    private val MODELS = listOf("openai/gpt-oss-120b", "llama-3.1-8b-instant")

    private fun dlog(msg: String) {
        Log.d(TAG, msg)
        try {
            val f = java.io.File(MyApp.instance.filesDir, "audio_diag.log")
            if (f.exists() && f.length() > 500_000) f.writeText("")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] [GROQ] $msg\n")
        } catch (_: Exception) {}
    }

    suspend fun generateTimestamps(
        description: String,
        durationSec: Long
    ): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            dlog("❌ API key 비어있음")
            return@withContext ""
        }
        dlog("🔑 API key prefix=${apiKey.take(10)}... len=${apiKey.length}")
        dlog("📝 desc len=${description.length}, duration=${durationSec}초")

        for (model in MODELS) {
            dlog("🔄 try model: $model")
            val result = tryCall(apiKey, model, description, durationSec)
            if (result.isNotBlank()) {
                dlog("✅ $model 성공: ${result.lines().size}줄")
                return@withContext result
            }
        }
        dlog("❌ 모든 모델 실패")
        ""
    }

    private fun tryCall(
        apiKey: String,
        model: String,
        description: String,
        durationSec: Long
    ): String {
        val cleanDesc = description.take(2000)
        val systemPrompt = """너는 유튜브 음악 모음 영상의 타임스탬프를 만드는 전문가야.
주어진 설명란과 영상 길이를 보고, 곡이 바뀌는 지점의 타임스탬프를 유추해.
규칙:
1. 반드시 "00:00:00"으로 시작
2. 최소 10개 이상
3. "HH:MM:SS 곡제목" 형식
4. 곡 제목은 설명란의 가수 유명곡으로 유추
5. 목록만 출력"""

        val userPrompt = """[길이] ${durationSec}초 (${durationSec / 60}분)
[설명란]
$cleanDesc

타임스탬프를 만들어줘."""

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system"); put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user"); put("content", userPrompt)
                    })
                })
                put("temperature", 0.3)
                put("max_tokens", 4000)
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            dlog("[$model] HTTP $code")

            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                dlog("[$model] ❌ err: ${err.take(300)}")
                return ""
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)
            val content = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content") ?: ""

            dlog("[$model] content len=${content.length}, preview=${content.take(80).replace("\n", " | ")}")

            // 더 유연한 필터: "HH:MM:SS" 또는 "MM:SS" 로 시작하는 줄
            val timePattern = Regex("^\\d{1,2}:\\d{1,2}:\\d{2}(\\s+.+)?$")
            val timePattern2 = Regex("^\\d{1,2}:\\d{2}(\\s+.+)?$")
            val lines = content.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && (timePattern.containsMatchIn(it) || timePattern2.containsMatchIn(it)) }

            dlog("[$model] 필터 후 ${lines.size}줄")
            if (lines.isEmpty()) return ""
            lines.joinToString("\n")
        } catch (e: Exception) {
            dlog("[$model] ❌ exception: ${e.message}")
            ""
        }
    }
}
