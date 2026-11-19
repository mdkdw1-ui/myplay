package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ChapterReidentifyClient {
    private const val SERVER = "http://193.123.167.118:5000"

    suspend fun reidentify(videoId: String, timeSec: Double, title: String? = null): ReidentifyResult =
        withContext(Dispatchers.IO) {
            val token = getJwtToken() ?: return@withContext ReidentifyResult(false, "", "")
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("time", timeSec)
                if (!title.isNullOrBlank()) put("title", title)
            }
            val conn = URL("$SERVER/chapters/reidentify").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext ReidentifyResult(false, "", "")
            val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            ReidentifyResult(
                ok = resp.optBoolean("ok", false),
                title = resp.optString("title", ""),
                source = resp.optString("source", "")
            )
        }

    data class ReidentifyResult(val ok: Boolean, val title: String, val source: String)

    private fun getJwtToken(): String? {
        val prefs = MyApp.instance.getSharedPreferences("serverauth", android.content.Context.MODE_PRIVATE)
        return prefs.getString("jwt", null)
    }
}
