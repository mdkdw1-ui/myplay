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
        if (!fromPlaylist) {
            QueueManager.clear(this)
        }

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

            if (currentVideoId.isNotEmpty()) {
                loadAudio(currentVideoId, isInitial = true)
            }
        }, MoreExecutors.directExecutor())
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
        val title = currentTitle
        val ch = currentChannel
        lifecycleScope.launch {
            try {
                val result = ArtistExtractor.extract(vid, title, ch)
                if (vid == currentVideoId) {
                    currentArtist = result
                    android.util.Log.d("AudioPlayer", "artist=$result")
                }
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

            mediaController?.setMediaItem(MediaItem.fromUri(url))
            mediaController?.prepare()
            mediaController?.playWhenReady = true

            addToHistory(videoId, currentTitle, currentChannel, currentThumb)
        }
    }

    private fun attachPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    scheduleNextTrack()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startPulse() else stopPulse()
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
                YouTubeArtist.fetchSongs(artist, currentVideoId)
                    .filter { it.videoId !in disliked }
            } else {
                YouTubeRadio.fetchRelated(currentVideoId)
                    .filter { it.videoId !in disliked }
            }

            if (related.isEmpty()) {
                Toast.makeText(
                    this@AudioPlayerActivity,
                    "다음 곡을 찾을 수 없습니다",
                    Toast.LENGTH_LONG
                ).show()
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
            autoPlayJob?.cancel()
            playPrevious()
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            autoPlayJob?.cancel()
            playNextRelated()
        }

        btnRepeat.setOnClickListener { toggleRepeat() }
        btnRepeat.setOnLongClickListener {
            dislikeCurrent()
            true
        }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener {
            dislikeCurrent()
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
        val label = when (next) {
            Player.REPEAT_MODE_ONE -> "🔁 한 곡 반복"
            Player.REPEAT_MODE_ALL -> "🔁 전체 반복"
            else -> "🔁 반복"
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

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 2000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                ivArt.scaleX = v
                ivArt.scaleY = v
                ivBackground.alpha = 0.15f + (v - 1f) * 1.5f
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        ivArt.scaleX = 1f
        ivArt.scaleY = 1f
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
        stopPulse()
        updateJob?.cancel()
        autoPlayJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
