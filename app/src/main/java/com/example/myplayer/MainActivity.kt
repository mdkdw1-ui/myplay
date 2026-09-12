package com.example.myplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var historyAdapter: HorizontalVideoAdapter
    private lateinit var relatedAdapter: HorizontalVideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // 검색창 → SearchActivity
        findViewById<View>(R.id.searchBar).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.btnStartSearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.tvHistoryMore).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // 어댑터
        historyAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        relatedAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }

        val rvHistory = findViewById<RecyclerView>(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvHistory.adapter = historyAdapter

        val rvRelated = findViewById<RecyclerView>(R.id.rvRelated)
        rvRelated.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRelated.adapter = relatedAdapter

        loadRecentSearches()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadRecentSearches()
        loadHistory()
    }

    private fun loadRecentSearches() {
        val section = findViewById<View>(R.id.sectionRecent)
        val container = findViewById<LinearLayout>(R.id.chipsContainer)
        container.removeAllViews()

        val list = RecentSearches.get(this)
        if (list.isEmpty()) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE

        for (q in list) {
            val chip = TextView(this).apply {
                text = q
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_search_input)
                val p = (14 * resources.displayMetrics.density).toInt()
                setPadding(p, p / 2, p, p / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                gravity = Gravity.CENTER
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SearchActivity::class.java)
                        .putExtra("QUERY", q))
                }
            }
            container.addView(chip)
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val db = HistoryDatabase.get(applicationContext)
            db.historyDao().getAll().collect { list ->
                // 최근 본 동영상
                val section = findViewById<View>(R.id.sectionHistory)
                val emptyState = findViewById<View>(R.id.emptyState)

                if (list.isEmpty()) {
                    section.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    findViewById<View>(R.id.sectionRelated).visibility = View.GONE
                } else {
                    section.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE

                    val home = list.take(10).map {
                        HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                    }
                    historyAdapter.submit(home)

                    // 가장 최근 영상 기반 관련 동영상
                    loadRelated(list.first().videoId)
                }
            }
        }
    }

    private fun loadRelated(baseVideoId: String) {
        lifecycleScope.launch {
            val results = YouTubeRelated.fetch(baseVideoId)
            if (results.isEmpty()) {
                findViewById<View>(R.id.sectionRelated).visibility = View.GONE
                return@launch
            }
            findViewById<View>(R.id.sectionRelated).visibility = View.VISIBLE
            relatedAdapter.submit(
                results.take(10).map {
                    HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                }
            )
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
