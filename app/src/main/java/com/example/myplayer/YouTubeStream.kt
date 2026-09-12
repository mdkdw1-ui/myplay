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
        val title: String,
        val debug: String
    ) {
        val hasAny: Boolean
            get() = muxedUrl != null || videoUrl != null || audioUrl != null
    }

    suspend fun extract(videoId: String): StreamResult = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            try {
                NewPipe.init(DownloaderImpl())
            } catch (e: Exception) {
                sb.append("init: ${e.message}\n")
            }

            val url = "https://www.youtube.com/watch?v=$videoId"
            sb.append("URL: $url\n")

            val info = try {
                StreamInfo.getInfo(ServiceList.YouTube, url)
            } catch (e: Exception) {
                sb.append("getInfo FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
                return@withContext StreamResult(null, null, null, "", sb.toString())
            }

            val title = info.name ?: ""
            sb.append("title: $title\n")
            sb.append("videoStreams: ${info.videoStreams.size}\n")
            sb.append("audioStreams: ${info.audioStreams.size}\n")

            val muxed = info.videoStreams.firstOrNull { !it.isVideoOnly && it.isUrl }
            if (muxed != null) {
                sb.append("muxed: ${muxed.resolution}\n")
                return@withContext StreamResult(null, null, muxed.content, title, sb.toString())
            }

            val video = try {
                info.videoStreams.filter { it.isVideoOnly && it.isUrl }.maxByOrNull { it.resolution }
            } catch (e: Exception) {
                sb.append("video FAIL: ${e.message}\n")
                null
            }
            val audio = try {
                info.audioStreams.filter { it.isUrl }.maxByOrNull { it.averageBitrate }
            } catch (e: Exception) {
                sb.append("audio FAIL: ${e.message}\n")
                null
            }

            sb.append("video: ${video?.resolution}\n")
            sb.append("audio: ${audio?.averageBitrate}\n")

            if (video != null && audio != null) {
                return@withContext StreamResult(video.content, audio.content, null, title, sb.toString())
            }
            if (video != null) {
                return@withContext StreamResult(video.content, null, null, title, sb.toString())
            }
            if (audio != null) {
                return@withContext StreamResult(null, audio.content, null, title, sb.toString())
            }

            sb.append("no usable stream\n")
            StreamResult(null, null, null, title, sb.toString())
        } catch (e: Exception) {
            sb.append("OUTER: ${e.javaClass.simpleName}: ${e.message}\n")
            StreamResult(null, null, null, "", sb.toString())
        }
    }
}
