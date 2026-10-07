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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var resolvingNext = false

    private val endListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // ExoPlayer 자동 큐 진행 시 prefs + QueueManager 동기화
            val newId = mediaItem?.mediaId ?: return
            if (newId.startsWith("local:")) {
                val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("current_video_id", newId)
                    .putString("current_title",
                        mediaItem.mediaMetadata.title?.toString() ?: "")
                    .putString("current_channel",
                        mediaItem.mediaMetadata.artist?.toString() ?: "")
                    .apply()
                QueueManager.setCurrent(this@PlaybackService, newId)
                Log.d("PlaybackService", "transition → $newId")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val p = exoPlayer

                // ★ 큐에 여러 곡이 있으면 ExoPlayer가 자동 진행 중이므로 skip
                if (p != null && p.mediaItemCount > 1) {
                    Log.d("PlaybackService",
                        "STATE_ENDED but mediaItemCount=${p.mediaItemCount} → skip")
                    return
                }

                // ★ 큐에 다음 곡이 남아있으면 skip
                if (p != null && p.hasNextMediaItem()) {
                    Log.d("PlaybackService", "STATE_ENDED but hasNext → skip")
                    return
                }

                // 진짜 큐 소진 → resolveNext
                Log.d("PlaybackService", "STATE_ENDED → resolveNext")
                serviceScope.launch {
                    val next = resolveNext()
                    if (next != null) playNext(next)
                    else Log.d("PlaybackService", "no next track")
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

        // ★ 큰 파일 대응: 버퍼 대폭 증가 (5분 max)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000,     // minBufferMs (50초)
                180_000,    // maxBufferMs (3분)
                2_500,      // bufferForPlaybackMs
                5_000       // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(50 * 1024 * 1024)   // 50MB 제한
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

    /**
     * ★ 다음 곡 결정 — 로컬/YouTube 모두 지원
     */
    private suspend fun resolveNext(): VideoItem? {
        if (resolvingNext) return null
        resolvingNext = true
        try {
            val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            val currentVideoId = prefs.getString("current_video_id", "") ?: ""

            // ===== 1) 큐 (로컬 + YouTube) =====
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            if (curIdx >= 0 && curIdx < queue.size - 1) {
                val next = queue[curIdx + 1]
                QueueManager.remove(this, next.videoId)
                Log.d("PlaybackService", "queue next: ${next.videoId}")
                return VideoItem(
                    next.videoId, next.title, next.channel, next.thumbnail
                )
            }

            // ===== 2) 로컬 파일 → LocalMedia 스캔 다음 곡 =====
            if (currentVideoId.startsWith("local:")) {
                val localId = currentVideoId.removePrefix("local:").toLongOrNull()
                if (localId != null) {
                    val local = LocalMediaScanner.scan(this)
                    val idx = local.indexOfFirst { it.id == localId }
                    if (idx >= 0 && idx < local.size - 1) {
                        val nxt = local[idx + 1]
                        Log.d("PlaybackService", "local next: ${nxt.title}")
                        return VideoItem(
                            "local:${nxt.id}",
                            nxt.title,
                            nxt.artist,
                            ""
                        )
                    }
                    Log.d("PlaybackService", "local 끝 (마지막 곡)")
                }
                return null
            }

            // ===== 3) YouTube 관련곡 =====
            if (currentVideoId.isBlank()) return null

            val currentTitle = prefs.getString("current_title", "") ?: ""
            val currentChannel = prefs.getString("current_channel", "") ?: ""
            val currentArtist = prefs.getString("current_artist", "") ?: ""
            val sameArtist = prefs.getBoolean("same_artist_mode", false)
            val disliked = prefs.getStringSet("disliked_ids", emptySet()) ?: emptySet()

            // ★ 3-소스 병렬 + 이력 폴백
            val relatedDeferred = serviceScope.async(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        if (sameArtist && currentArtist.isNotBlank()) {
                            YouTubeArtist.fetchSongs(currentArtist, currentVideoId)
                        } else {
                            YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel)
                        }
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }
            val artistDeferred = serviceScope.async(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        if (currentArtist.isNotBlank())
                            YouTubeArtist.fetchSongs(currentArtist, currentVideoId)
                        else if (currentChannel.isNotBlank())
                            YouTubeArtist.fetchSongs(currentChannel, currentVideoId)
                        else emptyList()
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }

            val related = relatedDeferred.await()
            if (related.isEmpty()) {
                val alt = artistDeferred.await()
                if (alt.isNotEmpty()) {
                    Log.d("PlaybackService", "artist fallback: ${alt.size}")
                    alt.firstOrNull {
                        it.videoId != currentVideoId && it.videoId !in disliked
                    }?.let { return it }
                }
            }

            val found = related.firstOrNull {
                it.videoId != currentVideoId && it.videoId !in disliked
            }
            if (found != null) return found

            // ★ artist 폴백
            if (currentArtist.isNotBlank()) {
                val artistSongs = try {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        YouTubeArtist.fetchSongs(currentArtist, currentVideoId)
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
                artistSongs.firstOrNull {
                    it.videoId != currentVideoId && it.videoId !in disliked
                }?.let { return it }
            }

            // ★ 이력 폴백 (마지막 수단)
            try {
                val history = HistoryDatabase.get(this@PlaybackService)
                    .historyDao().getAll().first()
                val candidates = history
                    .filter { it.videoId != currentVideoId }
                    .filter { it.videoId !in disliked }
                if (candidates.isNotEmpty()) {
                    val pick = candidates.random()
                    Log.d("PlaybackService", "history fallback: ${pick.videoId}")
                    return VideoItem(pick.videoId, pick.title, pick.channel, pick.thumbnail)
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "history fallback err", e)
            }

            return null
        } catch (e: Exception) {
            Log.e("PlaybackService", "resolveNext err", e)
            return null
        } finally {
            resolvingNext = false
        }
    }

    /**
     * ★ 다음 곡 재생 — 로컬/YouTube 모두
     */
    private suspend fun playNext(item: VideoItem) {
        val player = exoPlayer ?: return
        try {
            // ===== 로컬 파일 =====
            if (item.videoId.startsWith("local:")) {
                val localId = item.videoId.removePrefix("local:").toLongOrNull() ?: return
                val isQ = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                val collection = if (isQ)
                    android.provider.MediaStore.Audio.Media
                        .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
                else
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val uri = android.content.ContentUris.withAppendedId(collection, localId)
                val mi = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(item.videoId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.channel)
                            .build()
                    )
                    .build()

                savePrefs(item, "")
                withContext(Dispatchers.Main) {
                    player.setMediaItem(mi)
                    player.prepare()
                    player.playWhenReady = true
                }
                Log.d("PlaybackService", "played local: ${item.title}")
                return
            }

            // ===== YouTube =====
            val result = YouTubeStream.extract(item.videoId)
            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl
            if (url.isNullOrBlank()) {
                Log.e("PlaybackService", "no stream for ${item.videoId}")
                return
            }

            val subUrl = result.subtitles
                .firstOrNull { it.languageCode.startsWith("ko") }?.url
                ?: result.subtitles.firstOrNull { it.languageCode.startsWith("en") }?.url
                ?: result.subtitles.firstOrNull()?.url
                ?: ""

            savePrefs(item, subUrl)

            val mi = MediaItem.Builder()
                .setUri(url)
                .setMediaId(item.videoId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.channel)
                        .setArtworkUri(Uri.parse(item.thumbnail))
                        .build()
                )
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

    private fun savePrefs(item: VideoItem, subUrl: String) {
        getSharedPreferences("audio_prefs", Context.MODE_PRIVATE).edit()
            .putString("current_video_id", item.videoId)
            .putString("current_title", item.title)
            .putString("current_channel", item.channel)
            .putString("current_thumbnail", item.thumbnail)
            .putString("current_artist", item.channel)
            .putString("current_subtitle_url", subUrl)
            .apply()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    /** 앱 스와이프 시 재생 중이면 서비스 유지 */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = exoPlayer
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        // 재생 중이면 stopSelf 안 함 → 백그라운드 유지
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
        super.onDestroy()
    }

    companion object {
        var exoPlayer: ExoPlayer? = null
            private set
        var nextTrackHandler: (() -> Unit)? = null
    }
}
