package com.example.myplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        progress = ProgressBar(this)
        (playerView.parent as? android.view.ViewGroup)?.addView(progress)

        val videoUri = intent.getStringExtra("VIDEO_URI")
        val videoId = intent.getStringExtra("VIDEO_ID")

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            playerView.player = mediaController

            when {
                videoUri != null -> playUrl(videoUri)
                videoId != null -> extractAndPlay(videoId)
                else -> Toast.makeText(this, "no video", Toast.LENGTH_SHORT).show()
            }
        }, MoreExecutors.directExecutor())

        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPipMode() }
    }

    private fun playUrl(url: String) {
        mediaController?.setMediaItem(MediaItem.fromUri(url))
        mediaController?.prepare()
        mediaController?.playWhenReady = true
    }

    private fun extractAndPlay(videoId: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YouTubeStream.extract(videoId)
            progress.visibility = View.GONE

            if (!result.hasAny) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("extract failed")
                    .setMessage(result.debug)
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            when {
                result.muxedUrl != null -> {
                    mediaController?.setMediaItem(MediaItem.fromUri(result.muxedUrl))
                }
                result.videoUrl != null -> {
                    mediaController?.setMediaItem(MediaItem.fromUri(result.videoUrl))
                }
                result.audioUrl != null -> {
                    mediaController?.setMediaItem(MediaItem.fromUri(result.audioUrl))
                }
            }
            mediaController?.prepare()
            mediaController?.playWhenReady = true

            Toast.makeText(this@PlayerActivity, "play: ${result.title.take(30)}", Toast.LENGTH_SHORT).show()
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
