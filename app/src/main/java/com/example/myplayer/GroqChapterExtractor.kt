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

        return try {
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

    // ═══════════════════════════════════════════════════════════════
    // ★ 서버 경계 → Groq에게 각 구간의 곡명 재질의
    // ═══════════════════════════════════════════════════════════════
    suspend fun retitleSegments(
        desc: String,
        segments: List<Pair<Long, Long>>,
        durationSec: Long,
        candidates: List<String> = emptyList(),
    ): List<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank() || segments.isEmpty()) return@withContext emptyList()

        val cleanDesc = desc.take(3000)
        val segList = segments.mapIndexed { i, (s, e) ->
            "${i + 1}) ${s / 1000}초 ~ ${e / 1000}초 (길이 ${(e - s) / 1000}초)"
        }.joinToString("\n")

        val candidateList = candidates
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

        val systemPrompt = """너는 유튜브 음악 모음 영상의 각 구간에 어떤 곡이 재생되는지 알아내는 전문가야.
주어진 설명란에 곡 목록이 있고, 영상이 N개 구간으로 정확히 나뉘어 있다 (librosa 음원 분석으로 얻은 정확한 경계).
각 구간의 시작~끝 시간과 설명란의 곡들을 대조해서, 각 구간에 해당하는 곡을 유추해.

규칙:
1. 정확히 N개 항목의 JSON 배열로만 출력: ["가수 - 곡제목", "가수 - 곡제목", ...]
2. 각 항목은 "가수 - 곡제목" 형식
3. 후보 곡명 목록이 있으면 반드시 그 목록 안에서만 선택해
4. 후보 목록에 없는 곡명은 절대 만들지 마
5. 코드블록 없이 순수 JSON 배열만 출력"""

        val userPrompt = """[길이] ${durationSec}초 (${durationSec / 60}분)

[설명란]
$cleanDesc

[구간 목록 - ${segments.size}개]
$segList

[후보 곡명]
${candidateList.ifBlank { "없음" }}

위 ${segments.size}개 구간 각각에 재생되는 곡을 JSON 배열로 출력해줘. (정확히 ${segments.size}개)
반드시 후보 곡명 목록 안에서만 선택해줘."""

        for (model in MODELS) {
            dlog("🔄 retitle try: $model (${segments.size}구간)")
            val result = tryRetitle(apiKey, model, systemPrompt, userPrompt, segments.size)
            if (result.isNotEmpty()) {
                dlog("✅ retitle $model 성공: ${result.size}곡")
                return@withContext result
            }
        }

        dlog("❌ retitle 모든 모델 실패")
        emptyList()
    }

    private fun tryRetitle(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        expectedCount: Int
    ): List<String> {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                })
                put("temperature", 0.2)
                put("max_tokens", 2000)
            }

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            dlog("[$model] retitle HTTP $code")
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                dlog("[$model] retitle ❌ err: ${err.take(200)}")
                return emptyList()
            }

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val content = JSONObject(response).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content") ?: ""

            dlog("[$model] retitle content len=${content.length}, preview=${content.take(150).replace("\n", " | ")}")

            val cleaned = content.replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end < 0) { dlog("[$model] retitle ❌ JSON 배열 못 찾음"); return emptyList() }
            val jsonStr = cleaned.substring(start, end + 1)

            val arr = JSONArray(jsonStr)
            val titles = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                titles.add(arr.optString(i, "").trim())
            }

            if (titles.size < expectedCount) {
                dlog("[$model] retitle ⚠️ ${titles.size} < $expectedCount → 부족분 채움")
                while (titles.size < expectedCount) titles.add("")
            }

            titles.take(expectedCount).mapIndexed { i, t -> t.ifBlank { "구간 ${i + 1}" } }
        } catch (e: Exception) {
            dlog("[$model] retitle ❌ exception: ${e.message}")
            emptyList()
        }
    }
}
