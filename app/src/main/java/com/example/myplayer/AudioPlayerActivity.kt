package com.example.myplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var updateJob: Job? = null
    private var autoPlayJob: Job? = null
    private var pulseAnimator: android.animation.ValueAnimator? = null
    private var isDragging = false

    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""
    private var currentArtist: String = ""
    private var currentSubtitleUrl: String = ""
    private var sameArtistMode: Boolean = false

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

        currentVideoId = intent.getStringExtra("VIDEO_ID") ?: ""
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist) QueueManager.clear(this)

        updateUI()

        sameArtistMode = pref?.getBoolean("same_artist_mode", false) ?: false
        val swArtist = findViewById<SwitchMaterial>(R.id.swSameArtist)
        swArtist.isChecked = sameArtistMode
        swArtist.setOnCheckedChangeListener { _, checked ->
            sameArtistMode = checked
            pref?.edit()?.putBoolean("same_artist_mode", checked)?.apply()
            Toast.makeText(
                this,
                if (checked) "🎤 같은 가수만 재생" else "🎵 연관곡 재생",
                Toast.LENGTH_SHORT
            ).show()
        }

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

    private fun attachEqualizer() {
        val session = PlaybackService.exoPlayer?.audioSessionId ?: 0
        if (session != 0) {
            val ok = EqualizerManager.attach(session)
            android.util.Log.d("AudioPlayer", "eq attach: $ok (session=$session)")
        } else {
            // 재시도 (sessionId 준비 안 됐을 수 있음)
            lifecycleScope.launch {
                delay(1500)
                val s2 = PlaybackService.exoPlayer?.audioSessionId ?: 0
                if (s2 != 0) EqualizerManager.attach(s2)
            }
        }
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
        lifecycleScope.launch {
            try {
                val result = ArtistExtractor.extract(vid, t, c)
                if (vid == currentVideoId) currentArtist = result
            } catch (e: Exception) { }
        }
    }

    private fun loadAudio(videoId: String, isInitial: Boolean = false) {
        lifecycleScope.launch {
            if (isInitial) {
                Toast.makeText(this@AudioPlayerActivity, "오디오 추출 중...", Toast.LENGTH_SHORT).show()
            }

            val result = YouTubeStream.extract(videoId)
            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl

            if (url.isNullOrBlank()) {
                Toast.makeText(this@AudioPlayerActivity, "오디오를 가져올 수 없음", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 자막 URL 저장 (가사용)
            currentSubtitleUrl = result.subtitles.firstOrNull { it.languageCode.startsWith("ko") }?.url
                ?: result.subtitles.firstOrNull { it.languageCode.startsWith("en") }?.url
                ?: result.subtitles.firstOrNull()?.url
                ?: ""

            mediaController?.setMediaItem(MediaItem.fromUri(url))
            mediaController?.prepare()
            mediaController?.playWhenReady = true

            addToHistory(videoId, currentTitle, currentChannel, currentThumb)

            // ★ EQ 세션 재확인 (새 트랙마다)
            delay(300)
            val s = PlaybackService.exoPlayer?.audioSessionId ?: 0
            if (s != 0) EqualizerManager.attach(s)
        }
    }

    private fun attachPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) scheduleNextTrack()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startAnimation() else stopAnimation()
            }
        })
    }

    private fun scheduleNextTrack() {
        autoPlayJob?.cancel()
        autoPlayJob = lifecycleScope.launch {
            delay(1000)
            playNextRelated()
        }
    }

    private fun playNextRelated() {
        if (!isPlayingFromHistory && historyIndex >= 0 && historyIndex < audioHistory.size - 1) {
            historyIndex++
            val next = audioHistory[historyIndex]
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            updateUI()
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
            updateUI()
            loadAudio(queueNext.videoId, isInitial = false)
            return
        }

        QueueManager.clear(this)

        lifecycleScope.launch {
            val disliked = pref?.getStringSet("disliked_ids", emptySet()) ?: emptySet()
            Toast.makeText(
                this@AudioPlayerActivity,
                if (sameArtistMode) "🎤 같은 가수 곡 검색 중..." else "🎵 연관곡 검색 중...",
                Toast.LENGTH_SHORT
            ).show()

            val related = if (sameArtistMode) {
                val artist = currentArtist.ifBlank { currentChannel }
                YouTubeArtist.fetchSongs(artist, currentVideoId).filter { it.videoId !in disliked }
            } else {
                YouTubeRadio.fetchRelated(currentVideoId).filter { it.videoId !in disliked }
            }

            if (related.isEmpty()) {
                Toast.makeText(this@AudioPlayerActivity, "다음 곡을 찾을 수 없습니다", Toast.LENGTH_LONG).show()
                return@launch
            }

            val next = related.first()
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            updateUI()
            loadAudio(next.videoId, isInitial = false)
        }
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
            Toast.makeText(this, "처음 곡입니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dislikeCurrent() {
        if (currentVideoId.isBlank()) return
        val disliked = (pref?.getStringSet("disliked_ids", mutableSetOf()) ?: mutableSetOf()).toMutableSet()
        disliked.add(currentVideoId)
        pref?.edit()?.putStringSet("disliked_ids", disliked)?.apply()
        Toast.makeText(this, "다음부터 추천 제외됨", Toast.LENGTH_SHORT).show()
        autoPlayJob?.cancel()
        autoPlayJob = lifecycleScope.launch {
            delay(500)
            playNextRelated()
        }
    }

    private fun openLyrics() {
        if (currentSubtitleUrl.isBlank()) {
            Toast.makeText(this, "이 곡은 가사가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, LyricsActivity::class.java).apply {
            putExtra("VIDEO_ID", currentVideoId)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("VIDEO_CHANNEL", currentChannel)
            putExtra("SUBTITLE_URL", currentSubtitleUrl)
        })
    }

    private fun showEqDialog() {
        if (PlaybackService.exoPlayer == null) {
            Toast.makeText(this, "EQ 준비 안 됨", Toast.LENGTH_SHORT).show()
            return
        }
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
                setOnClickListener {
                    EqualizerManager.applyPreset(i)
                    // 다이얼로그 재오픈
                }
            }
            presetRow.addView(b)
        }
        container.addView(presetRow)

        val seekbars = mutableListOf<SeekBar>()
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
            seekbars.add(sb)
            row.addView(sb)
            container.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("🎛 이퀄라이저")
            .setView(container)
            .setPositiveButton("닫기", null)
            .setNeutralButton("EQ 끄기") { _, _ ->
                EqualizerManager.setEnabled(!EqualizerManager.isEnabled())
                Toast.makeText(
                    this,
                    if (EqualizerManager.isEnabled()) "EQ ON" else "EQ OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun attachListeners() {
        val mc = mediaController ?: return

        btnPlay.setOnClickListener {
            if (mc.isPlaying) mc.pause() else mc.play()
        }

        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            mc.seekTo((mc.currentPosition - 10_000).coerceAtLeast(0))
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            val dur = mc.duration
            val newPos = mc.currentPosition + 10_000
            mc.seekTo(if (dur > 0) newPos.coerceAtMost(dur) else newPos)
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            autoPlayJob?.cancel(); playPrevious()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            autoPlayJob?.cancel(); playNextRelated()
        }

        btnRepeat.setOnClickListener { toggleRepeat() }
        btnRepeat.setOnLongClickListener { dislikeCurrent(); true }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<MaterialButton>(R.id.btnLyrics).setOnClickListener { openLyrics() }
        findViewById<MaterialButton>(R.id.btnEq).setOnClickListener { showEqDialog() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener { dislikeCurrent() }
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
        val label = when (next) {
            Player.REPEAT_MODE_ONE -> "🔁1"
            Player.REPEAT_MODE_ALL -> "🔁A"
            else -> "🔁"
        }
        btnRepeat.text = label
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
        updateJob = lifecycleScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && !isDragging) {
                    val pos = mc.currentPosition
                    val dur = mc.duration
                    tvPos.text = fmt(pos)
                    tvDur.text = if (dur > 0) fmt(dur) else "0:00"
                    if (dur > 0) seekBar.progress = (pos * 1000 / dur).toInt()
                    btnPlay.setImageResource(
                        if (mc.isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
                delay(500)
            }
        }
    }

    /** ★ 배경 애니메이션 — pulse + 배경 드리프트 */
    private fun startAnimation() {
        if (pulseAnimator != null) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                // 아트 pulse
                val s = 1f + t * 0.06f
                ivArt.scaleX = s
                ivArt.scaleY = s
                // 배경 회전 + 줌
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
        stopAnimation()
        updateJob?.cancel()
        autoPlayJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
