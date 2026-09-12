package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeStream {

    private const val TAG = "YouTubeStream"

    data class StreamResult(
        val videoUrl: String?,
        val audioUrl: String?,
        val muxedUrl: String?,
        val title: String
    ) {
        val hasAny: Boolean
            get() = muxedUrl != null || videoUrl != null || audioUrl != null
    }

    suspend fun extract(videoId: String): StreamResult? = withContext(Dispatchers.IO) {
        try {
            try {
                NewPipe.init(DownloaderImpl())
            } catch (e: Exception) {
            }

            val url = "https://www.youtube.com/watch?v=" + videoId
            Log.d(TAG, "extract: $url")
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            val title = info.name ?: ""

            Log.d(TAG, "videoStreams: ${info.videoStreams.size}, audioStreams: ${info.audioStreams.size}")

            val muxed = info.videoStreams.firstOrNull { !it.isVideoOnly && it.isUrl }
            if (muxed != null) {
                Log.d(TAG, "muxed found")
                return@withContext StreamResult(null, null, muxed.content, title)
            }

            val video = info.videoStreams
                .filter { it.isVideoOnly && it.isUrl }
                .maxByOrNull { it.resolution }

            val audio = info.audioStreams
                .filter { it.isUrl }
                .maxByOrNull { it.averageBitrate }

            Log.d(TAG, "video=${video?.resolution}, audio=${audio?.averageBitrate}")

            if (video != null && audio != null) {
                return@withContext StreamResult(video.content, audio.content, null, title)
            }
            if (video != null) {
                return@withContext StreamResult(video.content, null, null, title)
            }
            if (audio != null) {
                return@withContext StreamResult(null, audio.content, null, title)
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "extract error: ${e.message}", e)
            null
        }
    }
}
