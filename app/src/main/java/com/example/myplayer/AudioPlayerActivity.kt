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

    private lateinit var ivArt: ImageView
    private lateinit var ivBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSpeed: com.google.android.material.button.MaterialButton
    private lateinit var btnRepeat: com.google.android.material.button.MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

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

        val videoId = intent.getStringExtra("VIDEO_ID") ?: ""
        val title = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val channel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val thumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        tvTitle.text = title
        tvChannel.text = channel
        if (thumb.isNotEmpty()) {
            Glide.with(this).load(thumb).into(ivArt)
            Glide.with(this).load(thumb).into(ivBackground)
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            attachListeners()
            startUpdateLoop()
            if (videoId.isNotEmpty()) {
                loadAudio(videoId, title, channel, thumb)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun loadAudio(videoId: String, title: String, channel: String, thumb: String) {
        lifecycleScope.launch {
            Toast.makeText(this@AudioPlayerActivity, "오디오 추출 중...", Toast.LENGTH_SHORT).show()
            val result = YouTubeStream.extract(videoId)
            // 오디오 우선
            val url = result.audioUrlBest ?: result.audioUrl ?: result.muxedUrl ?: result.videoUrl
            if (url.isNullOrBlank()) {
                Toast.makeText(this@AudioPlayerActivity, "오디오를 가져올 수 없음", Toast.LENGTH_LONG).show()
                return@launch
            }
            mediaController?.setMediaItem(MediaItem.fromUri(url))
            mediaController?.prepare()
            mediaController?.playWhenReady = true
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
            playFromQueue(-1)
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            playFromQueue(1)
        }

        btnRepeat.setOnClickListener { toggleRepeat() }

        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVideoMode).setOnClickListener {
            // 영상 모드로
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", intent.getStringExtra("VIDEO_ID"))
                putExtra("VIDEO_TITLE", intent.getStringExtra("VIDEO_TITLE"))
                putExtra("VIDEO_CHANNEL", intent.getStringExtra("VIDEO_CHANNEL"))
                putExtra("VIDEO_THUMB", intent.getStringExtra("VIDEO_THUMB"))
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

    private fun playFromQueue(delta: Int) {
        val queue = QueueManager.get(this)
        if (queue.isEmpty()) {
            Toast.makeText(this, "대기열 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val next = queue.first()
        QueueManager.remove(this, next.videoId)
        // 현재 액티비티 재사용
        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", next.videoId)
            putExtra("VIDEO_TITLE", next.title)
            putExtra("VIDEO_CHANNEL", next.channel)
            putExtra("VIDEO_THUMB", next.thumbnail)
        })
        finish()
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
        MediaController.releaseFuture(controllerFuture)
    }
}
