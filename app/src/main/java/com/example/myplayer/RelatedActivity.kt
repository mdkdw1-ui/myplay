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

class RelatedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_related)

        val videoId = intent.getStringExtra("VIDEO_ID") ?: ""
        val videoTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val videoChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""

        val tvHeader = findViewById<TextView>(R.id.tvHeader)
        tvHeader.text = videoTitle.ifEmpty { "관련 영상" }

        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        val adapter = SearchAdapter { item ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
                putExtra("VIDEO_CHANNEL", item.channel)
                putExtra("VIDEO_THUMB", item.thumbnail)
            }
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "관련 영상 불러오는 중..."

        lifecycleScope.launch {
            // 1) YouTube next 엔드포인트 시도
            var results = YouTubeRelated.fetch(videoId)

            // 2) 폴백 1: 제목 키워드 검색
            if (results.size < 3 && videoTitle.isNotBlank()) {
                val keyword = videoTitle.split(" ")
                    .filter { it.isNotBlank() }
                    .take(5)
                    .joinToString(" ")
                if (keyword.isNotEmpty()) {
                    tvStatus.text = "제목 기반 검색 중..."
                    val searchResults = YouTubeSearch.search(keyword)
                    if (searchResults.isNotEmpty()) {
                        results = searchResults.filter { it.videoId != videoId }
                    }
                }
            }

            // 3) 폴백 2: 채널명 검색
            if (results.isEmpty() && videoChannel.isNotBlank()) {
                tvStatus.text = "채널 기반 검색 중..."
                val searchResults = YouTubeSearch.search(videoChannel)
                results = searchResults.filter { it.videoId != videoId }
            }

            progress.visibility = View.GONE

            if (results.isEmpty()) {
                tvStatus.text = "관련 영상을 찾을 수 없습니다"
            } else {
                tvStatus.text = "${results.size}개"
            }
            adapter.submit(results)
        }
    }
}
