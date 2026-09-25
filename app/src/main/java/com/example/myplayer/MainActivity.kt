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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var historyAdapter: HorizontalVideoAdapter
    private lateinit var relatedAdapter: HorizontalVideoAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var bookmarkAdapter: HorizontalVideoAdapter
    private lateinit var downloadsAdapter: HorizontalVideoAdapter
    private lateinit var trendingAdapter: HorizontalVideoAdapter
    private lateinit var subAdapter: ChannelAdapter
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
        // 배경 그라데이션
        val rootLayout = findViewById<View>(R.id.rootScroll)
        val appPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedGrad = appPref.getString("home_gradient", "red") ?: "red"
        applyHomeGradient(rootLayout, savedGrad)

        findViewById<View>(R.id.btnTheme).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🎨 디자인 설정")
                .setItems(arrayOf("배경 테마", "카드 크기")) { _, which ->
                    when (which) {
                        0 -> {
                            val options = arrayOf("🔴 빨강 (기본)", "🔵 파랑", "🟢 초록", "🟣 보라")
                            val keys = arrayOf("red", "blue", "green", "purple")
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setItems(options) { _, i ->
                                    appPref.edit().putString("home_gradient", keys[i]).apply()
                                    applyHomeGradient(rootLayout, keys[i])
                                }.show()
                        }
                        1 -> {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setItems(arrayOf("작게", "보통 (기본)", "크게")) { _, i ->
                                    val key = when (i) { 0 -> "small"; 2 -> "large"; else -> "medium" }
                                    appPref.edit().putString("card_size", key).apply()
                                    recreate()
                                }.show()
                        }
                    }
                }
                .show()
        }
        findViewById<View>(R.id.btnSmartPlaylist).setOnClickListener {
            startActivity(Intent(this, SmartPlaylistActivity::class.java))
        }
        try {
            findViewById<View>(R.id.btnLocalMedia)?.setOnClickListener {
                startActivity(Intent(this, LocalMediaActivity::class.java))
            }
        } catch (e: Exception) { }

        findViewById<View>(R.id.btnAudioHome).setOnClickListener {
            startActivity(Intent(this, AudioHomeActivity::class.java))
        }
        findViewById<View>(R.id.btnPlaylist).setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "재생목록 URL"
                setPadding(40, 30, 40, 30)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📃 YouTube 재생목록")
                .setMessage("playlist?list=... 형식 URL")
                .setView(input)
                .setPositiveButton("열기") { _, _ ->
                    val url = input.text.toString().trim()
                    val pid = YouTubePlaylist.extractPlaylistId(url)
                    if (pid == null) {
                        Toast.makeText(this, "재생목록 ID를 찾을 수 없음", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivity(Intent(this, PlaylistActivity::class.java)
                            .putExtra("PLAYLIST_ID", pid))
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
        try {
            findViewById<View>(R.id.btnMyPlaylists)?.setOnClickListener {
                startActivity(Intent(this, MyPlaylistsActivity::class.java))
            }
        } catch (e: Exception) { }

        findViewById<View>(R.id.btnStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<View>(R.id.tvHistoryMore).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.tvDownloadsMore).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        findViewById<View>(R.id.tvTrendingMore).setOnClickListener {
            loadTrending()
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
        channelAdapter = ChannelAdapter(onClick = { c -> openChannel(c) })
        bookmarkAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        downloadsAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        trendingAdapter = HorizontalVideoAdapter { v -> openPlayer(v) }
        subAdapter = ChannelAdapter(
            onClick = { c -> openChannel(c) },
            onLongClick = { c -> playChannelAll(c) }
        )

        findViewById<RecyclerView>(R.id.rvHistory).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = historyAdapter
        }
        findViewById<RecyclerView>(R.id.rvRelated).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedAdapter
        }
        findViewById<RecyclerView>(R.id.rvChannels).apply {
            // 2행 그리드 (가로 스크롤)
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                this@MainActivity, 2,
                androidx.recyclerview.widget.GridLayoutManager.HORIZONTAL, false
            )
            adapter = channelAdapter
        }
        findViewById<RecyclerView>(R.id.rvBookmarks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = bookmarkAdapter
        }
        findViewById<RecyclerView>(R.id.rvDownloads).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = downloadsAdapter
        }
        findViewById<RecyclerView>(R.id.rvTrending).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = trendingAdapter
        }
        findViewById<RecyclerView>(R.id.rvSubscriptions).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                this@MainActivity, 2,
                androidx.recyclerview.widget.GridLayoutManager.HORIZONTAL, false
            )
            adapter = subAdapter
        }

        loadRecentSearches()
        loadBookmarks()
        loadDownloads()
        loadSubscriptions()
        loadTrending()
        loadHistory()

        // ★ 구독 알림 스케줄
        scheduleSubscriptionWorker()
    }

    override fun onResume() {
        super.onResume()
        loadRecentSearches()
        loadBookmarks()
        loadDownloads()
        loadHistory()
    }



    private fun loadSubscriptions() {
        lifecycleScope.launch {
            val section = findViewById<View>(R.id.sectionSubscriptions)
            try {
                HistoryDatabase.get(applicationContext).subscriptionDao().getAll().collect { list ->
                    if (list.isEmpty()) {
                        section.visibility = View.GONE
                    } else {
                        section.visibility = View.VISIBLE
                        subAdapter.submit(list.map {
                            ChannelItem(it.channelId, it.name, it.avatar, it.subscribers)
                        })
                    }
                }
            } catch (e: Exception) {
                section.visibility = View.GONE
            }
        }
    }

    private fun loadTrending() {
        lifecycleScope.launch {
            val section = findViewById<View>(R.id.sectionTrending)
            try {
                val list = YouTubeTrending.fetch()
                if (list.isEmpty()) {
                    section.visibility = View.GONE
                } else {
                    section.visibility = View.VISIBLE
                    trendingAdapter.submit(list.take(15).map {
                        HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                    })
                }
            } catch (e: Exception) {
                section.visibility = View.GONE
            }
        }
    }

    private fun loadDownloads() {
        lifecycleScope.launch {
            val section = findViewById<View>(R.id.sectionDownloads)
            try {
                HistoryDatabase.get(applicationContext).downloadDao().getAll().collect { list ->
                    if (list.isEmpty()) {
                        section.visibility = View.GONE
                    } else {
                        section.visibility = View.VISIBLE
                        downloadsAdapter.submit(list.take(10).map {
                            HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                        })
                    }
                }
            } catch (e: Exception) {
                section.visibility = View.GONE
            }
        }
    }


    private fun applyHomeGradient(view: View, key: String) {
        val res = when (key) {
            "blue" -> R.drawable.bg_gradient_blue
            "green" -> R.drawable.bg_gradient_green
            "purple" -> R.drawable.bg_gradient_purple
            else -> R.drawable.bg_gradient_red
        }
        view.setBackgroundResource(res)
    }

    
    private fun scheduleSubscriptionWorker() {
        try {
            val req = androidx.work.PeriodicWorkRequestBuilder<SubscriptionWorker>(
                6, java.util.concurrent.TimeUnit.HOURS
            ).build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork(
                    "sub_check",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
        } catch (e: Exception) { }
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

                // ★ 상위 5개 서로 다른 채널로 다양화
                val topChannels = list.map { it.channel }
                    .filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .take(5).map { it.key }
                if (topChannels.isNotEmpty()) loadChannelsMulti(topChannels)
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

    private fun loadChannelsMulti(baseChannelNames: List<String>) {
        lifecycleScope.launch {
            val all = mutableListOf<ChannelItem>()
            val seen = mutableSetOf<String>()
            val knownNames = baseChannelNames.toSet()

            // ===== 1) 내가 본 채널 (최대 5개) =====
            for (name in baseChannelNames) {
                if (all.size >= 5) break
                val res = YouTubeChannels.search(name)
                for (c in res) {
                    if (c.channelId.isBlank() || c.channelId in seen) continue
                    seen.add(c.channelId)
                    all.add(c)
                    if (all.size >= 5) break
                }
            }

            // ===== 2) 연관 채널 (관련 영상 + 제목 키워드) =====
            try {
                val history = withContext(Dispatchers.IO) {
                    HistoryDatabase.get(applicationContext).historyDao().getAll().first()
                }

                val relatedNames = mutableSetOf<String>()

                // (a) 관련 영상 (next) 의 채널
                for (h in history.take(5)) {
                    if (relatedNames.size >= 20) break
                    val related = YouTubeRelated.fetch(h.videoId)
                    for (v in related) {
                        if (v.channel.isNotBlank()) relatedNames.add(v.channel)
                    }
                }

                // (b) ★ next가 부족하면 제목 키워드 검색
                if (relatedNames.size < 10) {
                    for (h in history.take(5)) {
                        if (relatedNames.size >= 20) break
                        val kw = h.title.split(" ")
                            .filter { it.isNotBlank() && it.length >= 2 }
                            .take(3).joinToString(" ")
                        if (kw.isBlank()) continue
                        try {
                            val res = YouTubeSearch.search(kw)
                            for (v in res) {
                                if (v.channel.isNotBlank()) relatedNames.add(v.channel)
                            }
                        } catch (e: Exception) { }
                    }
                }

                // (c) 이미 본 채널 제외 + 검색
                val candidates = relatedNames
                    .filter { it !in knownNames }
                    .take(10)

                for (name in candidates) {
                    if (all.size >= 10) break
                    val res = YouTubeChannels.search(name)
                    for (c in res) {
                        if (c.channelId.isBlank() || c.channelId in seen) continue
                        seen.add(c.channelId)
                        all.add(c)
                        if (all.size >= 10) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val filtered = all.distinctBy { it.channelId }.take(10)
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
    private fun playChannelAll(c: ChannelItem) {
        lifecycleScope.launch {
            try {
                val (info, page) = YouTubeChannel.fetch(c.channelId, c.name)
                val list = page.videos
                if (list.isEmpty()) {
                    Toast.makeText(this@MainActivity, "영상 없음", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                QueueManager.clear(this@MainActivity)
                for (v in list) {
                    QueueManager.add(this@MainActivity,
                        HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
                }
                val first = list.first()
                QueueManager.setCurrent(this@MainActivity, first.videoId)
                startActivity(Intent(this@MainActivity, AudioPlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", first.videoId)
                    putExtra("VIDEO_TITLE", first.title)
                    putExtra("VIDEO_CHANNEL", first.channel)
                    putExtra("VIDEO_THUMB", first.thumbnail)
                    putExtra("FROM_PLAYLIST", true)
                })
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChannel(c: ChannelItem) {
        val intent = Intent(this, ChannelActivity::class.java).apply {
            putExtra("CHANNEL_ID", c.channelId)
            putExtra("CHANNEL_NAME", c.name)
        }
        startActivity(intent)
    }
}
