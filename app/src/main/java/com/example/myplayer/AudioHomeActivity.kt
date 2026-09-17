package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 오디오 모드 홈 — 추천 플레이리스트 목록
 */
class AudioHomeActivity : AppCompatActivity() {

    data class AudioPlaylist(
        val emoji: String,
        val title: String,
        val subtitle: String,
        val videos: List<VideoItem>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_home)

        val container = findViewById<android.widget.LinearLayout>(R.id.container)
        val progress = findViewById<View>(R.id.progress)
        val emptyBox = findViewById<View>(R.id.emptyBox)

        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val playlists = buildPlaylists()
            progress.visibility = View.GONE

            if (playlists.isEmpty()) {
                emptyBox.visibility = View.VISIBLE
                return@launch
            }

            for (pl in playlists) {
                container.addView(makeSection(pl))
            }
        }
    }

    private suspend fun buildPlaylists(): List<AudioPlaylist> {
        val out = mutableListOf<AudioPlaylist>()

        val history = try {
            HistoryDatabase.get(applicationContext).historyDao().getAll().first()
        } catch (e: Exception) { emptyList() }

        val bookmarks = try {
            HistoryDatabase.get(applicationContext).bookmarkDao().getAll().first()
        } catch (e: Exception) { emptyList() }

        val disliked = getSharedPreferences("audio_prefs", MODE_PRIVATE)
            .getStringSet("disliked_ids", emptySet()) ?: emptySet()

        // 1) 최근 들은 곡 (오디오 모드 = positionMs 기준 30초 이상)
        val recentAudio = history
            .filter { it.positionMs > 30_000 && it.videoId !in disliked }
            .sortedByDescending { it.watchedAt }
            .map { VideoItem(it.videoId, it.title, it.channel, it.thumbnail) }
            .distinctBy { it.videoId }
            .take(30)

        if (recentAudio.size >= 2) {
            out.add(AudioPlaylist("🔁", "최근 들은 곡", "${recentAudio.size}곡", recentAudio))
        }

        // 2) 좋아요 (북마크)
        val bmVideos = bookmarks
            .filter { it.videoId !in disliked }
            .map { VideoItem(it.videoId, it.title, it.channel, it.thumbnail) }
            .distinctBy { it.videoId }
            .take(30)

        if (bmVideos.size >= 2) {
            out.add(AudioPlaylist("❤️", "좋아요한 곡", "${bmVideos.size}곡", bmVideos))
        }

        // 3) 자주 듣는 채널
        val topChannel = history
            .filter { it.channel.isNotBlank() }
            .groupingBy { it.channel }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .firstOrNull()?.key

        if (topChannel != null) {
            val chVideos = history
                .filter { it.channel == topChannel && it.videoId !in disliked }
                .sortedByDescending { it.watchedAt }
                .map { VideoItem(it.videoId, it.title, it.channel, it.thumbnail) }
                .distinctBy { it.videoId }
                .take(30)
            if (chVideos.size >= 2) {
                out.add(AudioPlaylist("🎵", "$topChannel 더 듣기", "${chVideos.size}곡", chVideos))
            }
        }

        // 4) 인기 급상승 오디오 (YouTube 트렌딩에서 노래만)
        try {
            val trending = YouTubeTrending.fetch()
            if (trending.isNotEmpty()) {
                out.add(AudioPlaylist("🎧", "지금 인기 오디오", "${trending.size}곡", trending.take(20)))
            }
        } catch (e: Exception) { }

        // 5) 내 취향 (여러 채널 섞기)
        val mixed = history
            .filter { it.videoId !in disliked }
            .sortedByDescending { it.watchedAt }
            .map { VideoItem(it.videoId, it.title, it.channel, it.thumbnail) }
            .distinctBy { it.channel }
            .take(30)

        if (mixed.size >= 3) {
            out.add(AudioPlaylist("🌟", "내 취향 믹스", "${mixed.size}곡", mixed))
        }

        return out
    }

    private fun makeSection(pl: AudioPlaylist): View {
        val wrapper = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 제목
        wrapper.addView(android.widget.TextView(this).apply {
            text = "${pl.emoji} ${pl.title}"
            textSize = 16f
            setTextColor(0xFFF5F5F7.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (2 * resources.displayMetrics.density).toInt()
            )
        })

        // 부제
        wrapper.addView(android.widget.TextView(this).apply {
            text = pl.subtitle
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                0,
                (20 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
        })

        // 가로 리스트
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AudioHomeActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            clipToPadding = false
            adapter = HorizontalVideoAdapter { v ->
                // ★ 전체 플레이리스트 큐로 재생
                playPlaylist(pl, v)
            }.also { a ->
                a.submit(pl.videos.map {
                    HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                })
            }
        }
        wrapper.addView(rv)

        // ▶ 전체 재생 버튼
        val playAll = com.google.android.material.button.MaterialButton(this).apply {
            text = "▶ 전체 재생"
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (20 * resources.displayMetrics.density).toInt()
                setMargins(m, (10 * resources.displayMetrics.density).toInt(), m, 0)
            }
            setOnClickListener {
                if (pl.videos.isNotEmpty()) {
                    playPlaylist(pl, null)
                }
            }
        }
        wrapper.addView(playAll)

        return wrapper
    }

    /** ★ 플레이리스트 재생 — 큐 채우기 */
    private fun playPlaylist(pl: AudioPlaylist, firstVideo: HomeVideo?) {
        if (pl.videos.isEmpty()) return

        val startId = firstVideo?.videoId ?: pl.videos[0].videoId
        val startTitle = firstVideo?.title ?: pl.videos[0].title
        val startChannel = firstVideo?.channel ?: pl.videos[0].channel
        val startThumb = firstVideo?.thumbnail ?: pl.videos[0].thumbnail

        val startIdx = pl.videos.indexOfFirst { it.videoId == startId }.coerceAtLeast(0)

        QueueManager.clear(this)
        for (i in (startIdx + 1) until pl.videos.size) {
            val v = pl.videos[i]
            QueueManager.add(this, HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
        }

        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", startId)
            putExtra("VIDEO_TITLE", startTitle)
            putExtra("VIDEO_CHANNEL", startChannel)
            putExtra("VIDEO_THUMB", startThumb)
        })
    }
}
