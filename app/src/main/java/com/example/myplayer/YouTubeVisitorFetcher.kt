package com.example.myplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object YouTubeVisitorFetcher {
    private const val TAG = "YtVisitor"
    private const val API_URL = "https://www.youtube.com/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun ensure(ctx: Context): String = withContext(Dispatchers.IO) {
        val cached = YouTubeVisitorData.load(ctx)
        if (cached.isNotBlank()) {
            Log.d(TAG, "cached: len=${cached.length}")
            return@withContext cached
        }

        // 방법 1: visitor_id API
        val fromApi = fetchFromApi(ctx)
        if (fromApi.isNotBlank()) {
            YouTubeVisitorData.save(ctx, fromApi)
            Log.d(TAG, "from API: len=${fromApi.length}")
            return@withContext fromApi
        }

        // 방법 2: HTML
        val fromHtml = fetchFromHtml(ctx)
        if (fromHtml.isNotBlank()) {
            YouTubeVisitorData.save(ctx, fromHtml)
            Log.d(TAG, "from HTML: len=${fromHtml.length}")
            return@withContext fromHtml
        }

        Log.e(TAG, "visitorData 획득 실패")
        ""
    }

    private fun fetchFromApi(ctx: Context): String {
        try {
            val json = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20240101.00.00","hl":"ko","gl":"KR"}}}"""

            val reqBuilder = Request.Builder()
                .url(API_URL)
                .method("POST", json.toRequestBody("application/json".toMediaType()))
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")

            // 쿠키 + 인증
            try {
                val cookie = YouTubeCookieManager.load(ctx)
                if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)
            } catch (_: Exception) {}

            val resp = client.newCall(reqBuilder.build()).execute()
            val code = resp.code
            val text = resp.body?.string() ?: ""
            resp.close()

            Log.d(TAG, "API HTTP $code, len=${text.length}")

            if (code !in 200..299) return ""

            // ★ visitorData 추출 (정규식)
            val regex = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")
            val m = regex.find(text)
            if (m != null) {
                val vd = m.groupValues[1]
                Log.d(TAG, "visitorData regex match len=${vd.length}")
                return vd
            }

            // responseContext 안 시도
            try {
                val jsonObj = org.json.JSONObject(text)
                val vd = jsonObj.optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?: jsonObj.optString("visitorData")
                if (vd.isNotBlank()) return vd
            } catch (_: Exception) {}

            Log.d(TAG, "API 응답에 visitorData 없음")
        } catch (e: Exception) {
            Log.e(TAG, "API err: ${e.message}")
        }
        return ""
    }

    private fun fetchFromHtml(ctx: Context): String {
        try {
            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "ko-KR,ko;q=0.9")

            try {
                val cookie = YouTubeCookieManager.load(ctx)
                if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)
            } catch (_: Exception) {}

            val resp = client.newCall(reqBuilder.build()).execute()
            val html = resp.body?.string() ?: ""
            resp.close()

            val regex = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")
            val m = regex.find(html)
            if (m != null) return m.groupValues[1]

            Log.d(TAG, "HTML에 visitorData 없음 (len=${html.length})")
        } catch (e: Exception) {
            Log.e(TAG, "HTML err: ${e.message}")
        }
        return ""
    }
}
