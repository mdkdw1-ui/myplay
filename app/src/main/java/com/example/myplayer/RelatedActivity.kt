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
            val results = YouTubeRelated.fetch(videoId)
            progress.visibility = View.GONE
            tvStatus.text = "${results.size}개"
            adapter.submit(results)
        }
    }
}
