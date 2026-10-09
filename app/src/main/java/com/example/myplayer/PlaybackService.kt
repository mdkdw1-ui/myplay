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

                // ★ PlayerActivity 활성이면 자동 다음곡 skip
                if (autoNextDisabled) {
                    Log.d("PlaybackService", "autoNextDisabled → skip (Activity 처리)")
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
                60_000,     // minBufferMs (60초)
                600_000,    // maxBufferMs (10분) ★
                5_000,      // bufferForPlaybackMs
                10_000      // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(200 * 1024 * 1024)   // 200MB ★
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
            .setCallback(object : MediaSession.Callback {
                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    // 연결 시 자동 재생 방지
                }

            })
            .build()

        // ★ 알림에서 중단 버튼 → 완전 정지
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // ★ pause는 pause일 뿐 → stop 트리거 X
                //   (완전 정지는 알림 스와이프 or 앱 스와이프로만)
                android.util.Log.d("PlaybackService",
                    "onPlayWhenReadyChanged: playWhenReady=$playWhenReady reason=$reason")
            }
        })

        // ★ Foreground Service 승격 (Doze 모드에서도 네트워크 유지)
        try {
            val pref = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            val bgEnabled = pref.getBoolean("keep_bg_playback", true)
            if (bgEnabled) {
                startForegroundInternal()
            } else {
                Log.d("PlaybackService", "백그라운드 재생 OFF — Foreground 미승격")
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "startForeground err", e)
        }
    }

    /** 알림 생성 + startForeground */
    private fun startForegroundInternal() {
        val channelId = "playback_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val ch = android.app.NotificationChannel(
                    channelId, "재생 중", android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        val intent = Intent(this, AudioPlayerActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("MyPlayer")
            .setContentText("백그라운드 재생 중")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(1001, notif)
            Log.d("PlaybackService", "startForeground OK")
        } catch (e: Exception) {
            Log.e("PlaybackService", "startForeground failed: ${e.message}", e)
        }
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
                // ★ 큐에서 제거하지 않음 (순서 유지)
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

    /** ★ 커스텀 알림 (스와이프 시 서비스 정지) */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)

        // ★ deleteIntent 추가 — 사용자가 알림 스와이프 시 정지
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "playback_channel"
            val mgr = nm.getNotificationChannel(channelId)
            if (mgr == null) {
                val ch = android.app.NotificationChannel(
                    channelId, "재생 중", android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(ch)
            }

            val stopIntent = Intent(this, StopServiceReceiver::class.java).apply {
                action = "STOP_SERVICE_FROM_NOTIFICATION"
            }
            val deletePending = PendingIntent.getBroadcast(
                this, 100, stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // ★ Media3가 만든 알림에 deleteIntent 적용
            val notif = android.app.Notification.Builder(this, channelId)
                .setContentTitle(session.player.mediaMetadata.title ?: "MyPlayer")
                .setContentText(session.player.mediaMetadata.artist ?: "백그라운드 재생 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setDeleteIntent(deletePending)
                .setStyle(
                    androidx.media3.session.MediaNotification.Provider // 컴파일 에러 방지용
                        ?.let { null } // no-op
                )
                .build()

            // ★ 커스텀 알림으로 덮어쓰기
            nm.notify(1001, notif)
            android.util.Log.d("PlaybackService", "커스텀 알림 적용 (deleteIntent)")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "onUpdateNotification err: ${e.message}", e)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    /** 앱 스와이프 시 재생 중이면 서비스 유지 */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // ★ 앱 스와이프 시 완전 정지 (백그라운드 재생 유지 X)
        android.util.Log.d("PlaybackService", "onTaskRemoved → 완전 정지")
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "stop err", e)
        }
        try { stopForeground(true) } catch (_: Exception) {}
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { stopForeground(true) } catch (_: Exception) {}
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
        // ★ PlayerActivity가 활성이면 자동 다음곡 비활성화
        @Volatile var autoNextDisabled: Boolean = false

    /** ★ video+audio 병합 재생 (MediaController로는 불가) */
    fun playMergedVideoAudio(
        videoUrl: String,
        audioUrl: String,
        title: String,
        channel: String,
        videoId: String
    ): Boolean {
        val player = exoPlayer ?: return false
        return try {
            val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0")
                .setAllowCrossProtocolRedirects(true)

            val videoSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(videoUrl)
                        .setMediaId("${videoId}_video")
                        .build()
                )

            val audioSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(audioUrl)
                        .setMediaId("${videoId}_audio")
                        .build()
                )

            val mergedSource = androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    player.setMediaSource(mergedSource)
                    player.prepare()
                    player.playWhenReady = true
                    Log.d("PlaybackService", "playMergedVideoAudio OK: $videoId")
                } catch (e: Exception) {
                    Log.e("PlaybackService", "setMediaSource err", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("PlaybackService", "playMergedVideoAudio err", e)
            false
        }
    }

    }
}
