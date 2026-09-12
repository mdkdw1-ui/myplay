package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeStream {

    data class StreamResult(
        val url: String,
        val title: String,
        val isVideo: Boolean
    )

    suspend fun extract(videoId: String): StreamResult? = withContext(Dispatchers.IO) {
        try {
            try {
                NewPipe.init(DownloaderImpl())
            } catch (e: Exception) {
            }

            val url = "https://www.youtube.com/watch?v=" + videoId
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            val title = info.name ?: ""

            val muxed = info.videoStreams
                .filter { it.isUrl }
                .sortedByDescending { it.getResolution() }
                .firstOrNull { !it.isVideoOnly }

            if (muxed != null) {
                return@withContext StreamResult(muxed.content, title, true)
            }

            val videoOnly = info.videoStreams
                .filter { it.isUrl }
                .sortedByDescending { it.getResolution() }
                .firstOrNull()

            if (videoOnly != null) {
                return@withContext StreamResult(videoOnly.content, title, true)
            }

            val audio = info.audioStreams
                .filter { it.isUrl }
                .sortedByDescending { it.getAverageBitrate() }
                .firstOrNull()

            if (audio != null) {
                return@withContext StreamResult(audio.content, title, false)
            }

            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
