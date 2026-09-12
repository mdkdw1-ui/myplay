package com.example.myplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var etHomeSearch: EditText

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

        etHomeSearch = findViewById(R.id.etHomeSearch)

        etHomeSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etHomeSearch.text.toString().trim()
                if (q.isEmpty()) {
                    Toast.makeText(this, "검색어를 입력하세요", Toast.LENGTH_SHORT).show()
                } else {
                    RecentSearches.add(this, q)
                    etHomeSearch.setText("")
                    startActivity(Intent(this, SearchActivity::class.java)
                        .putExtra("QUERY", q))
                }
                true
            } else false
        }

        findViewById<View>(R.id.btnStartSearch).setOnClickListener {
            etHomeSearch.requestFocus()
        }
        findViewById<View>(R.id.tvHistoryMore).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        historyAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        relatedAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        channelAdapter = ChannelAdapter { c -> openChannel(c) }

        val rvHistory = findViewById<RecyclerView>(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvHistory.adapter = historyAdapter

        val rvRelated = findViewById<RecyclerView>(R.id.rvRelated)
        rvRelated.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRelated.adapter = relatedAdapter

        val rvChannels = findViewById<RecyclerView>(R.id.rvChannels)
        rvChannels.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvChannels.adapter = channelAdapter

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
                    RecentSearches.add(this@MainActivity, q)
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
                val section = findViewById<View>(R.id.sectionHistory)
                val emptyState = findViewById<View>(R.id.emptyState)

                if (list.isEmpty()) {
                    section.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    findViewById<View>(R.id.sectionRelated).visibility = View.GONE
                    findViewById<View>(R.id.sectionChannels).visibility = View.GONE
                } else {
                    section.visibility = View.VISIBLE
                    emptyState.visibility = View.GONE

                    val home = list.take(10).map {
                        HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                    }
                    historyAdapter.submit(home)

                    loadRelated(list.first().videoId)

                    // 가장 자주 등장하는 채널명 → 채널 검색
                    val topChannel = list.map { it.channel }
                        .filter { it.isNotBlank() }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                    if (topChannel != null) loadChannels(topChannel)
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

    private fun loadChannels(baseChannelName: String) {
        lifecycleScope.launch {
            val results = YouTubeChannels.search(baseChannelName)
            // 자기 자신 제외 + 유효 채널만
            val filtered = results
                .filter { it.name != baseChannelName && it.thumbnail.isNotEmpty() }
                .take(10)
            if (filtered.isEmpty()) {
                findViewById<View>(R.id.sectionChannels).visibility = View.GONE
                return@launch
            }
            findViewById<View>(R.id.sectionChannels).visibility = View.VISIBLE
            channelAdapter.submit(filtered)
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

    private fun openChannel(c: ChannelItem) {
        // 채널명으로 검색
        RecentSearches.add(this, c.name)
        startActivity(Intent(this, SearchActivity::class.java)
            .putExtra("QUERY", c.name))
    }
}
