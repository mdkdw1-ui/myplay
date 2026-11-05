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
            try {
                YouTubeStream.extractAudioOnly(id)
            } catch (_: Exception) { }
        }
    }
}
