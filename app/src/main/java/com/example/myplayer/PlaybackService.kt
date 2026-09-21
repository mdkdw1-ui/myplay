package com.example.myplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var resolvingNext = false

    private val endListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                Log.d("PlaybackService", "STATE_ENDED → resolveNext")
                serviceScope.launch {
                    val next = resolveNext()
                    if (next != null) {
                        playNext(next)
                    } else {
                        Log.d("PlaybackService", "no next track")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(50000, 120000, 5000, 10000)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(false)
            .setLoadControl(loadControl)
            .build()

        player.addListener(endListener)
        exoPlayer = player

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    /** ★ 다음 곡 결정 — Service에서 자체 처리 */
    private suspend fun resolveNext(): VideoItem? {
        if (resolvingNext) return null
        resolvingNext = true
        try {
            val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            val currentVideoId = prefs.getString("current_video_id", "") ?: ""
            val currentTitle = prefs.getString("current_title", "") ?: ""
            val currentChannel = prefs.getString("current_channel", "") ?: ""
            val currentArtist = prefs.getString("current_artist", "") ?: ""
            val sameArtist = prefs.getBoolean("same_artist_mode", false)
            val disliked = prefs.getStringSet("disliked_ids", emptySet()) ?: emptySet()

            if (currentVideoId.isBlank()) return null

            // 1) 큐
            val queue = QueueManager.get(this)
            val queueNext = queue.firstOrNull { it.videoId != currentVideoId }
            if (queueNext != null) {
                QueueManager.remove(this, queueNext.videoId)
                return VideoItem(
                    queueNext.videoId, queueNext.title,
                    queueNext.channel, queueNext.thumbnail
                )
            }

            // 2) YouTubeRadio / YouTubeArtist
            val related = if (sameArtist && currentArtist.isNotBlank()) {
                YouTubeArtist.fetchSongs(currentArtist, currentVideoId)
            } else {
                YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel)
            }

            return related.firstOrNull {
                it.videoId != currentVideoId && it.videoId !in disliked
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "resolveNext err", e)
            return null
        } finally {
            resolvingNext = false
        }
    }

    /** ★ 다음 곡 재생 — Service에서 직접 */
    private suspend fun playNext(item: VideoItem) {
        val player = exoPlayer ?: return
        try {
            val result = YouTubeStream.extract(item.videoId)
            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl
            if (url.isNullOrBlank()) {
                Log.e("PlaybackService", "no stream for ${item.videoId}")
                return
            }

            // 자막 URL 저장
            val subUrl = result.subtitles
                .firstOrNull { it.languageCode.startsWith("ko") }?.url
                ?: result.subtitles.firstOrNull { it.languageCode.startsWith("en") }?.url
                ?: result.subtitles.firstOrNull()?.url
                ?: ""

            // 현재 곡 정보 prefs에 저장
            val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("current_video_id", item.videoId)
                .putString("current_title", item.title)
                .putString("current_channel", item.channel)
                .putString("current_thumbnail", item.thumbnail)
                .putString("current_subtitle_url", subUrl)
                .apply()
            QueueManager.setCurrent(this@PlaybackService, item.videoId)

            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.channel)
                .setAlbumTitle(item.channel)
                .setArtworkUri(Uri.parse(item.thumbnail))
                .build()

            val mi = MediaItem.Builder()
                .setUri(url)
                .setMediaId(item.videoId)
                .setMediaMetadata(metadata)
                .build()

            withContext(Dispatchers.Main) {
                player.setMediaItem(mi)
                player.prepare()
                player.playWhenReady = true
            }
            Log.d("PlaybackService", "played next: ${item.title}")
        } catch (e: Exception) {
            Log.e("PlaybackService", "playNext err", e)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player != null && player.playWhenReady) {
            Log.d("PlaybackService", "task removed, keep playing")
        } else {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.removeListener(endListener)
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        serviceScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    companion object {
        var exoPlayer: ExoPlayer? = null
            private set

        /** (호환용) 예전 Activity 핸들러 */
        var nextTrackHandler: (() -> Unit)? = null
    }
}
