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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmartPlaylistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smart_playlist)

        val container = findViewById<android.widget.LinearLayout>(R.id.container)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val emptyBox = findViewById<View>(R.id.emptyBox)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)

        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val history = try {
                HistoryDatabase.get(applicationContext).historyDao().getAll().first()
            } catch (e: Exception) { emptyList() }

            val lists = SmartPlaylistGenerator.generate(history)
            progress.visibility = View.GONE

            if (lists.isEmpty()) {
                emptyBox.visibility = View.VISIBLE
                return@launch
            }

            tvSubtitle.text = "${lists.size}개 자동 생성 · ${history.size}개 기록 기반"

            for (pl in lists) {
                // 섹션 제목
                val header = TextView(this@SmartPlaylistActivity).apply {
                    text = "${pl.emoji} ${pl.name} (${pl.videos.size})"
                    textSize = 15f
                    setTextColor(0xFFF5F5F7.toInt())
                    setPadding(
                        (20 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt()
                    )
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(header)

                // 가로 RecyclerView
                val rv = RecyclerView(this@SmartPlaylistActivity).apply {
                    layoutManager = LinearLayoutManager(
                        this@SmartPlaylistActivity,
                        LinearLayoutManager.HORIZONTAL, false
                    )
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    val pad = (20 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, 0)
                    clipToPadding = false
                    adapter = HorizontalVideoAdapter { v -> openPlayer(v) }.also { a ->
                        a.submit(pl.videos.map {
                            HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                        })
                    }
                }
                container.addView(rv)

                // "전체 재생" 버튼
                val playAll = com.google.android.material.button.MaterialButton(
                    this@SmartPlaylistActivity
                ).apply {
                    text = "▶ 전체 재생 (${pl.videos.size})"
                    textSize = 12f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        val m = (20 * resources.displayMetrics.density).toInt()
                        setMargins(m, (10 * resources.displayMetrics.density).toInt(), m, 0)
                    }
                    setOnClickListener {
                        QueueManager.clear(this@SmartPlaylistActivity)
                        for (v in pl.videos.drop(1)) {
                            QueueManager.add(this@SmartPlaylistActivity, HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
                        }
                        val first = pl.videos.first()
                        startActivity(Intent(this@SmartPlaylistActivity, PlayerActivity::class.java).apply {
                            putExtra("VIDEO_ID", first.videoId)
                            putExtra("VIDEO_TITLE", first.title)
                            putExtra("VIDEO_CHANNEL", first.channel)
                            putExtra("VIDEO_THUMB", first.thumbnail)
                        })
                    }
                }
                container.addView(playAll)
            }
        }
    }

    private fun openPlayer(v: HomeVideo) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", v.videoId)
            putExtra("VIDEO_TITLE", v.title)
            putExtra("VIDEO_CHANNEL", v.channel)
            putExtra("VIDEO_THUMB", v.thumbnail)
        })
    }
}
