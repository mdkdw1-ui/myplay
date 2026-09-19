package com.example.myplayer

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LyricsActivity : AppCompatActivity() {

    private lateinit var adapter: LyricsAdapter
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var syncJob: Job? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var lastIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)

        val videoId = intent.getStringExtra("VIDEO_ID") ?: ""
        val title = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val channel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val subtitleUrl = intent.getStringExtra("SUBTITLE_URL") ?: ""

        Log.d("LyricsActivity", "videoId=$videoId, subUrl=${subtitleUrl.take(80)}")

        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvChannel).text = channel

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)

        adapter = LyricsAdapter { line ->
            mediaController?.seekTo(line.startMs)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            startSync()
        }, MoreExecutors.directExecutor())

        if (subtitleUrl.isBlank()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "🎤\n\n이 곡은 자막/가사가 없습니다"
            progress.visibility = View.GONE
            return
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            lyricLines = LyricsParser.fetchLyrics(subtitleUrl)
            progress.visibility = View.GONE

            Log.d("LyricsActivity", "loaded ${lyricLines.size} lines")

            if (lyricLines.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "🎤\n\n가사를 불러올 수 없습니다\n(자막 URL: ${subtitleUrl.take(40)}...)"
                Toast.makeText(
                    this@LyricsActivity,
                    "가사 0줄 (자막 서버 응답 없음)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                tvEmpty.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                adapter.submit(lyricLines)

                // 초기 스크롤
                val pos = mediaController?.currentPosition ?: 0L
                val idx = lyricLines.indexOfLast { it.startMs <= pos }
                if (idx >= 0) {
                    recycler.post {
                        (recycler.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, recycler.height / 3)
                    }
                }
            }
        }
    }

    private fun startSync() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            val recycler = findViewById<RecyclerView>(R.id.recycler)
            while (isActive) {
                val pos = mediaController?.currentPosition ?: 0L
                if (lyricLines.isNotEmpty()) {
                    val idx = lyricLines.indexOfLast { it.startMs <= pos }
                    if (idx >= 0 && idx != lastIndex) {
                        lastIndex = idx
                        adapter.setCurrentIndex(idx)
                        (recycler.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, recycler.height / 3)
                    }
                }
                delay(300)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
