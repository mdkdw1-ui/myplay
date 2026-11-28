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

            val newStart = resp.optLong("startMs", -1L)
            val chapters = resp.optJSONArray("chapters")
            val hasChapters = chapters != null && chapters.length() > 0
            val title = resp.optString("title", newTitle)

            ReidentifyResult(
                ok = (newStart >= 0 && title.isNotBlank()) || hasChapters,
                title = title,
                source = resp.optString("source", "google+whisper"),
                startMs = if (newStart < 0) 0L else newStart,
                chaptersJson = chapters?.toString(),
                added = resp.optBoolean("added", true),
                reason = resp.optString("reason", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    data class ReidentifyResult(
        val ok: Boolean,
        val title: String,
        val source: String,
        val startMs: Long,
        val chaptersJson: String? = null,
        val added: Boolean = true,
        val reason: String = ""
    )

    suspend fun fetchCurrentVideoId(): String? = withContext(Dispatchers.IO) {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("${ServerConfig.get()}/chapters/current_video")
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            getJwtToken()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (conn.responseCode in 200..299) {
                val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                json.optString("videoId").takeIf { it.isNotBlank() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun getJwtToken(): String? {
        val prefs = MyApp.instance.getSharedPreferences(
            "server_auth",
            android.content.Context.MODE_PRIVATE
        )
        return prefs.getString("jwt", null)
    }
}
