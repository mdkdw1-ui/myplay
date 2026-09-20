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
        val artist = intent.getStringExtra("VIDEO_ARTIST") ?: channel
        val subtitleUrl = intent.getStringExtra("SUBTITLE_URL") ?: ""

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

        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            // 1차: YouTube 자막 시도
            var lines: List<LyricLine> = emptyList()
            if (subtitleUrl.isNotBlank()) {
                lines = LyricsParser.fetchLyrics(subtitleUrl)
                Log.d("LyricsActivity", "YouTube subs: ${lines.size}")
            }

            // 2차: YouTube 자막 없으면 lyrics.ovh
            if (lines.isEmpty()) {
                Log.d("LyricsActivity", "fallback to lyrics.ovh")
                val text = LyricsFetcher.fetch(artist, title)
                if (text != null) {
                    // 가사 텍스트를 LyricLine으로 변환 (타임스탬프 없이)
                    val textLines = text.split("\n")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    lines = textLines.mapIndexed { i, t ->
                        LyricLine((i * 5000L), t)  // 5초 간격 가짜 타임스탬프
                    }
                    Log.d("LyricsActivity", "lyrics.ovh: ${lines.size}")
                }
            }

            progress.visibility = View.GONE

            if (lines.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "🎤\n\n가사를 찾을 수 없습니다\n(YouTube 자막 X, lyrics.ovh 실패)"
                Toast.makeText(this@LyricsActivity, "가사 없음", Toast.LENGTH_LONG).show()
            } else {
                tvEmpty.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                lyricLines = lines
                adapter.submit(lines)
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
                delay(500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        MediaController.releaseFuture(controllerFuture)
    }
}
