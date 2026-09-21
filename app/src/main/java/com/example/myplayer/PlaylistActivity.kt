package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {

    private lateinit var adapter: SearchAdapter
    private var nextContinuation: String? = null
    private var loadingMore = false
    private var playlistId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        playlistId = intent.getStringExtra("PLAYLIST_ID") ?: ""
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        if (playlistId.isBlank()) {
            tvStatus.text = "재생목록 ID 없음"
            return
        }

        adapter = SearchAdapter { item ->
            // ★ 탭한 영상 + 나머지를 큐에 채워 순차 재생
            val all = currentList
            if (all.size > 1) {
                // Feature 8: 전체 플레이리스트를 큐에 넣고 현재곡 지정
                QueueManager.clear(this)
                for (v in all) {
                    QueueManager.add(this, HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
                }
                QueueManager.setCurrent(this, item.videoId)
            }

            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
                putExtra("VIDEO_CHANNEL", item.channel)
                putExtra("VIDEO_THUMB", item.thumbnail)
            })
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as LinearLayoutManager
                if (!loadingMore && nextContinuation != null && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    loadMore()
                }
            }
        })

        progress.visibility = View.VISIBLE
        tvStatus.text = "재생목록 불러오는 중..."

        lifecycleScope.launch {
            val page = YouTubePlaylist.fetch(playlistId)
            progress.visibility = View.GONE
            tvTitle.text = page.title.ifBlank { "재생목록" }
            tvStatus.text = "${page.videos.size}개"
            nextContinuation = page.continuation
            currentList.clear()
            currentList.addAll(page.videos)
            adapter.submit(page.videos)
        }
    }

    private val currentList = mutableListOf<VideoItem>()

    private fun loadMore() {
        val token = nextContinuation ?: return
        loadingMore = true
        lifecycleScope.launch {
            val page = YouTubePlaylist.fetchMore(token)
            nextContinuation = page.continuation
            currentList.addAll(page.videos)
            adapter.append(page.videos)
            loadingMore = false
        }
    }
}
