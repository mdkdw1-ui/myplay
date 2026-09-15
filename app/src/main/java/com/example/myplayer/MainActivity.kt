package com.example.myplayer

import android.Manifest
import android.app.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var historyAdapter: HorizontalVideoAdapter
    private lateinit var relatedAdapter: HorizontalVideoAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var bookmarkAdapter: HorizontalVideoAdapter
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
                    startActivity(Intent(this, SearchActivity::class.java).putExtra("QUERY", q))
                }
                true
            } else false
        }
        findViewById<View>(R.id.btnStartSearch).setOnClickListener { etHomeSearch.requestFocus() }
        findViewById<View>(R.id.tvHistoryMore).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.tvClearSearches).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("최근 검색어")
                .setMessage("모두 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    RecentSearches.clear(this)
                    loadRecentSearches()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        historyAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        relatedAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        channelAdapter = ChannelAdapter { c -> openChannel(c) }
        bookmarkAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }

        findViewById<RecyclerView>(R.id.rvHistory).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = historyAdapter
        }
        findViewById<RecyclerView>(R.id.rvRelated).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedAdapter
        }
        findViewById<RecyclerView>(R.id.rvChannels).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = channelAdapter
        }
        findViewById<RecyclerView>(R.id.rvBookmarks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = bookmarkAdapter
        }

        loadRecentSearches()
        loadBookmarks()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadRecentSearches()
        loadBookmarks()
        loadHistory()
    }

    private fun loadRecentSearches() {
        val section = findViewById<View>(R.id.sectionRecent)
        val container = findViewById<LinearLayout>(R.id.chipsContainer)
        container.removeAllViews()

        val list = RecentSearches.get(this)
        if (list.isEmpty()) { section.visibility = View.GONE; return }
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
                    startActivity(Intent(this@MainActivity, SearchActivity::class.java).putExtra("QUERY", q))
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("검색어 삭제")
                        .setMessage("\"$q\" 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ ->
                            RecentSearches.remove(this@MainActivity, q)
                            loadRecentSearches()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                    true
                }
            }
            container.addView(chip)
        }
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            val section = findViewById<View>(R.id.sectionBookmarks)
            try {
                HistoryDatabase.get(applicationContext).bookmarkDao().getAll().collect { list ->
                    if (list.isEmpty()) {
                        section.visibility = View.GONE
                    } else {
                        section.visibility = View.VISIBLE
                        bookmarkAdapter.submit(list.take(10).map {
                            HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                        })
                    }
                }
            } catch (e: Exception) {
                section.visibility = View.GONE
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            HistoryDatabase.get(applicationContext).historyDao().getAll().collect { list ->
                val section = findViewById<View>(R.id.sectionHistory)
                val emptyState = findViewById<View>(R.id.emptyState)

                if (list.isEmpty()) {
                    section.visibility = View.GONE
                    emptyState.visibility = View.VISIBLE
                    findViewById<View>(R.id.sectionRelated).visibility = View.GONE
                    findViewById<View>(R.id.sectionChannels).visibility = View.GONE
                    return@collect
                }

                section.visibility = View.VISIBLE
                emptyState.visibility = View.GONE

                historyAdapter.submit(list.take(10).map {
                    HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                })

                loadRecommended(list)

                val topChannel = list.map { it.channel }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
                if (topChannel != null) loadChannels(topChannel)
            }
        }
    }

    private suspend fun buildRecommendations(history: List<HistoryEntity>): List<VideoItem> =
        withContext(Dispatchers.IO) {
            val seeds = history.take(5)
            val watchedIds = history.map { it.videoId }.toSet()
            val channelWeight = history.map { it.channel }
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
            val scoreMap = mutableMapOf<String, Pair<VideoItem, Int>>()

            for (seed in seeds) {
                try {
                    var related = YouTubeRelated.fetch(seed.videoId)
                    if (related.size < 3) {
                        val keyword = seed.title.split(" ")
                            .filter { it.isNotBlank() }.take(4).joinToString(" ")
                        if (keyword.isNotEmpty()) related = YouTubeSearch.search(keyword)
                    }
                    for (v in related) {
                        if (v.videoId in watchedIds) continue
                        if (v.videoId == seed.videoId) continue
                        val existing = scoreMap[v.videoId]
                        val bonus = (channelWeight[v.channel] ?: 0)
                        val addScore = 1 + bonus
                        scoreMap[v.videoId] = if (existing != null)
                            existing.copy(second = existing.second + addScore)
                        else Pair(v, addScore)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            scoreMap.values.sortedByDescending { it.second }.map { it.first }
        }

    private fun loadRecommended(history: List<HistoryEntity>) {
        lifecycleScope.launch {
            val recs = buildRecommendations(history)
            if (recs.isEmpty()) {
                findViewById<View>(R.id.sectionRelated).visibility = View.GONE
                return@launch
            }
            findViewById<View>(R.id.sectionRelated).visibility = View.VISIBLE
            relatedAdapter.submit(recs.take(10).map {
                HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
            })
        }
    }

    private fun loadChannels(baseChannelName: String) {
        lifecycleScope.launch {
            val results = YouTubeChannels.search(baseChannelName)
            val filtered = results.filter { it.name != baseChannelName && it.thumbnail.isNotEmpty() }.take(10)
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

    // ★ 채널 페이지로 이동
    private fun openChannel(c: ChannelItem) {
        val intent = Intent(this, ChannelActivity::class.java).apply {
            putExtra("CHANNEL_ID", c.channelId)
            putExtra("CHANNEL_NAME", c.name)
        }
        startActivity(intent)
    }
}
