package com.example.myplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
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
    private var isDragging = false

    // ★ 오디오 모드 상태
    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""
    private var autoPlayJob: Job? = null
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
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRepeat = findViewById(R.id.btnRepeat)

        currentVideoId = intent.getStringExtra("VIDEO_ID") ?: ""
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        updateUI()

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
    }

    /** ★ 오디오 로드 (음질 최고 우선) */
    private fun loadAudio(videoId: String, isInitial: Boolean = false) {
        lifecycleScope.launch {
            if (isInitial) {
                Toast.makeText(this@AudioPlayerActivity, "오디오 추출 중...", Toast.LENGTH_SHORT).show()
            }

            val result = YouTubeStream.extract(videoId)
            // ★ 오디오 전용 (최고 비트레이트) → 없으면 muxed
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
        }
    }

    /** ★ Player.Listener — 곡 종료 시 자동 다음 */
    private fun attachPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    scheduleNextTrack()
                }
            }
        })
    }

    /** ★ 1초 후 다음 곡 */
    private fun scheduleNextTrack() {
        autoPlayJob?.cancel()
        autoPlayJob = lifecycleScope.launch {
            delay(1000)  // ★ 1초 대기
            playNextRelated()
        }
    }

    /** ★ 다음 곡: 큐 우선 → 없으면 YouTubeRadio */
    private fun playNextRelated() {
        // 1. 큐에 다음 곡 있으면 그걸 재생
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

        // 2. 큐 비었으면 YouTubeRadio
        lifecycleScope.launch {
            val disliked = pref?.getStringSet("disliked_ids", emptySet()) ?: emptySet()

            Toast.makeText(
                this@AudioPlayerActivity,
                "다음 곡 검색 중...",
                Toast.LENGTH_SHORT
            ).show()

            val related = YouTubeRadio.fetchRelated(currentVideoId)
                .filter { it.videoId !in disliked }

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

    /** ★ 싫어요 — 다음부터 제외 */
    private fun dislikeCurrent() {
        if (currentVideoId.isBlank()) return
        val disliked = (pref?.getStringSet("disliked_ids", mutableSetOf()) ?: mutableSetOf()).toMutableSet()
        disliked.add(currentVideoId)
        pref?.edit()?.putStringSet("disliked_ids", disliked)?.apply()

        Toast.makeText(this, "다음부터 추천 제외됨", Toast.LENGTH_SHORT).show()

        // 즉시 다음 곡
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

        // ★ 이전/다음 곡 버튼
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            autoPlayJob?.cancel()
            Toast.makeText(this, "이전 곡", Toast.LENGTH_SHORT).show()
            // 히스토리 없으니 처음부터 재생
            mc.seekTo(0)
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            autoPlayJob?.cancel()
            playNextRelated()
        }

        btnRepeat.setOnClickListener { toggleRepeat() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener {
            dislikeCurrent()
        }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        // ★ 싫어요 버튼 (없으면 btnRepeat 롱프레스로)
        btnRepeat.setOnLongClickListener {
            dislikeCurrent()
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
                    if (dur > 0) {
                        seekBar.progress = (pos * 1000 / dur).toInt()
                    }
                    btnPlay.setImageResource(
                        if (mc.isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
                delay(500)
            }
        }
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
        updateJob?.cancel()
        autoPlayJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
