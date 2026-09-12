package com.example.myplayer

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private var isFullscreen = false
    private var videoUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        videoUri = intent.getStringExtra("VIDEO_URI")
            ?: "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

        // MediaController를 통해 PlaybackService의 ExoPlayer에 연결
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            playerView.player = mediaController
            setMediaItem(videoUri!!)
            mediaController?.playWhenReady = true
        }, MoreExecutors.directExecutor())

        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPipMode() }
    }

    private fun setMediaItem(url: String) {
        val item = MediaItem.fromUri(url)
        mediaController?.setMediaItem(item)
        mediaController?.prepare()
    }

    // --- 전체화면 전환 ---
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

    // --- PiP 진입 ---
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
        // PiP 중에는 컨트롤 버튼 숨기기
        findViewById<View>(R.id.controlBar).visibility =
            if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    // --- 생명주기: onStop에서만 일시정지, PiP 중에는 재생 유지 ---
    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            mediaController?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 컨트롤러만 해제; 실제 ExoPlayer는 Service가 소유하므로 재생은 유지됨
        MediaController.releaseFuture(controllerFuture)
    }
}
