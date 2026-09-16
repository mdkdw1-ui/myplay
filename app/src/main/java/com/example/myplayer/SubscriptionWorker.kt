package com.example.myplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first

class SubscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            val subs = try {
                HistoryDatabase.get(ctx).subscriptionDao().getAll().first()
            } catch (e: Exception) { emptyList() }

            if (subs.isEmpty()) return Result.success()

            val pref = ctx.getSharedPreferences("sub_prefs", Context.MODE_PRIVATE)
            var newCount = 0
            val newVideos = mutableListOf<Pair<String, VideoItem>>()

            for (sub in subs) {
                // 채널의 최신 영상 1개 확인
                val videos = YouTubeChannel.fetch(sub.channelId, sub.name).second.videos
                val latest = videos.firstOrNull() ?: continue
                val lastSeen = pref.getString("last_${sub.channelId}", "") ?: ""

                if (latest.videoId != lastSeen && lastSeen.isNotEmpty()) {
                    newCount++
                    newVideos.add(sub.name to latest)
                }
                pref.edit().putString("last_${sub.channelId}", latest.videoId).apply()
            }

            if (newCount > 0) {
                showNotification(ctx, newCount, newVideos)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("SubscriptionWorker", "err: ${e.message}", e)
            Result.retry()
        }
    }

    private fun showNotification(
        ctx: Context,
        count: Int,
        videos: List<Pair<String, VideoItem>>
    ) {
        val channelId = "sub_updates"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "구독 채널 새 영상", NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }

        val body = videos.take(3).joinToString("\n") { (ch, v) ->
            "$ch: ${v.title.take(30)}"
        }

        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("구독 채널 새 영상 ${count}개")
            .setContentText(videos.firstOrNull()?.let { "${it.first} · ${it.second.title}" } ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()

        nm.notify(7001, notif)
    }
}
