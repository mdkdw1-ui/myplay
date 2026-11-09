package com.example.myplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
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
    private val playbackHistory = ArrayDeque<String>()

private var resolvingNext = false

    private val endListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newId = mediaItem?.mediaId ?: return

            // ★★★ 모든 곡 (로컬 + YouTube) prefs/QueueManager 업데이트 (근본 해결)
            val title = mediaItem.mediaMetadata.title?.toString() ?: ""
            val channel = mediaItem.mediaMetadata.artist?.toString() ?: ""
            val thumb = mediaItem.mediaMetadata.artworkUri?.toString() ?: ""

            val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("current_video_id", newId)
                .putString("current_title", title)
                .putString("current_channel", channel)
                .putString("current_thumbnail", thumb)
                .apply()
            QueueManager.setCurrent(this@PlaybackService, newId)

            // ★★★ current 곡이 큐에 없으면 자동 추가 (prefetch/updateNextSongs curIdx 정상화)
            try {
                val queue = QueueManager.get(this@PlaybackService)
                if (queue.none { it.videoId == newId }) {
                    QueueManager.add(this@PlaybackService,
                        HomeVideo(newId, title, channel, thumb))
                    Log.d("PlaybackService", "current 곡을 큐에 자동 추가: $newId")
                }
            } catch (_: Exception) {}

            Log.d("PlaybackService", "transition → $newId (title=${title.take(20)})")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // ★ 블루투스 Next/Prev 신호로 인한 위치 이동 감지 ★
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val currentId = newPosition.mediaItem?.mediaId
                if (currentId != null && currentId != newPosition.mediaItem?.mediaId) {
                    // 큐에 다음 곡이 없으면 다음 곡 로드
                    val player = exoPlayer ?: return
                    if (!player.hasNextMediaItem()) {
                        Log.d("PlaybackService", "onPositionDiscontinuity: 큐 끝, 다음 곡 필요")
                        // 서비스 내에서 다음 곡 로드 시도
                        serviceScope.launch {
                            val next = resolveNext()
                            if (next != null) playNext(next)
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val p = exoPlayer

                // ★★★ autoNextDisabled 무시 → Service가 항상 다음 곡 처리 (근본 해결)
                if (p != null && p.hasNextMediaItem()) {
                    Log.d("PlaybackService", "STATE_ENDED but hasNext → skip (자동 진행)")
                    return
                }

                Log.d("PlaybackService", "STATE_ENDED → resolveNext (autoNextDisabled 무시)")
                serviceScope.launch {
                    val next = resolveNext()
                    if (next != null) playNext(next)
                    else Log.d("PlaybackService", "no next track")
                }
            }
        }
    }

    
    // ★★★ 블루투스/테슬라 Next/Prev 신호 직접 수신 ★★★
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        try {
            val keyCode = intent?.getIntExtra("android.intent.extra.KEY_EVENT", -1) ?: -1
            // KEYCODE_MEDIA_NEXT (87), KEYCODE_MEDIA_PREVIOUS (88)
            when (keyCode) {
                87 -> {
                    Log.d("PlaybackService", "onStartCommand: KEYCODE_MEDIA_NEXT")
                    val player = exoPlayer
                    if (player != null && player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    } else {
                        // 큐 끝 → 서비스에서 다음 곡 로드
                        serviceScope.launch {
                            val next = resolveNext()
                            if (next != null) playNext(next)
                        }
                    }
                }
                88 -> {
                    Log.d("PlaybackService", "onStartCommand: KEYCODE_MEDIA_PREVIOUS")
                    val player = exoPlayer
                    if (player != null && player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player?.seekTo(0)
                    }
                }
                else -> {
                    Log.d("PlaybackService", "onStartCommand: keyCode=$keyCode (기타)")
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "onStartCommand err: ${e.message}")
        }
        return super.onStartCommand(intent, flags, startId)
    }

override fun onCreate() {
        super.onCreate()
        instance = this   // ★ 인스턴스 저장

        // ★ 완전 종료 플래그 감지 → 해제하고 계속 (로딩 저하 방지)
        val stopFlag = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_stopped", false)
        if (stopFlag) {
            Log.d("PlaybackService", "service_stopped=true 감지 → 플래그 해제 후 계속")
            getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("service_stopped", false)
                .apply()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,      // minBufferMs: 2초
                60_000,     // maxBufferMs: 1분
                100,        // bufferForPlaybackMs: 100ms
                1_000       // bufferForPlaybackAfterRebufferMs: 1초
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(50 * 1024 * 1024)   // 200 → 50MB
            .build()

        // ★ content:// (로컬 파일), http:// (스트리밍) 모두 지원하는 DataSource
        // ★ newpipe-local URL은 폰 IP로 재생 (WARP 프록시 우회)
        // ★ WARP 프록시 (서버 40001) 경유 - 이 앱만 WARP 통과
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12) gzip")
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this, mediaSourceFactory)
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
                override fun onMediaButtonEvent(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
                        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                        when (key?.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                                nextTrack()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                                previousTrack()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                seekBy(-10000L)
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                seekBy(10000L)
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaSession, controller, intent)
                }

                override fun onSkipToNext() {
                    Log.d("PlaybackService", "Tesla: onSkipToNext")
                    nextTrack()
                }

                override fun onSkipToPrevious() {
                    Log.d("PlaybackService", "Tesla: onSkipToPrevious")
                    previousTrack()
                }

                override fun onFastForward() {
                    Log.d("PlaybackService", "Tesla: onFastForward")
                    seekBy(10000L)
                }

                override fun onRewind() {
                    Log.d("PlaybackService", "Tesla: onRewind")
                    seekBy(-10000L)
                }

                override fun onSeekTo(pos: Long) {
                    Log.d("PlaybackService", "Tesla: onSeekTo $pos")
                    exoPlayer?.seekTo(pos)
                }

                override fun onPlay() {
                    Log.d("PlaybackService", "Tesla: onPlay")
                    exoPlayer?.play()
                }

                override fun onPause() {
                    Log.d("PlaybackService", "Tesla: onPause")
                    exoPlayer?.pause()
                }

                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    // 연결 시 자동 재생 방지
                }
            })
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.d("PlaybackService",
                    "onPlayWhenReadyChanged: playWhenReady=$playWhenReady reason=$reason")
            }
        })

        player.addListener(notifListener)

        try {
            val pref = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            val bgEnabled = pref.getBoolean("keep_bg_playback", true)
            if (bgEnabled) {
                startForegroundWithCustomNotification()
            } else {
                Log.d("PlaybackService", "백그라운드 재생 OFF — Foreground 미승격")
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "startForeground err", e)
        }
    }

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
            .setOngoing(false)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(1001, notif)
            Log.d("PlaybackService", "startForeground OK")
        } catch (e: Exception) {
            Log.e("PlaybackService", "startForeground failed: ${e.message}", e)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        val duration = player.duration
        val current = player.currentPosition
        val target = if (duration > 0L) {
            (current + deltaMs).coerceIn(0L, duration)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        player.seekTo(target)
        Log.d("PlaybackService", "seekBy $deltaMs -> $target")
    }

    private fun nextTrack() {
        serviceScope.launch {
            val next = resolveNext()
            if (next != null) {
                playNext(next)
            } else {
                Log.d("PlaybackService", "nextTrack: no next")
            }
        }
    }

    private fun previousTrack() {
        val player = exoPlayer ?: return

        // 3초 초과 → 현재 곡 처음으로
        if (player.currentPosition > 3000L) {
            player.seekTo(0L)
            return
        }

        // 3초 이내 → 직전 재생 곡으로 이동
        serviceScope.launch {
            try {
                val currentId = player.currentMediaItem?.mediaId
                var prevId: String? = null

                while (playbackHistory.isNotEmpty()) {
                    val candidate = playbackHistory.removeLast()
                    if (candidate != currentId) {
                        prevId = candidate
                        break
                    }
                }

                if (prevId.isNullOrBlank()) {
                    player.seekTo(0L)
                    return@launch
                }

                val queue = QueueManager.get(this@PlaybackService)
                val prev = queue.firstOrNull { it.videoId == prevId }

                playNext(
                    VideoItem(
                        prevId,
                        prev?.title ?: "",
                        prev?.channel ?: "",
                        prev?.thumbnail ?: ""
                    )
                )
            } catch (e: Exception) {
                Log.e("PlaybackService", "previousTrack err", e)
            }
        }
    }

    private suspend fun resolveNext(): VideoItem? {
        if (resolvingNext) return null
        resolvingNext = true
        try {
            val prefs = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            val currentVideoId = prefs.getString("current_video_id", "") ?: ""

            // ★★★ 현재 곡이 큐에 없으면 자동 추가 (curIdx 정상화)
            try {
                if (currentVideoId.isNotBlank() && !currentVideoId.startsWith("local:")) {
                    val q = QueueManager.get(this)
                    if (q.none { it.videoId == currentVideoId }) {
                        val title = prefs.getString("current_title", "") ?: currentVideoId
                        val channel = prefs.getString("current_channel", "") ?: "YouTube"
                        val thumb = prefs.getString("current_thumbnail", "") ?: ""
                        QueueManager.add(this, HomeVideo(currentVideoId, title, channel, thumb))
                        Log.d("PlaybackService", "resolveNext: 현재 곡 큐에 자동 추가 $currentVideoId")
                    }
                }
            } catch (_: Exception) {}

            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            if (curIdx >= 0 && curIdx < queue.size - 1) {
                val next = queue[curIdx + 1]
                Log.d("PlaybackService", "queue next: ${next.videoId}")
                return VideoItem(
                    next.videoId, next.title, next.channel, next.thumbnail
                )
            }

            if (currentVideoId.startsWith("local:")) {
                // ★ local:download:prefix 처리
                val localId = currentVideoId
                    .removePrefix("local:download:")
                    .removePrefix("local:")
                    .toLongOrNull()
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
                }
                // ★ local:download는 서비스에서 처리 불가 (Activity가 담당)
                Log.d("PlaybackService", "local 끝 → null")
                return null
            }

            if (currentVideoId.isBlank()) return null

            val currentTitle = prefs.getString("current_title", "") ?: ""
            val currentChannel = prefs.getString("current_channel", "") ?: ""
            val currentArtist = prefs.getString("current_artist", "") ?: ""
            val sameArtist = prefs.getBoolean("same_artist_mode", false)
            val disliked = prefs.getStringSet("disliked_ids", emptySet()) ?: emptySet()

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

    private suspend fun playNext(item: VideoItem) {
        val player = exoPlayer ?: return
        try {
            player.currentMediaItem?.mediaId?.let { currentId ->
                if (currentId.isNotBlank() && currentId != item.videoId) {
                    playbackHistory.remove(currentId)
                    playbackHistory.addLast(currentId)
                    while (playbackHistory.size > 30) {
                        playbackHistory.removeFirst()
                    }
                }
            }
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

    private fun buildCustomNotification(): android.app.Notification {
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

        val player = exoPlayer
        val title = player?.mediaMetadata?.title?.toString() ?: "MyPlayer"
        val artist = player?.mediaMetadata?.artist?.toString() ?: "백그라운드 재생 중"
        val isPlaying = player?.isPlaying == true

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AudioPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ★ deleteIntent: 알림 스와이프 시 완전 종료
        val deleteIntent = PendingIntent.getBroadcast(
            this, 100,
            Intent(this, StopServiceReceiver::class.java).apply {
                action = "STOP_SERVICE_FROM_NOTIFICATION"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getBroadcast(
            this, 101,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = "ACTION_PREV"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            this, 102,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = "ACTION_PLAY_PAUSE"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getBroadcast(
            this, 103,
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = "ACTION_NEXT"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getBroadcast(
            this, 104,
            Intent(this, StopServiceReceiver::class.java).apply {
                action = "STOP_SERVICE_FROM_NOTIFICATION"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setOngoing(false)
            .setAutoCancel(false)
            .setSilent(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            // ★ 미디어 스타일 (아이콘 표시용)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        builder.addAction(
            android.R.drawable.ic_media_previous, "이전", prevIntent
        )
        builder.addAction(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
            if (isPlaying) "일시정지" else "재생",
            playPauseIntent
        )
        builder.addAction(
            android.R.drawable.ic_media_next, "다음", nextIntent
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "종료", stopIntent
        )

        return builder.build()
    }

    private fun startForegroundWithCustomNotification() {
        try {
            val notif = buildCustomNotification()
            startForeground(1001, notif)
            Log.d("PlaybackService", "startForeground 커스텀 알림 OK")
        } catch (e: Exception) {
            Log.e("PlaybackService", "startForeground err", e)
        }
    }

    fun refreshNotification() {
        try {
            // ★ service_stopped면 알림 재생성 금지
            val stopped = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
                .getBoolean("service_stopped", false)
            if (stopped) {
                Log.d("PlaybackService", "service_stopped → 알림 재생성 스킵")
                return
            }

            val notif = buildCustomNotification()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(1001, notif)
            Log.d("PlaybackService", "커스텀 알림 갱신")
        } catch (e: Exception) {
            Log.e("PlaybackService", "알림 갱신 err", e)
        }
    }

    private val notifListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            refreshNotification()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshNotification()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshNotification()
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        Log.e("PlaybackService", "🔥 onUpdateNotification 호출됨!")
        try {
            java.io.File(filesDir, "notif_debug.log").appendText(
                "[${System.currentTimeMillis()}] onUpdateNotification 호출\n"
            )
        } catch (_: Exception) {}

        // ★ service_stopped면 알림 재생성 금지
        val stopped = getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
            .getBoolean("service_stopped", false)
        if (stopped) {
            Log.d("PlaybackService", "service_stopped → onUpdateNotification 스킵")
            return
        }

        try {
            val notif = buildCustomNotification()
            startForeground(1001, notif)
            Log.d("PlaybackService", "onUpdateNotification → 커스텀 알림")
        } catch (e: Exception) {
            Log.e("PlaybackService", "onUpdateNotification err: ${e.message}", e)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        // ★★★ [수정] mediaSession이 null이면 재생성 ★★★
        if (mediaSession == null) {
            try {
                val player = exoPlayer
                if (player != null) {
                    mediaSession = MediaSession.Builder(this, player)
                        .setSessionActivity(PendingIntent.getActivity(
                            this, 0,
                            Intent(this, PlayerActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        ))
                        .setCallback(object : MediaSession.Callback {
                            override fun onSkipToNext() {
                                Log.d("PlaybackService", "Tesla(re): onSkipToNext")
                                nextTrack()
                            }

                            override fun onSkipToPrevious() {
                                Log.d("PlaybackService", "Tesla(re): onSkipToPrevious")
                                previousTrack()
                            }

                            override fun onFastForward() {
                                Log.d("PlaybackService", "Tesla(re): onFastForward")
                                seekBy(10000L)
                            }

                            override fun onRewind() {
                                Log.d("PlaybackService", "Tesla(re): onRewind")
                                seekBy(-10000L)
                            }

                            override fun onSeekTo(pos: Long) {
                                Log.d("PlaybackService", "Tesla(re): onSeekTo $pos")
                                exoPlayer?.seekTo(pos)
                            }

                            override fun onPlay() {
                                Log.d("PlaybackService", "Tesla(re): onPlay")
                                exoPlayer?.play()
                            }

                            override fun onPause() {
                                Log.d("PlaybackService", "Tesla(re): onPause")
                                exoPlayer?.pause()
                            }

                            override fun onPostConnect(
                                session: MediaSession,
                                controller: MediaSession.ControllerInfo
                            ) {}
                        })
                        .build()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "onGetSession 재생성 err: ${e.message}")
            }
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        Log.d("PlaybackService", "onTaskRemoved → 완전 정지")
        try {
            // ★ 완전 종료 플래그
            getSharedPreferences("audio_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("service_stopped", true)
                .putString("current_video_id", "")
                .putBoolean("auto_next", false)
                .apply()

            QueueManager.clear(this)

            try {
                exoPlayer?.removeListener(endListener)
                exoPlayer?.removeListener(notifListener)
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                exoPlayer?.release()
                exoPlayer = null
            } catch (e: Exception) {
                Log.e("PlaybackService", "stop err", e)
            }

            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(1001)
            } catch (_: Exception) {}

            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
        } catch (e: Exception) {
            Log.e("PlaybackService", "onTaskRemoved err", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null   // ★ 참조 해제
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        mediaSession?.run {
            player.removeListener(endListener)
            player.removeListener(notifListener)
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
        @Volatile var autoNextDisabled: Boolean = false

        // ★ 서비스 인스턴스 참조 (StopServiceReceiver에서 사용)
        @Volatile var instance: PlaybackService? = null
            private set

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
                    .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12) gzip")
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

// ═══════════════════════════════════════════════════════════════
// ★ 알림 제어용 BroadcastReceiver
// ═══════════════════════════════════════════════════════════════

class StopServiceReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        Log.d("StopServiceReceiver", "action=${intent.action}")
        try {
            // ★ 1. 완전 종료 플래그
            try {
                context.getSharedPreferences("audio_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("service_stopped", true)
                    .putString("current_video_id", "")
                    .putBoolean("auto_next", false)
                    .apply()
            } catch (_: Exception) {}

            // ★ 2. ExoPlayer 정지
            try {
                val player = PlaybackService.exoPlayer
                player?.stop()
                player?.clearMediaItems()
                player?.playWhenReady = false
                player?.release()
            } catch (_: Exception) {}

            // ★ 3. 큐 초기화
            try { QueueManager.clear(context) } catch (_: Exception) {}

            // ★ 4. Foreground Service 중지 + 알림 제거 (순서 중요!)
            try {
                val service = PlaybackService.instance
                if (service != null) {
                    // ★ stopForeground를 먼저 호출 (알림 자동 제거)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        service.stopForeground(
                            android.app.Service.STOP_FOREGROUND_REMOVE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        service.stopForeground(true)
                    }
                    Log.d("StopServiceReceiver", "stopForeground(REMOVE) 완료")
                }
            } catch (e: Exception) {
                Log.e("StopServiceReceiver", "stopForeground err: ${e.message}", e)
            }

            // ★ 5. NotificationManager로도 확실히 제거
            try {
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                nm.cancel(1001)
                // ❌ nm.cancelAll() 제거 — 구독 알림 보존
                Log.d("StopServiceReceiver", "알림 강제 취소 완료")
            } catch (_: Exception) {}

            // ★ 6. 서비스 정지
            try {
                context.stopService(android.content.Intent(context, PlaybackService::class.java))
                Log.d("StopServiceReceiver", "stopService 호출")
            } catch (_: Exception) {}

            Log.d("StopServiceReceiver", "✅ 완전 종료 완료")
        } catch (e: Exception) {
            Log.e("StopServiceReceiver", "stopService err", e)
        }
    }
}

class NotificationActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        Log.d("NotificationActionReceiver", "action=${intent.action}")

        // ★★★ ACTION_SAVE_LATER: 나중에 목록 저장 + 알림 제거 ★★★
        if (intent.action == "ACTION_SAVE_LATER") {
            val videoId = intent.getStringExtra("VIDEO_ID") ?: return
            val title = intent.getStringExtra("VIDEO_TITLE") ?: ""
            val channel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
            val thumb = intent.getStringExtra("VIDEO_THUMB") ?: ""
            val notifId = intent.getIntExtra("NOTIF_ID", 7001)

            try {
                val prefs = context.getSharedPreferences(
                    "sub_notif_later", android.content.Context.MODE_PRIVATE
                )
                val existing = prefs.getStringSet("items", mutableSetOf()) ?: mutableSetOf()
                val newItems = existing.toMutableSet()

                val entry = "$videoId|$title|$channel|$thumb|${System.currentTimeMillis()}"
                newItems.add(entry)

                // 최대 50개 유지
                if (newItems.size > 50) {
                    val sorted = newItems.sortedByDescending {
                        it.substringAfterLast('|').toLongOrNull() ?: 0
                    }
                    newItems.clear()
                    newItems.addAll(sorted.take(50))
                }

                prefs.edit().putStringSet("items", newItems).apply()

                Log.d("NotificationActionReceiver",
                    "나중에 저장: $videoId (${title.take(30)}), 총 ${newItems.size}개")

                // ★ 개별 알림만 제거 (그룹 요약은 유지)
                // ★ nm.cancel(7000) 호출 시 그룹 전체가 제거되므로 절대 호출 X
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                nm.cancel(notifId)
                Log.d("NotificationActionReceiver", "알림 $notifId 만 제거 (그룹 유지)")
            } catch (e: Exception) {
                Log.e("NotificationActionReceiver", "나중에 저장 err: ${e.message}", e)
            }
            return
        }

        // ★ 기존 액션 (재생 컨트롤)
        val player = PlaybackService.exoPlayer ?: return
        when (intent.action) {
            "ACTION_PREV" -> {
                if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                else player.seekTo(0)
            }
            "ACTION_PLAY_PAUSE" -> {
                if (player.isPlaying) {
                    player.pause()
                    player.playWhenReady = false
                } else {
                    player.play()
                }
            }
            "ACTION_NEXT" -> {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        }
    }
}
