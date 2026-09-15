package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

class ChannelActivity : AppCompatActivity() {

    private lateinit var adapter: SearchAdapter
    private var nextContinuation: String? = null
    private var loadingMore = false
    private var channelId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)

        channelId = intent.getStringExtra("CHANNEL_ID") ?: ""
        val fallbackName = intent.getStringExtra("CHANNEL_NAME") ?: ""

        val ivAvatar = findViewById<ImageView>(R.id.ivAvatar)
        val tvChannelName = findViewById<TextView>(R.id.tvChannelName)
        val tvSubscribers = findViewById<TextView>(R.id.tvSubscribers)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        tvChannelName.text = fallbackName
        tvSubscribers.text = ""

        adapter = SearchAdapter { item ->
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
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!loadingMore && nextContinuation != null && lastVisible >= total - 3) {
                    loadMore()
                }
            }
        })

        if (channelId.isBlank()) {
            tvStatus.text = "채널 ID 없음"
            tvStatus.visibility = View.VISIBLE
            return
        }

        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "채널 정보 불러오는 중... (id=$channelId)"

        lifecycleScope.launch {
            val (info, page) = YouTubeChannel.fetch(channelId)
            progress.visibility = View.GONE

            if (info != null) {
                tvChannelName.text = info.name.ifBlank { fallbackName }
                tvSubscribers.text = listOf(info.subscribers, info.videoCount)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (info.avatar.isNotBlank()) {
                    Glide.with(ivAvatar).load(info.avatar).circleCrop().into(ivAvatar)
                }
            }

            nextContinuation = page.continuation
            val dbg = YouTubeChannel.lastDebug
            tvStatus.text = if (page.videos.isEmpty())
                "영상 없음 ($dbg)"
            else "${page.videos.size}개 · $dbg"
            adapter.submit(page.videos)
        }
    }

    private fun loadMore() {
        val token = nextContinuation ?: return
        loadingMore = true
        lifecycleScope.launch {
            val page = YouTubeChannel.fetchMore(token)
            nextContinuation = page.continuation
            adapter.append(page.videos)
            loadingMore = false
        }
    }
}
