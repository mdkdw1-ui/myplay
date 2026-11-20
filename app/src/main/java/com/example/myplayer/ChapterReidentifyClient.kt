package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ChapterReidentifyClient {

    suspend fun fixChapter(
        videoId: String,
        startMs: Long,
        newTitle: String,
        artist: String? = null
    ): ReidentifyResult = withContext(Dispatchers.IO) {
        val token = getJwtToken()
        android.util.Log.d("ChapterReidentifyClient", "token len=${token?.length ?: 0}")
        if (token == null) return@withContext ReidentifyResult(false, "", "")

        val body = JSONObject().apply {
            put("videoId", videoId)
            put("startMs", startMs)
            put("newTitle", newTitle)
            if (!artist.isNullOrBlank()) put("artist", artist)
        }

        val jobId = submitFix(body, token)
        android.util.Log.d("ChapterReidentifyClient", "job submitted id=$jobId")

        var result: JSONObject? = null
        repeat(60) {
            delay(3000)
            val job = pollFix(jobId, token)
            if (job != null) {
                result = job
                return@repeat
            }
        }

        val res = result ?: return@withContext ReidentifyResult(false, "", "")
        ReidentifyResult(
            ok = res.optBoolean("ok", false),
            title = res.optString("title", ""),
            source = res.optString("source", "google-music")
        )
    }

    private fun submitFix(body: JSONObject, token: String): String {
        val conn = URL("${ServerConfig.get()}/chapters/fix").openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        android.util.Log.d("ChapterReidentifyClient", "submit HTTP $code")
        if (code !in 200..299) throw IOException("submit failed: $code")
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        return resp.getString("job_id")
    }

    private fun pollFix(jobId: String, token: String): JSONObject? {
        val conn = URL("${ServerConfig.get()}/chapters/fix/status?job_id=$jobId")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        val code = conn.responseCode
        android.util.Log.d("ChapterReidentifyClient", "poll HTTP $code")
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("poll failed: $code")
        }
        val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        conn.disconnect()
        return when (resp.optString("status")) {
            "completed" -> resp.optJSONObject("result")
            "failed" -> throw IOException(resp.optString("error", "fix failed"))
            else -> null
        }
    }

    data class ReidentifyResult(val ok: Boolean, val title: String, val source: String)

    private fun getJwtToken(): String? {
        val prefs = MyApp.instance.getSharedPreferences("server_auth", android.content.Context.MODE_PRIVATE)
        return prefs.getString("jwt", null)
    }
}
