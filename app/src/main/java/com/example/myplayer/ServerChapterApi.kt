package com.example.myplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ServerChapterApi {
    private const val TAG = "ServerChapterApi"
    private val submitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun markSubmitted(videoId: String): Boolean {
        if (videoId in submitted) return false
        submitted.add(videoId)
        return true
    }
    data class ServerChapter(val startMs: Long, val endMs: Long, val title: String, val confidence: Double)
    data class AnalyzeResult(val chapters: List<ServerChapter>, val titles: List<String>)

    suspend fun analyze(
        ctx: Context,
        videoId: String,
        audioUrl: String,
        durationMs: Long,
        groqSegments: org.json.JSONArray? = null
    ): AnalyzeResult? = withContext(Dispatchers.IO) {
        try {
            val token = ctx.getSharedPreferences("server_auth", Context.MODE_PRIVATE)
                .getString("jwt", null)
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("audioUrl", audioUrl)
                put("durationMs", durationMs)
                if (groqSegments != null && groqSegments.length() > 0) {
                    put("groqSegments", groqSegments)
                }
            }.toString()
            val submitResp = http("${ServerConfig.get()}/chapters/analyze", "POST", body, token) ?: return@withContext null
            val submitJson = JSONObject(submitResp)
            val jobId = submitJson.optString("jobId", "")
            if (jobId.isEmpty()) { Log.e(TAG, "no jobId"); return@withContext null }
            Log.d(TAG, "submitted job=$jobId video=$videoId")
            // ★ 서버 캐시 히트 시 즉시 결과 조회 (10초 대기 skip)
            if (submitJson.optBoolean("cached", false) || submitJson.optString("status") == "complete") {
                Log.d(TAG, "CACHE HIT: fetching result immediately")
                repeat(3) { attempt ->
                    val res = http("${ServerConfig.get()}/chapters/result?jobId=$jobId", "GET", null, token)
                    if (res != null) {
                        val resJson = JSONObject(res)
                        val arr = resJson.optJSONArray("chapters")
                        val titlesArr = resJson.optJSONArray("titles")
                        if (arr != null) {
                            val chs = (0 until arr.length()).map { j ->
                                val o = arr.getJSONObject(j)
                                ServerChapter(o.optLong("startMs"), o.optLong("endMs"),
                                    o.optString("title", "구간 ${j+1}"), o.optDouble("confidence", 0.7))
                            }
                            val ttls = mutableListOf<String>()
                            if (titlesArr != null) for (k in 0 until titlesArr.length()) ttls.add(titlesArr.optString(k, ""))
                            return@withContext AnalyzeResult(chs, ttls)
                        }
                    }
                    if (attempt < 2) delay(1_000L)
                }
                Log.w(TAG, "cache hit but result fetch failed")
                return@withContext null
            }
            repeat(90) { i ->
                val statusResp = http("${ServerConfig.get()}/chapters/status?jobId=$jobId", "GET", null, token)
                if (statusResp == null) { delay(5_000L); return@repeat }
                val st = JSONObject(statusResp).optString("status", "")
                Log.d(TAG, "poll[$i] status=$st")
                when (st) {
                    "complete" -> {
                        val res = http("${ServerConfig.get()}/chapters/result?jobId=$jobId", "GET", null, token) ?: return@withContext null
                        val resJson = JSONObject(res)
                        val arr = resJson.optJSONArray("chapters") ?: return@withContext null
                        val chs = (0 until arr.length()).map { j ->
                            val o = arr.getJSONObject(j)
                            ServerChapter(o.optLong("startMs"), o.optLong("endMs"),
                                o.optString("title", "구간 ${j+1}"), o.optDouble("confidence", 0.7))
                        }
                        val titlesArr = resJson.optJSONArray("titles")
                        val ttls = mutableListOf<String>()
                        if (titlesArr != null) for (k in 0 until titlesArr.length()) ttls.add(titlesArr.optString(k, ""))
                        return@withContext AnalyzeResult(chs, ttls)
                    }
                    "failed", "canceled", "cancelled" -> { Log.w(TAG, "server $st"); return@withContext null }
                }
                delay(10_000L)
            }
            null
        } catch (e: Exception) { Log.e(TAG, "analyze err", e); null }
    }

    suspend fun saveTitles(ctx: Context, videoId: String, titles: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = ctx.getSharedPreferences("server_auth", Context.MODE_PRIVATE).getString("jwt", null)
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("titles", org.json.JSONArray(titles))
            }.toString()
            val resp = http("${ServerConfig.get()}/chapters/titles_save", "POST", body, token) ?: return@withContext false
            val ok = JSONObject(resp).optBoolean("saved", false)
            Log.d(TAG, "saveTitles $videoId → ${titles.size}개 saved=$ok")
            ok
        } catch (e: Exception) { Log.e(TAG, "saveTitles err", e); false }
    }

    private fun http(url: String, method: String, body: String?, token: String?): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                if (body != null) doOutput = true
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: return null)).use { it.readText() }
            Log.d(TAG, "$method ${url.substringAfter(ServerConfig.get())} → $code")
            if (code in 200..299) text else { Log.e(TAG, "http $code: ${text.take(200)}"); null }
        } catch (e: Exception) { Log.e(TAG, "http err: ${e.message}"); null }
    }
}
