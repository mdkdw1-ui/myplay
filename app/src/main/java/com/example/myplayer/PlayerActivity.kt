package com.example.myplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var btnSpeed: MaterialButton
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private var isFullscreen = false
    private var currentSpeed = 1.0f

    private var subtitleTracks: List<SubtitleTrack> = emptyList()
    private var currentStreamUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        btnSpeed = findViewById(R.id.btnSpeed)

        progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@PlayerActivity, R.color.spinner)
            )
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        (playerView.parent as? android.view.ViewGroup)?.addView(progress)

        val videoUri = intent.getStringExtra("VIDEO_URI")
        val videoId = intent.getStringExtra("VIDEO_ID")
        val vTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val vChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val vThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            playerView.player = mediaController

            when {
                videoUri != null -> playDirectUrl(videoUri)
                videoId != null -> extractAndPlay(videoId, vTitle, vChannel, vThumb)
                else -> Toast.makeText(this, "재생할 영상이 없습니다", Toast.LENGTH_SHORT).show()
            }
        }, MoreExecutors.directExecutor())

        btnSpeed.setOnClickListener { showSpeedDialog() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPipMode() }
        findViewById<View>(R.id.btnCc).setOnClickListener { showSubtitleDialog() }
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("재생 속도")
            .setItems(labels) { _, i ->
                currentSpeed = speeds[i]
                mediaController?.setPlaybackSpeed(currentSpeed)
                btnSpeed.text = "${currentSpeed}x"
            }
            .show()
    }

    private fun playDirectUrl(url: String) {
        currentStreamUrl = url
        mediaController?.setMediaItem(MediaItem.fromUri(url))
        mediaController?.prepare()
        mediaController?.playWhenReady = true
        mediaController?.setPlaybackSpeed(currentSpeed)
    }

    private fun extractAndPlay(videoId: String, title: String, channel: String, thumb: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YouTubeStream.extract(videoId)
            progress.visibility = View.GONE

            if (!result.hasAny) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("재생 실패")
                    .setMessage(result.debug)
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }

            subtitleTracks = result.subtitles
            val streamUrl = result.muxedUrl ?: result.videoUrl ?: result.audioUrl

            val autoSub = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
                ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
                ?: subtitleTracks.firstOrNull()

            applyStreamWithSubtitle(streamUrl, autoSub, null, 0L)

            saveHistory(
                videoId = videoId,
                title = if (title.isNotEmpty()) title else result.title,
                channel = channel,
                thumb = thumb
            )
        }
    }

    private fun applyStreamWithSubtitle(
        url: String?,
        sub: SubtitleTrack?,
        targetLang: String?,
        startPosMs: Long
    ) {
        if (url.isNullOrBlank()) return
        currentStreamUrl = url

        val builder = MediaItem.Builder().setUri(url)

        if (sub != null) {
            val subUrl = if (targetLang != null) buildTranslatedUrl(sub.url, targetLang) else sub.url
            val label = if (targetLang != null) "${sub.displayName} → 한국어" else sub.displayName
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(targetLang ?: sub.languageCode)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        mediaController?.setMediaItem(builder.build(), startPosMs)
        mediaController?.prepare()
        mediaController?.playWhenReady = true
        mediaController?.setPlaybackSpeed(currentSpeed)
    }

    private fun buildTranslatedUrl(originalUrl: String, targetLang: String): String {
        return if (originalUrl.contains("tlang=")) {
            originalUrl.replace(Regex("tlang=[a-zA-Z\\-]+"), "tlang=$targetLang")
        } else {
            "$originalUrl&tlang=$targetLang"
        }
    }

    private fun showSubtitleDialog() {
        if (subtitleTracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("자막")
                .setMessage("이 영상엔 자막이 없습니다")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        val labels = mutableListOf<String>()
        val callbacks = mutableListOf<() -> Unit>()

        labels.add("자막 끄기")
        callbacks.add {
            applyStreamWithSubtitle(
                currentStreamUrl, null, null,
                mediaController?.currentPosition ?: 0L
            )
        }

        for (s in subtitleTracks) {
            val auto = if (s.isAutoGenerated) " · 자동" else ""
            val name = "${s.displayName}$auto"
            val pos = mediaController?.currentPosition ?: 0L

            labels.add(name)
            callbacks.add {
                applyStreamWithSubtitle(currentStreamUrl, s, null, pos)
            }

            if (!s.languageCode.startsWith("ko")) {
                labels.add("$name → 한국어")
                callbacks.add {
                    applyStreamWithSubtitle(currentStreamUrl, s, "ko", pos)
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("자막 선택")
            .setItems(labels.toTypedArray()) { _, i ->
                callbacks[i].invoke()
            }
            .show()
    }

    private suspend fun saveHistory(videoId: String, title: String, channel: String, thumb: String) =
        withContext(Dispatchers.IO) {
            try {
                HistoryDatabase.get(applicationContext).historyDao().insert(
                    HistoryEntity(
                        videoId = videoId,
                        title = title,
                        channel = channel,
                        thumbnail = thumb,
                        watchedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

    private fun toggleFullscreen() {
        val controller = window.insetsController ?: return
        if (!isFullscreen) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isFullscreen = true
        } else {
            controller.show(WindowInsets.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isFullscreen = false
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        findViewById<View>(R.id.controlBar).visibility =
            if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            mediaController?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaController.releaseFuture(controllerFuture)
    }
}
