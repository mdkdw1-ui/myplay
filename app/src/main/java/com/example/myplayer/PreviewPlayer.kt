package com.example.myplayer

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PreviewPlayer {

    private const val TAG = "PreviewPlayer"
    private const val PREVIEW_MS = 10_000L   // ★ 10초로 연장

    // ★ 캐시: videoId → url (두 번째부터 즉시)
    private val urlCache = mutableMapOf<String, String>()

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var overlay: View? = null
    private var host: ViewGroup? = null
    private var scope: CoroutineScope? = null
    private var stopJob: Job? = null
    private var loadJob: Job? = null

    fun isPlaying(): Boolean = player?.isPlaying == true
    fun isActive(): Boolean = overlay != null

    /**
     * @param thumbUrl 썸네일 (검은 화면 대신 먼저 표시)
     */
    fun start(ctx: Context, hostCard: ViewGroup, videoId: String, thumbUrl: String) {
        stop()

        try {
            // 1. 오버레이 (썸네일 배경 + PlayerView)
            val ov = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                // ★ 터치 통과 (카드가 탭 받도록)
                isClickable = false
                isFocusable = false
            }

            // ★ 썸네일 먼저 (블러 + 스케일)
            val thumbIv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setColorFilter(0x99000000.toInt())
                isClickable = false
                isFocusable = false
            }
            if (thumbUrl.isNotBlank()) {
                Glide.with(ctx).load(thumbUrl).into(thumbIv)
            }
            ov.addView(thumbIv)

            // 로딩 인디케이터
            val loader = android.widget.ProgressBar(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER }
                indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#FF2D55")
                )
            }
            ov.addView(loader)

            // PlayerView (숨김 상태로)
            val pv = PlayerView(ctx).apply {
                useController = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                visibility = View.GONE  // 로드 완료 후 표시
            }
            ov.addView(pv)

            if (hostCard is FrameLayout || hostCard is androidx.cardview.widget.CardView) {
                hostCard.addView(ov)
            } else {
                val parent = hostCard.parent as? ViewGroup ?: return
                parent.addView(ov)
            }

            // 2. ExoPlayer
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

            // 3. URL 캐시 확인 → 즉시 재생 or 추출
            val cached = urlCache[videoId]
            if (cached != null) {
                playUrl(p, pv, loader, cached)
            } else {
                loadJob = scope?.launch {
                    try {
                        val result = YouTubeStream.extract(videoId)
                        // ★ 최저화질 우선 (빠른 로드)
                        val url = result.qualities
                            .minByOrNull { it.height }
                            ?.url
                            ?: result.muxedUrl
                            ?: result.videoUrl
                            ?: result.audioUrl

                        if (url.isNullOrBlank()) {
                            stop()
                            return@launch
                        }
                        urlCache[videoId] = url
                        withContext(Dispatchers.Main) {
                            playUrl(p, pv, loader, url)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "extract err", e)
                        stop()
                    }
                }
            }

            // 4. 자동 정지 (10초)
            stopJob = scope?.launch {
                delay(PREVIEW_MS)
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "start err", e)
            stop()
        }
    }

    private fun playUrl(p: ExoPlayer, pv: PlayerView, loader: View, url: String) {
        try {
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.prepare()
            p.playWhenReady = true
            p.seekTo(7000)  // 7초 지점부터 (인트로 스킵)

            // 준비되면 PlayerView 표시 + 로더 숨김
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        pv.visibility = View.VISIBLE
                        loader.visibility = View.GONE
                    } else if (state == Player.STATE_BUFFERING) {
                        loader.visibility = View.VISIBLE
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "play err", e)
            stop()
        }
    }

    fun stop() {
        stopJob?.cancel(); stopJob = null
        loadJob?.cancel(); loadJob = null
        try {
            overlay?.let { ov -> (ov.parent as? ViewGroup)?.removeView(ov) }
        } catch (e: Exception) { }
        overlay = null
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        playerView = null
        host = null
        scope?.cancel(); scope = null
    }
}
