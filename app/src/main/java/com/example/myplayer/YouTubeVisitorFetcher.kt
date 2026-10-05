package com.example.myplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object YouTubeVisitorFetcher {
    private const val TAG = "YtVisitor"
    private const val INIT_URL = "https://www.youtube.com/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"

    /** visitorData 최초 1회 획득 (없을 때만) */
    suspend fun ensure(ctx: Context): String = withContext(Dispatchers.IO) {
        val cached = YouTubeVisitorData.load(ctx)
        if (cached.isNotBlank()) return@withContext cached

        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
            }
            val conn = URL(INIT_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            CookieUtil.apply(conn)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "HTTP ${conn.responseCode}")
                return@withContext ""
            }

            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(text)
            val vd = json.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?: json.optString("visitorData")
            if (vd.isNotBlank()) {
                YouTubeVisitorData.save(ctx, vd)
                Log.d(TAG, "visitorData acquired len=${vd.length}")
            }
            vd
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}")
            ""
        }
    }
}
