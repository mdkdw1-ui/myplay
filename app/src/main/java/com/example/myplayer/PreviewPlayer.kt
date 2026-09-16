package com.example.myplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
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
 * 카드 위에 뜨는 5초 미리보기 (전역 싱글턴, 안전 설계)
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

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun isActive(): Boolean = overlay != null

    /**
     * @param host 카드의 itemView (RecyclerView 아님!)
     */
    fun start(ctx: Context, hostCard: ViewGroup, videoId: String) {
        stop()

        try {
            val ov = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0x99000000.toInt())
                isClickable = true
                isFocusable = true
                // 카드 위에 얹기 위해 부모가 FrameLayout이어야 함
            }
            val pv = PlayerView(ctx).apply {
                useController = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            ov.addView(pv)

            // 카드 자체가 FrameLayout이 아니면 감쌀 수 없음 → 부모에 얹기
            // item_video.xml 루트가 CardView 내부 LinearLayout → 그 위에 얹기 위해 CardView에 붙임
            if (hostCard is FrameLayout || hostCard is androidx.cardview.widget.CardView) {
                hostCard.addView(ov)
            } else {
                // fallback: 부모 ViewGroup에 얹기
                val parent = hostCard.parent as? ViewGroup ?: run {
                    Log.e(TAG, "no parent")
                    return
                }
                parent.addView(ov)
            }

            val p = ExoPlayer.Builder(ctx).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
            }
            pv.player = p

            overlay = ov
            playerView = pv
            player = p
            host = hostCard
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            scope?.launch {
                try {
                    val result = YouTubeStream.extract(videoId)
                    val url = result.muxedUrl
                        ?: result.videoUrl
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
                            p.seekTo(5000)
                        } catch (e: Exception) {
                            Log.e(TAG, "play err", e)
                            stop()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "extract err", e)
                    stop()
                }
            }

            stopJob = scope?.launch {
                delay(PREVIEW_MS)
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start err", e)
            stop()
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        playerView = null
        try {
            overlay?.let {
                (it.parent as? ViewGroup)?.removeView(it)
            }
        } catch (e: Exception) { }
        overlay = null
        host = null
        scope?.cancel()
        scope = null
    }
}
