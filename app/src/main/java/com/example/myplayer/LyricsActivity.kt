package com.example.myplayer

import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
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
            return
        }

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            lyricLines = LyricsParser.fetchLyrics(subtitleUrl)
            progress.visibility = View.GONE
            if (lyricLines.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
            } else {
                adapter.submit(lyricLines)
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
                        // 현재 라인으로 스크롤
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
