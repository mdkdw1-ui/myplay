package com.example.myplayer

import kotlinx.coroutines.Dispatchers
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
            ?: return@withContext ReidentifyResult(false, "", "", 0L)

        val body = JSONObject().apply {
            put("videoId", videoId)
            put("positionMs", startMs)
            put("title", newTitle)
            if (!artist.isNullOrBlank()) put("artist", artist)
        }.toString()

        val conn = URL("${ServerConfig.get()}/chapters/identify_manual")
            .openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 300000

        try {
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("identify_manual failed: $code $err")
            }

            val resp = JSONObject(
                conn.inputStream.bufferedReader().use { it.readText() }
            )

            val newStart = resp.optLong("startMs", 0L)

            ReidentifyResult(
                ok = newStart > 0,
                title = resp.optString("title", newTitle),
                source = resp.optString("source", "google+whisper"),
                startMs = newStart
            )
        } finally {
            conn.disconnect()
        }
    }

    data class ReidentifyResult(
        val ok: Boolean,
        val title: String,
        val source: String,
        val startMs: Long
    )

    private fun getJwtToken(): String? {
        val prefs = MyApp.instance.getSharedPreferences(
            "server_auth",
            android.content.Context.MODE_PRIVATE
        )
        return prefs.getString("jwt", null)
    }
}
