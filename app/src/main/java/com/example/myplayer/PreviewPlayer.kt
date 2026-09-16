package com.example.myplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 카드 위에 뜨는 5초 미리보기 플레이어 (전역 싱글턴)
 */
object PreviewPlayer {

    private const val TAG = "PreviewPlayer"
    private const val PREVIEW_MS = 5000L

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var overlay: View? = null
    private var host: ViewGroup? = null
    private var scope: CoroutineScope? = null
    private var stopJob: Job? = null

    /** 미리보기 시작 (카드 위 오버레이) */
    fun start(ctx: Context, hostContainer: ViewGroup, videoId: String) {
        stop() // 기존 정지

        try {
            // 1. 오버레이 뷰 생성
            val ov = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xCC000000.toInt())
            }
            val pv = PlayerView(ctx).apply {
                useController = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            ov.addView(pv)
            hostContainer.addView(ov)

            // 2. ExoPlayer 생성
            val p = ExoPlayer.Builder(ctx).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
            }
            pv.player = p

            overlay = ov
            playerView = pv
            player = p
            host = hostContainer
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            // 3. 스트림 추출 (비동기)
            scope?.launch {
                val result = YouTubeStream.extract(videoId)
                val url = result.videoUrl
                    ?: result.muxedUrl
                    ?: result.audioUrl
                if (url.isNullOrBlank()) {
                    stop()
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    try {
                        p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                        p.prepare()
                        p.playWhenReady = true
                        p.seekTo(5000) // 5초 지점부터 (인트로 스킵)
                    } catch (e: Exception) {
                        Log.e(TAG, "play err: ${e.message}", e)
                        stop()
                    }
                }
            }

            // 4. 5초 후 자동 정지
            stopJob = scope?.launch {
                delay(PREVIEW_MS)
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start err: ${e.message}", e)
            stop()
        }
    }

    /** 정지 + 정리 */
    fun stop() {
        stopJob?.cancel()
        stopJob = null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        playerView = null
        try {
            overlay?.let { host?.removeView(it) }
        } catch (e: Exception) { }
        overlay = null
        host = null
        scope?.cancel()
        scope = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
}
