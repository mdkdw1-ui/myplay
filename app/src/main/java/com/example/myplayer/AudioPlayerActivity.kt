package com.example.myplayer

import android.content.ComponentName
import android.view.View
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var updateJob: Job? = null
    private var pulseAnimator: android.animation.ValueAnimator? = null
    private var isDragging = false
    private val bgScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""
    private var currentArtist: String = ""
    private var currentSubtitleUrl: String = ""
    private var sameArtistMode: Boolean = false
    private var loadingNext = false
    private var audioLiveActive = false
    private var reuseStreamUrl: String = ""
    private var reuseSubtitleUrl: String = ""

    private val audioHistory = mutableListOf<AudioHistoryItem>()
    private var historyIndex = -1
    private var isPlayingFromHistory = false

    data class AudioHistoryItem(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnail: String
    )

    private var pref: android.content.SharedPreferences? = null
    private var localOnlyMode: Boolean = false

    private lateinit var ivArt: ImageView
    private lateinit var ivBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnLiveSub: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        pref = getSharedPreferences("audio_prefs", MODE_PRIVATE)

        ivArt = findViewById(R.id.ivArt)
        ivBackground = findViewById(R.id.ivBackground)
        tvTitle = findViewById(R.id.tvTitle)
        tvChannel = findViewById(R.id.tvChannel)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        seekBar = findViewById(R.id.seekBar)
        seekBar.max = 1000
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnLiveSub = findViewById(R.id.btnLiveSub)

        currentVideoId = intent.getStringExtra("VIDEO_ID") ?: ""
        val localUri = intent.getStringExtra("LOCAL_URI") ?: ""
        if (localUri.isNotBlank()) {
            // 로컬 파일 바로 재생
            lifecycleScope.launch {
                delay(500)
                try {
                    val mi = MediaItem.fromUri(localUri)
                    mediaController?.setMediaItem(mi)
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                } catch (e: Exception) { }
            }
        }
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist) QueueManager.clear(this)

        reuseStreamUrl = intent.getStringExtra("REUSE_STREAM_URL") ?: ""
        reuseSubtitleUrl = intent.getStringExtra("REUSE_SUBTITLE_URL") ?: ""

        updateUI()

        sameArtistMode = pref?.getBoolean("same_artist_mode", false) ?: false
        localOnlyMode = pref?.getBoolean("local_only_mode", false) ?: false
        val swLocalOnly = findViewById<SwitchMaterial>(R.id.swLocalOnly)
        swLocalOnly.isChecked = localOnlyMode
        swLocalOnly.setOnCheckedChangeListener { _, checked ->
            localOnlyMode = checked
            pref?.edit()?.putBoolean("local_only_mode", checked)?.apply()
            Toast.makeText(
                this,
                if (checked) "🎧 로컬 큐만 재생" else "🌐 유튜브 연관곡 사용",
                Toast.LENGTH_SHORT
            ).show()
        }

        val swArtist = findViewById<SwitchMaterial>(R.id.swSameArtist)
        swArtist.isChecked = sameArtistMode
        swArtist.setOnCheckedChangeListener { _, checked ->
            sameArtistMode = checked
            pref?.edit()?.putBoolean("same_artist_mode", checked)?.apply()
        }

        acquireWakeLock()

        updateAudioLiveSubButton()

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            attachListeners()
            attachPlayerListener()
            startUpdateLoop()
            attachEqualizer()
            if (currentVideoId.isNotEmpty()) loadAudio(currentVideoId, isInitial = true)
        }, MoreExecutors.directExecutor())
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyPlayer::AudioPlayback").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) { }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
    }

    private fun attachEqualizer() {
        val session = PlaybackService.exoPlayer?.audioSessionId ?: 0
        if (session != 0) EqualizerManager.attach(session)
    }

    private fun updateUI() {

        // Feature: 로컬 파일이면 영상 모드 버튼 숨김
        val isLocal = currentVideoId.startsWith("local:") || currentVideoId.isBlank()
        findViewById<View>(R.id.btnVideoMode)?.visibility = if (isLocal) View.GONE else View.VISIBLE
                tvTitle.text = currentTitle
        tvChannel.text = currentChannel
        if (currentThumb.isNotEmpty()) {
            Glide.with(this).load(currentThumb).into(ivArt)
            Glide.with(this).load(currentThumb).into(ivBackground)
        }
        extractArtistAsync()
    }

    private fun extractArtistAsync() {
        val vid = currentVideoId
        val t = currentTitle
        val c = currentChannel
        bgScope.launch {
            try {
                val result = ArtistExtractor.extract(vid, t, c)
                if (vid == currentVideoId) {
                    currentArtist = result
                    // ★ 아티스트 확정 후 메타데이터 재갱신 (블루투스용)
                    runOnUiThread { refreshMediaMetadata() }
                }
            } catch (e: Exception) { }
        }
    }

    /** ★ 블루투스/알림용 메타데이터 갱신 */
    private fun refreshMediaMetadata() {
        val mc = mediaController ?: return
        val item = mc.currentMediaItem ?: return
        val url = item.localConfiguration?.uri?.toString() ?: return

        val artist = currentArtist.ifBlank { currentChannel }
        val metadata = MediaMetadata.Builder()
            .setTitle(currentTitle)
            .setArtist(artist)
            .setAlbumTitle(currentChannel)
            .setArtworkUri(android.net.Uri.parse(currentThumb))
            .build()

        val newItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(currentVideoId)
            .setMediaMetadata(metadata)
            .build()

        val pos = mc.currentPosition
        val wasPlaying = mc.isPlaying
        mc.setMediaItem(newItem, pos)
        mc.prepare()
        if (wasPlaying) mc.play()
    }

    private fun loadAudio(videoId: String, isInitial: Boolean = false) {
        // 로컬 파일이면 YouTubeStream.extract 스킵
        if (videoId.startsWith("local:")) {
            bgScope.launch {
                try {
                    // ★ 로컬 큐 전체를 ExoPlayer에 넣기 → 자동 다음곡
                    val queue = QueueManager.get(this@AudioPlayerActivity)
                    val localQueue = queue.filter { it.videoId.startsWith("local:") }
                    if (localQueue.size >= 1) {
                        // MediaStore에서 각 항목의 URI 조회 (캐시 활용)
                        val scan = LocalMediaScanner.scan(this@AudioPlayerActivity, forceRefresh = false)
                        val items = mutableListOf<MediaItem>()
                        var startIdx = 0
                        localQueue.forEachIndexed { idx, item ->
                            val localId = item.videoId.removePrefix("local:").toLongOrNull()
                            val found = scan.firstOrNull { it.id == localId }
                            if (found != null) {
                                // ★ mediaId = "local:${id}" 형식 유지 → onMediaItemTransition 매칭
                                val meta = androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.channel)
                                    .build()
                                val mi = MediaItem.Builder()
                                    .setUri(found.uri.toString())
                                    .setMediaId(item.videoId)
                                    .setMediaMetadata(meta)
                                    .build()
                                items.add(mi)
                                if (item.videoId == videoId) startIdx = items.size - 1
                            }
                        }
                        if (items.isNotEmpty()) {
                            runOnUiThread {
                                mediaController?.setMediaItems(items, startIdx, 0L)
                                mediaController?.prepare()
                                mediaController?.playWhenReady = true
                            }
                            return@launch
                        }
                    }
                    // 폴백: LOCAL_URI 하나만
                    val uri = intent.getStringExtra("LOCAL_URI")
                    if (uri != null) {
                        val mi = MediaItem.fromUri(uri)
                        runOnUiThread {
                            mediaController?.setMediaItem(mi)
                            mediaController?.prepare()
                            mediaController?.playWhenReady = true
                        }
                    }
                } catch (e: Exception) { }
            }
            return
        }
        bgScope.launch {
            // ★ URL 재사용 (첫 곡만)
            if (isInitial && reuseStreamUrl.isNotBlank() && videoId == currentVideoId) {
                currentSubtitleUrl = reuseSubtitleUrl
                val artist = currentArtist.ifBlank { currentChannel }
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(artist)
                    .setAlbumTitle(currentChannel)
                    .setArtworkUri(android.net.Uri.parse(currentThumb))
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(reuseStreamUrl)
                    .setMediaId(videoId)
                    .setMediaMetadata(metadata)
                    .build()
                runOnUiThread {
                    mediaController?.setMediaItem(mediaItem)
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                }
                addToHistory(videoId, currentTitle, currentChannel, currentThumb)
                reuseStreamUrl = ""
                reuseSubtitleUrl = ""
                delay(500)
                attachEqualizer()
                loadingNext = false
                return@launch
            }

            if (isInitial) {
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "오디오 추출 중...", Toast.LENGTH_SHORT).show()
                }
            }

            val result = YouTubeStream.extract(videoId)

            // ★ 라이브 스킵
            if (result.isLive) {
                android.util.Log.d("AudioPlayer", "skip LIVE: $videoId")
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "라이브는 오디오 모드 제외", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                // 자동으로 다음 곡
                bgScope.launch { delay(500); playNextRelatedBg() }
                return@launch
            }

            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl

            if (url.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "오디오 없음", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                return@launch
            }

            // ★ 자막 URL - 자동생성 포함, 언어 우선순위
            currentSubtitleUrl = result.subtitles
                .firstOrNull { it.languageCode.startsWith("ko") }?.url
                ?: result.subtitles.firstOrNull { it.languageCode.startsWith("en") }?.url
                ?: result.subtitles.firstOrNull()?.url
                ?: ""

            android.util.Log.d("AudioPlayer", "videoId=$videoId, subs=${result.subtitles.size}, subUrl=${currentSubtitleUrl.take(50)}")

            // ★ MediaMetadata 세팅 (블루투스용)
            val artist = currentArtist.ifBlank { currentChannel }
            val metadata = MediaMetadata.Builder()
                .setTitle(currentTitle)
                .setArtist(artist)
                .setAlbumTitle(currentChannel)
                .setArtworkUri(android.net.Uri.parse(currentThumb))
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(videoId)
                .setMediaMetadata(metadata)
                .build()

            // ★ prefs에 현재 곡 저장 (Service가 다음 곡 결정 시 사용)
            pref?.edit()
                ?.putString("current_video_id", videoId)
                ?.putString("current_title", currentTitle)
                ?.putString("current_channel", currentChannel)
                ?.putString("current_thumbnail", currentThumb)
                ?.putString("current_artist", currentArtist)
                ?.putString("current_subtitle_url", currentSubtitleUrl)
                ?.apply()

            runOnUiThread {
                mediaController?.setMediaItem(mediaItem)
                mediaController?.prepare()
                mediaController?.playWhenReady = true
                updateUI()
            }

            addToHistory(videoId, currentTitle, currentChannel, currentThumb)

            delay(500)
            attachEqualizer()
            loadingNext = false
        }
    }

    private fun attachPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startAnimation() else stopAnimation()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // ExoPlayer 큐 다 소진 → 다음곡 로직 발동 (한 번만)
                if (playbackState == Player.STATE_ENDED) {
                    android.util.Log.d("AudioPlayer", "STATE_ENDED → playNextRelatedBg")
                    if (!loadingNext) {
                        loadingNext = true
                        bgScope.launch {
                            kotlinx.coroutines.delay(300)
                            playNextRelatedBg()
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("AudioPlayer",
                    "onPlayerError: ${error.errorCodeName} / ${error.message}", error)
                // ★ 재생 실패 시 다음 곡으로 강제 진행
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@AudioPlayerActivity,
                        "재생 실패: ${error.errorCodeName}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                val mc = mediaController
                if (mc != null && mc.hasNextMediaItem()) {
                    mc.seekToNextMediaItem()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newId = mediaItem?.mediaId ?: return
                if (newId == currentVideoId) return
                android.util.Log.d("AudioPlayer", "external transition to $newId")
                currentVideoId = newId
                currentTitle = mediaItem.mediaMetadata.title?.toString() ?: currentTitle
                currentChannel = mediaItem.mediaMetadata.artist?.toString() ?: currentChannel
                currentThumb = mediaItem.mediaMetadata.artworkUri?.toString() ?: currentThumb
                currentArtist = ""
                currentSubtitleUrl = pref?.getString("current_subtitle_url", "") ?: ""

                // ★ QueueManager 현재곡 동기화 (localOnlyMode 큐 필터 정확도)
                QueueManager.setCurrent(this@AudioPlayerActivity, newId)

                // ★ 로컬 파일이면 prefs도 갱신
                if (newId.startsWith("local:")) {
                    pref?.edit()
                        ?.putString("current_video_id", newId)
                        ?.putString("current_title", currentTitle)
                        ?.putString("current_channel", currentChannel)
                        ?.apply()
                }

                runOnUiThread {
                    updateUI()
                    updateLyricsButtonLabel()
                }
            }
        })
    }

    private fun playNextRelatedBg() {
        // ★ ExoPlayer 큐에 다음 곡 남아있으면 처리하지 않음 (ExoPlayer가 자동 진행)
        val mc = mediaController
        if (mc != null && mc.hasNextMediaItem()) {
            android.util.Log.d("AudioPlayer", "ExoPlayer 큐 있음 → playNextRelatedBg skip")
            loadingNext = false
            return
        }

        if (!isPlayingFromHistory && historyIndex >= 0 && historyIndex < audioHistory.size - 1) {
            historyIndex++
            val next = audioHistory[historyIndex]
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            runOnUiThread { updateUI() }
            loadAudio(next.videoId, isInitial = false)
            return
        }
        isPlayingFromHistory = false

        // ★ Feature 8: 인덱스 기반 큐 (로컬 파일도 지원)
        val queue = QueueManager.get(this)
        val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
        if (curIdx >= 0 && curIdx < queue.size - 1) {
            val next = queue[curIdx + 1]
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            runOnUiThread { updateUI() }
            // 로컬 파일이면 LOCAL_URI로 재생
            if (next.videoId.startsWith("local:")) {
                // QueueManager에서 저장한 로컬 정보가 없을 수 있어 LocalMedia에서 찾기
                val localId = next.videoId.removePrefix("local:").toLongOrNull()
                if (localId != null) {
                    bgScope.launch {
                        val local = LocalMediaScanner.scan(this@AudioPlayerActivity, forceRefresh = false)
                            .firstOrNull { it.id == localId }
                        if (local != null) {
                            runOnUiThread {
                                val mi = androidx.media3.common.MediaItem.fromUri(local.uri.toString())
                                mediaController?.setMediaItem(mi)
                                mediaController?.prepare()
                                mediaController?.playWhenReady = true
                            }
                        }
                    }
                }
            } else {
                loadAudio(next.videoId, isInitial = false)
            }
            return
        }

        // ★ 로컬 전용 모드: 큐에 로컬곡 남아있으면 그걸 재생, 없으면 정지
        if (localOnlyMode) {
            // 큐 인덱스 기반: 현재 위치 다음 로컬곡
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            val nextIdx = if (curIdx >= 0) curIdx + 1 else 0
            val next = queue.drop(nextIdx).firstOrNull {
                it.videoId.startsWith("local:")
            }
            if (next != null) {
                currentVideoId = next.videoId
                currentTitle = next.title
                currentChannel = next.channel
                currentThumb = next.thumbnail
                QueueManager.setCurrent(this, next.videoId)
                runOnUiThread { updateUI() }
                loadAudio(next.videoId, isInitial = false)
                loadingNext = false
                return
            }
            loadingNext = false
            runOnUiThread {
                Toast.makeText(this@AudioPlayerActivity, "로컬 큐 끝", Toast.LENGTH_SHORT).show()
            }
            return
        }

        QueueManager.clear(this)

        bgScope.launch {
            val disliked = pref?.getStringSet("disliked_ids", emptySet()) ?: emptySet()

            val related = try {
                if (sameArtistMode) {
                    val artist = currentArtist.ifBlank { currentChannel }
                    YouTubeArtist.fetchSongs(artist, currentVideoId).filter { it.videoId !in disliked }
                } else {
                    YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel)
                        .filter { it.videoId !in disliked }
                        .filter { isMusicLike(it.title) }
                }
            } catch (e: Exception) {
                emptyList()
            }

            if (related.isEmpty()) {
                loadingNext = false
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "다음 곡 없음", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val next = related.first()
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            runOnUiThread { updateUI() }
            loadAudio(next.videoId, isInitial = false)
        }
    }

    private fun playNextManual() {
        val mc = mediaController
        // ★ ExoPlayer 큐에 다음 곡이 있으면 ExoPlayer에 위임 (큐 리셋 X)
        if (mc != null && mc.hasNextMediaItem()) {
            mc.seekToNextMediaItem()
            return
        }
        // 큐 끝: 기존 로직
        if (loadingNext) return
        loadingNext = true
        bgScope.launch { playNextRelatedBg() }
    }

    private fun addToHistory(videoId: String, title: String, channel: String, thumb: String) {
        audioHistory.removeAll { it.videoId == videoId }
        audioHistory.add(AudioHistoryItem(videoId, title, channel, thumb))
        while (audioHistory.size > 50) audioHistory.removeAt(0)
        historyIndex = audioHistory.size - 1
    }

    private fun playPrevious() {
        val mc = mediaController
        // ★ ExoPlayer 큐에 이전 곡이 있으면 ExoPlayer에 위임
        if (mc != null && mc.hasPreviousMediaItem()) {
            mc.seekToPreviousMediaItem()
            return
        }
        // ExoPlayer 큐 없음: audioHistory 기반
        if (historyIndex > 0) {
            historyIndex--
            val prev = audioHistory[historyIndex]
            isPlayingFromHistory = true
            currentVideoId = prev.videoId
            currentTitle = prev.title
            currentChannel = prev.channel
            currentThumb = prev.thumbnail
            updateUI()
            loadAudio(prev.videoId, isInitial = false)
        } else {
            mc?.seekTo(0)
        }
    }

    private fun dislikeCurrent() {
        if (currentVideoId.isBlank()) return
        val disliked = (pref?.getStringSet("disliked_ids", mutableSetOf()) ?: mutableSetOf()).toMutableSet()
        disliked.add(currentVideoId)
        pref?.edit()?.putStringSet("disliked_ids", disliked)?.apply()
        Toast.makeText(this, "다음부터 제외", Toast.LENGTH_SHORT).show()
        if (!loadingNext) {
            loadingNext = true
            bgScope.launch { delay(500); playNextRelatedBg() }
        }
    }

    private fun openLyrics() {
        if (currentSubtitleUrl.isBlank()) {
            Toast.makeText(this, "이 곡은 자막/가사가 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        android.util.Log.d("AudioPlayer", "openLyrics url=${currentSubtitleUrl.take(80)}")
        startActivity(Intent(this, LyricsActivity::class.java).apply {
            putExtra("VIDEO_ID", currentVideoId)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("VIDEO_CHANNEL", currentChannel)
            putExtra("VIDEO_ARTIST", currentArtist.ifBlank { currentChannel })
            putExtra("SUBTITLE_URL", currentSubtitleUrl)
        })
    }

    private fun showEqDialog() {
        if (PlaybackService.exoPlayer == null) return
        val session = PlaybackService.exoPlayer?.audioSessionId ?: 0
        if (session == 0 || !EqualizerManager.attach(session)) {
            Toast.makeText(this, "EQ 초기화 실패", Toast.LENGTH_SHORT).show()
            return
        }

        val bands = EqualizerManager.bandCount()
        val (minL, maxL) = EqualizerManager.levelRange()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val presetRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val presets = arrayOf("평탄", "저음", "고음", "V자")
        for (i in presets.indices) {
            val b = com.google.android.material.button.MaterialButton(this).apply {
                text = presets[i]
                textSize = 11f
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener { EqualizerManager.applyPreset(i) }
            }
            presetRow.addView(b)
        }
        container.addView(presetRow)

        for (i in 0 until bands) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            }
            val hz = EqualizerManager.centerFreqHz(i)
            val label = android.widget.TextView(this).apply {
                text = if (hz > 0) "${hz}Hz" else "B$i"
                textSize = 11f
                setTextColor(0xFF8E8E93.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (60 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(label)

            val sb = SeekBar(this).apply {
                max = maxL - minL
                progress = EqualizerManager.getLevel(i) - minL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) EqualizerManager.setLevel(i, p + minL)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(sb)
            container.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("🎛 이퀄라이저")
            .setView(container)
            .setPositiveButton("닫기", null)
            .show()
    }


    // ========== 🎙 실시간 자막 (오디오 모드) ==========
    private fun toggleAudioLiveSubtitle() {
        startActivity(Intent(this, LiveSubtitleActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        // LiveSubtitleActivity에서 돌아오면 상태 재확인
        audioLiveActive = false
        updateAudioLiveSubButton()
    }


    private fun updateAudioLiveSubButton() {
        try {
            if (audioLiveActive) {
                btnLiveSub.setTextColor(0xFFFF2D55.toInt())
                btnLiveSub.text = "🎙 ON"
            } else {
                btnLiveSub.setTextColor(0xFF8E8E93.toInt())
                btnLiveSub.text = "🎙"
            }
        } catch (e: Exception) { }
    }


    private fun toggleKeepScreenOn() {
        val on = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "🔴 화면 꺼짐 허용", Toast.LENGTH_SHORT).show()
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "🟢 화면 켜짐 유지 ON", Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateLyricsButtonLabel() {
        try {
            val btn = findViewById<MaterialButton>(R.id.btnLyrics) ?: return
            btn.text = if (currentSubtitleUrl.isNotBlank()) "📝" else "📜"
        } catch (e: Exception) { }
    }

    /** 음악 아닌 영상(말 많은 것) 필터 */
    private fun isMusicLike(title: String): Boolean {
        val badKeywords = listOf(
            "뉴스", "속보", "인터뷰", "강연", "토크", "팟캐스트", "podcast",
            "ep.", "회차", "라이브", "생방송", "예능", "드라마", "시사",
            "뉴스룸", "긴급", "특집", "다큐", "설명", "강의",
            "한국사", "역사", "과학", "다큐멘터리", "웨비나", "세미나"
        )
        return badKeywords.none { title.contains(it, ignoreCase = true) }
    }

    private fun attachListeners() {
        val mc = mediaController ?: return

        btnPlay.setOnClickListener { if (mc.isPlaying) mc.pause() else mc.play() }
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            mc.seekTo((mc.currentPosition - 10_000).coerceAtLeast(0))
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            val dur = mc.duration
            val newPos = mc.currentPosition + 10_000
            mc.seekTo(if (dur > 0) newPos.coerceAtMost(dur) else newPos)
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { playPrevious() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { playNextManual() }

        btnRepeat.setOnClickListener { toggleRepeat() }
        btnLiveSub.setOnClickListener { toggleAudioLiveSubtitle() }
        findViewById<MaterialButton>(R.id.btnMoreAudio).setOnClickListener {
            AlertDialog.Builder(this)
                .setItems(arrayOf(
                    "💡 화면 켜짐 유지 (토글)",
                    "🎛 이퀄라이저",
                    "👎 싫어요 (다음부터 제외)",
                    "📺 영상 모드로 전환",
                    "🎚 재생 속도"
                )) { _, which ->
                    when (which) {
                        0 -> toggleKeepScreenOn()
                        1 -> showEqDialog()
                        2 -> dislikeCurrent()
                        3 -> {
                            if (currentVideoId.startsWith("local:") || currentVideoId.isBlank()) {
                                Toast.makeText(this, "로컬 파일은 영상 모드가 없습니다", Toast.LENGTH_SHORT).show()
                            } else {
                                val pos = mediaController?.currentPosition ?: 0L
                                startActivity(Intent(this, PlayerActivity::class.java).apply {
                                    putExtra("VIDEO_ID", currentVideoId)
                                    putExtra("VIDEO_TITLE", currentTitle)
                                    putExtra("VIDEO_CHANNEL", currentChannel)
                                    putExtra("VIDEO_THUMB", currentThumb)
                                    putExtra("IS_SAME_VIDEO", true)
                                    putExtra("CURRENT_POS", pos)
                                })
                                finish()
                            }
                        }
                        4 -> showSpeedDialog()
                    }
                }
                .show()
        }
        btnRepeat.setOnLongClickListener { dislikeCurrent(); true }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<MaterialButton>(R.id.btnLyrics).setOnClickListener { openLyrics() }
        findViewById<MaterialButton>(R.id.btnEq).setOnClickListener { showEqDialog() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener { dislikeCurrent() }
        findViewById<MaterialButton>(R.id.btnVideoMode).setOnClickListener {
            if (currentVideoId.startsWith("local:") || currentVideoId.isBlank()) {
                Toast.makeText(this, "로컬 파일은 영상 모드가 없습니다", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", currentVideoId)
                    putExtra("VIDEO_TITLE", currentTitle)
                    putExtra("VIDEO_CHANNEL", currentChannel)
                    putExtra("VIDEO_THUMB", currentThumb)
                })
                finish()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isDragging) {
                    val dur = mc.duration
                    if (dur > 0) mc.seekTo((dur * progress / 1000))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isDragging = false
                val dur = mc.duration
                if (dur > 0) mc.seekTo((dur * (sb?.progress ?: 0) / 1000))
            }
        })
    }

    private fun toggleRepeat() {
        val mc = mediaController ?: return
        val next = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        mc.repeatMode = next
        btnRepeat.text = when (next) {
            Player.REPEAT_MODE_ONE -> "🔁1"
            Player.REPEAT_MODE_ALL -> "🔁A"
            else -> "🔁"
        }
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("재생 속도")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                mediaController?.setPlaybackSpeed(speeds[i])
                btnSpeed.text = "${speeds[i]}x"
            }
            .show()
    }

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = bgScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && !isDragging) {
                    val pos = mc.currentPosition
                    val dur = mc.duration
                    runOnUiThread {
                        tvPos.text = fmt(pos)
                        tvDur.text = if (dur > 0) fmt(dur) else "0:00"
                        if (dur > 0) seekBar.progress = (pos * 1000 / dur).toInt()
                        btnPlay.setImageResource(
                            if (mc.isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun startAnimation() {
        if (pulseAnimator != null) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val s = 1f + t * 0.06f
                ivArt.scaleX = s
                ivArt.scaleY = s
                ivBackground.rotation = -3f + t * 6f
                ivBackground.scaleX = 1.1f + t * 0.05f
                ivBackground.scaleY = 1.1f + t * 0.05f
                ivBackground.alpha = 0.15f + t * 0.15f
            }
            start()
        }
    }

    private fun stopAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        ivArt.scaleX = 1f
        ivArt.scaleY = 1f
        ivBackground.rotation = 0f
        ivBackground.scaleX = 1f
        ivBackground.scaleY = 1f
        ivBackground.alpha = 0.25f
    }

    private fun fmt(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackService.nextTrackHandler = null
        releaseWakeLock()
        stopAnimation()
        updateJob?.cancel()
        bgScope.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
