package com.example.myplayer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 영상 탭 시 재생 모드 선택 다이얼로그
 * - 동영상 모드 → PlayerActivity
 * - 음악 모드 → AudioPlayerActivity
 */
object PlayModeChooser {

    fun show(ctx: Context, video: VideoItem) {
        val titleShort = video.title.take(50)
        AlertDialog.Builder(ctx)
            .setTitle(titleShort)
            .setItems(arrayOf("▶ 동영상 모드", "🎵 음악 모드")) { _, which ->
                when (which) {
                    0 -> openVideo(ctx, video)
                    1 -> openAudio(ctx, video)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    fun showHomeVideo(ctx: Context, video: HomeVideo) {
        val titleShort = video.title.take(50)
        AlertDialog.Builder(ctx)
            .setTitle(titleShort)
            .setItems(arrayOf("▶ 동영상 모드", "🎵 음악 모드")) { _, which ->
                val v = VideoItem(video.videoId, video.title, video.channel, video.thumbnail)
                when (which) {
                    0 -> openVideo(ctx, v)
                    1 -> openAudio(ctx, v)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openVideo(ctx: Context, video: VideoItem) {
        try {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", video.videoId)
                putExtra("VIDEO_TITLE", video.title)
                putExtra("VIDEO_CHANNEL", video.channel)
                putExtra("VIDEO_THUMB", video.thumbnail)
            })
        } catch (e: Exception) {
            Toast.makeText(ctx, "동영상 모드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAudio(ctx: Context, video: VideoItem) {
        try {
            QueueManager.clear(ctx)
            ctx.startActivity(Intent(ctx, AudioPlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", video.videoId)
                putExtra("VIDEO_TITLE", video.title)
                putExtra("VIDEO_CHANNEL", video.channel)
                putExtra("VIDEO_THUMB", video.thumbnail)
                putExtra("FROM_PLAYLIST", false)
            })
        } catch (e: Exception) {
            Toast.makeText(ctx, "음악 모드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
