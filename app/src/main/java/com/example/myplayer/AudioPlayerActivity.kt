package com.example.myplayer

import android.content.ComponentName
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
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist) QueueManager.clear(this)

        reuseStreamUrl = intent.getStringExtra("REUSE_STREAM_URL") ?: ""
        reuseSubtitleUrl = intent.getStringExtra("REUSE_SUBTITLE_URL") ?: ""

        updateUI()

        sameArtistMode = pref?.getBoolean("same_artist_mode", false) ?: false
        val swArtist = findViewById<SwitchMaterial>(R.id.swSameArtist)
        swArtist.isChecked = sameArtistMode
        swArtist.setOnCheckedChangeListener { _, checked ->
            sameArtistMode = checked
            pref?.edit()?.putBoolean("same_artist_mode", checked)?.apply()
        }

        PlaybackService.nextTrackHandler = {
            runOnUiThread {
                if (!loadingNext) {
                    loadingNext = true
                    bgScope.launch {
                        delay(1000)
                        playNextRelatedBg()
                    }
                }
            }
        }

        acquireWakeLock()

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
        })
    }

    private fun playNextRelatedBg() {
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

        val queue = QueueManager.get(this)
        val queueNext = queue.firstOrNull { it.videoId != currentVideoId }
        if (queueNext != null) {
            QueueManager.remove(this, queueNext.videoId)
            currentVideoId = queueNext.videoId
            currentTitle = queueNext.title
            currentChannel = queueNext.channel
            currentThumb = queueNext.thumbnail
            runOnUiThread { updateUI() }
            loadAudio(queueNext.videoId, isInitial = false)
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
                    YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel).filter { it.videoId !in disliked }
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
            mediaController?.seekTo(0)
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
        btnRepeat.setOnLongClickListener { dislikeCurrent(); true }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<MaterialButton>(R.id.btnLyrics).setOnClickListener { openLyrics() }
        findViewById<MaterialButton>(R.id.btnEq).setOnClickListener { showEqDialog() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener { dislikeCurrent() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnLongClickListener {
            toggleAudioLiveSubtitle()
            true
        }
        findViewById<MaterialButton>(R.id.btnVideoMode).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", currentVideoId)
                putExtra("VIDEO_TITLE", currentTitle)
                putExtra("VIDEO_CHANNEL", currentChannel)
                putExtra("VIDEO_THUMB", currentThumb)
            })
            finish()
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
