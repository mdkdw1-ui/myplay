package com.example.myplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

object PlaybackWarmup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastWarmId = AtomicReference<String?>(null)

    fun warm(videoId: String?) {
        val id = videoId?.takeIf { it.isNotBlank() } ?: return
        if (id.startsWith("local") || id.startsWith("localdownload")) return
        if (lastWarmId.getAndSet(id) == id) return

        scope.launch {
            // 1) 서버 /warm 호출 (백그라운드 다운로드 트리거)
            try {
                val token = try {
                    MyApp.instance.getSharedPreferences("server_auth", android.content.Context.MODE_PRIVATE)
                        .getString("jwt", null)
                } catch (_: Exception) { null }

                if (!token.isNullOrBlank()) {
                    val url = "http://193.123.167.118:5000/warm?v=$id&token=$token"
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    android.util.Log.d("PlaybackWarmup", "server /warm: $id code=$code")
                }
            } catch (e: Exception) {
                android.util.Log.w("PlaybackWarmup", "/warm failed: ${e.message}")
            }

            // 2) 로컬 extractAudioOnly (캐시 채우기)
            try {
                YouTubeStream.extractAudioOnly(id)
            } catch (_: Exception) { }
        }
    }
}
