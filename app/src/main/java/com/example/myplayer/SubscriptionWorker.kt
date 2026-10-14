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
        val groupId = "sub_group"
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "구독 채널 새 영상", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(true)
                description = "구독 채널의 새 영상 알림"
            }
            nm.createNotificationChannel(ch)

            // ★ 채널별 그룹 알림 (Oreo+)
            try {
                nm.deleteNotificationChannel("sub_group_summary")
            } catch (_: Exception) {}
        }

        // ★ 첫 영상 정보 저장 (기존)
        videos.firstOrNull()?.let { (chName, video) ->
            ctx.getSharedPreferences("sub_notif", Context.MODE_PRIVATE)
                .edit()
                .putString("video_id", video.videoId)
                .putString("video_title", video.title)
                .putString("video_channel", video.channel.ifBlank { chName })
                .putString("video_thumbnail", video.thumbnail)
                .putLong("saved_at", System.currentTimeMillis())
                .apply()
        }

        // ★★★ 옵션 3: 채널별 개별 알림 ★★★
        // videos: List<Pair<채널명, VideoItem>>
        // 채널별로 그룹화
        val byChannel = videos.groupBy { it.first }

        // 그룹 요약 알림 (접혀있을 때 표시)
        val summaryBuilder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("구독 채널 새 영상 ${count}개")
            .setContentText("탭하여 모두 큐에 추가")
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                videos.take(5).forEach { (ch, v) ->
                    style.addLine("$ch: ${v.title.take(35)}")
                }
                if (videos.size > 5) style.setSummaryText("+${videos.size - 5}개 더")
            })
            .setContentIntent(makePlayAllIntent(ctx, videos))   // ★ 옵션 2: 순차 재생
            .setAutoCancel(true)
            .setGroup(groupId)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        nm.notify(7000, summaryBuilder.build())

        // 채널별 개별 알림 (최대 5개)
        var notifId = 7001
        byChannel.entries.take(5).forEach { (chName, items) ->
            val first = items.first().second
            val channelVideos = items.map { it.second }

            val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("$chName · 새 영상 ${items.size}개")
                .setContentText(first.title.take(50))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    items.take(3).joinToString("\n") { it.second.title.take(40) }
                ))
                .setContentIntent(makePlaySingleIntent(ctx, first))   // ★ 탭: 첫 영상 재생
                .setAutoCancel(true)
                .setGroup(groupId)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            // ★★★ "재생" / "나중에" 액션 버튼 (명확한 2개) ★★★
            val playSinglePi = makePlaySingleIntent(ctx, first, notifId)
            val laterPi = makeLaterIntent(ctx, first, notifId)

            builder.addAction(
                android.R.drawable.ic_media_play,
                "▶ 재생",
                playSinglePi
            )
            builder.addAction(
                android.R.drawable.ic_menu_recent_history,
                "⏰ 나중에",
                laterPi
            )

            nm.notify(notifId, builder.build())
            notifId++
        }

        android.util.Log.d("SubscriptionWorker",
            "알림 표시: 총 ${count}개, ${byChannel.size}개 채널")
    }

    /** 단일 영상 재생 PendingIntent (옵션 1) */
    private fun makePlaySingleIntent(
        ctx: Context,
        video: VideoItem,
        actionIdx: Int = 0
    ): android.app.PendingIntent {
        val intent = android.content.Intent(ctx, AudioPlayerActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("VIDEO_ID", video.videoId)
            putExtra("VIDEO_TITLE", video.title)
            putExtra("VIDEO_CHANNEL", video.channel)
            putExtra("VIDEO_THUMB", video.thumbnail)
            putExtra("FROM_PLAYLIST", false)
            putExtra("FROM_SUB_NOTIFICATION", true)
            putExtra("PLAY_MODE", "single")
        }
        return android.app.PendingIntent.getActivity(
            ctx,
            8000 + actionIdx + video.videoId.hashCode().and(0xFFF),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 순차 재생 PendingIntent (옵션 2) — 큐에 모두 추가 */
    private fun makePlayAllIntent(
        ctx: Context,
        videos: List<Pair<String, VideoItem>>
    ): android.app.PendingIntent {
        // ★ 큐 정보를 직렬화해서 전달 (JSON)
        val queueJson = try {
            val arr = org.json.JSONArray()
            videos.forEach { (chName, v) ->
                val obj = org.json.JSONObject().apply {
                    put("videoId", v.videoId)
                    put("title", v.title)
                    put("channel", v.channel.ifBlank { chName })
                    put("thumbnail", v.thumbnail)
                }
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }

        val first = videos.firstOrNull()?.second

        val intent = android.content.Intent(ctx, AudioPlayerActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("VIDEO_ID", first?.videoId ?: "")
            putExtra("VIDEO_TITLE", first?.title ?: "")
            putExtra("VIDEO_CHANNEL", first?.channel ?: "")
            putExtra("VIDEO_THUMB", first?.thumbnail ?: "")
            putExtra("FROM_PLAYLIST", true)             // ★ 큐 유지
            putExtra("FROM_SUB_NOTIFICATION", true)
            putExtra("PLAY_MODE", "queue")
            putExtra("QUEUE_JSON", queueJson)           // ★ 큐 데이터
        }
        return android.app.PendingIntent.getActivity(
            ctx,
            9001,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** ★ "나중에" PendingIntent — 나중에 목록 저장 + 알림 제거 */
    private fun makeLaterIntent(
        ctx: Context,
        video: VideoItem,
        notifId: Int
    ): android.app.PendingIntent {
        val intent = android.content.Intent(ctx, NotificationActionReceiver::class.java).apply {
            action = "ACTION_SAVE_LATER"
            putExtra("NOTIF_ID", notifId)
            putExtra("VIDEO_ID", video.videoId)
            putExtra("VIDEO_TITLE", video.title)
            putExtra("VIDEO_CHANNEL", video.channel)
            putExtra("VIDEO_THUMB", video.thumbnail)
        }
        return android.app.PendingIntent.getBroadcast(
            ctx,
            10000 + notifId,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
