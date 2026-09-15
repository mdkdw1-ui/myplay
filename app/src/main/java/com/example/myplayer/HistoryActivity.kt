package com.example.myplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private enum class SortMode(val label: String) {
        NEWEST("최신순"),
        OLDEST("오래된순"),
        TITLE("제목순"),
        CHANNEL("채널순"),
        PROGRESS("진행률순")
    }

    private var sortMode = SortMode.NEWEST
    private var currentList: List<HistoryEntity> = emptyList()
    private var currentQuery: String = ""
    private var channelFilter: String? = null  // null = 전체
    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: View
    private lateinit var tvClear: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvClear = findViewById(R.id.tvClearHistory)
        val btnSort = findViewById<TextView>(R.id.btnSort)
        val etSearch = findViewById<EditText>(R.id.etSearch)

        adapter = HistoryAdapter(
            onClick = { item -> playVideo(item) },
            onRelatedClick = { item -> showRelated(item) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        tvClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("시청 기록")
                .setMessage("모든 기록을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    lifecycleScope.launch {
                        HistoryDatabase.get(applicationContext).historyDao().clearAll()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        btnSort.setOnClickListener { showSortDialog() }

        lifecycleScope.launch {
            HistoryDatabase.get(applicationContext).historyDao().getAll().collect { list ->
                currentList = list
                render()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                render()
            }
        })

        updateSortLabel()
        loadChannelChips()
    }

    private fun loadChannelChips() {
        val scroll = findViewById<View>(R.id.scrollChannels)
        val container = findViewById<android.widget.LinearLayout>(R.id.chipsChannels)
        container.removeAllViews()

        // 채널별 시청 횟수
        val counts = currentList.map { it.channel }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(10)

        if (counts.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE

        // "전체" 칩
        container.addView(makeChip("전체", channelFilter == null) {
            channelFilter = null
            updateChips()
            render()
        })

        for ((name, count) in counts) {
            container.addView(makeChip("$name ($count)", channelFilter == name) {
                channelFilter = if (channelFilter == name) null else name
                updateChips()
                render()
            })
        }
    }

    private fun makeChip(text: String, selected: Boolean, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@HistoryActivity,
                if (selected) R.color.white else R.color.text_primary
            ))
            if (selected) setBackgroundColor(0xFFFF2D55.toInt())
            else setBackgroundResource(R.drawable.bg_search_input)
            val p = (12 * resources.displayMetrics.density).toInt()
            val pv = (6 * resources.displayMetrics.density).toInt()
            setPadding(p, pv, p, pv)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            gravity = android.view.Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun updateChips() {
        // 칩 상태 갱신 (재생성)
        loadChannelChips()
    }

    private fun render() {
        var filtered = currentList
        // 채널 필터
        if (channelFilter != null) {
            filtered = filtered.filter { it.channel == channelFilter }
        }
        // 검색어 필터
        if (currentQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(currentQuery, ignoreCase = true) ||
                it.channel.contains(currentQuery, ignoreCase = true)
            }
        }
        val sorted = when (sortMode) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.watchedAt }
            SortMode.OLDEST -> filtered.sortedBy { it.watchedAt }
            SortMode.TITLE -> filtered.sortedBy { it.title }
            SortMode.CHANNEL -> filtered.sortedBy { it.channel }
            SortMode.PROGRESS -> filtered.sortedByDescending { it.progressPercent }
        }
        adapter.submit(sorted)
        tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        tvClear.visibility = if (currentList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSortDialog() {
        val modes = SortMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("정렬")
            .setItems(labels) { _, i ->
                sortMode = modes[i]
                updateSortLabel()
                render()
            }
            .show()
    }

    private fun updateSortLabel() {
        findViewById<TextView>(R.id.btnSort).text = "⇅ ${sortMode.label}"
    }

    private fun playVideo(item: HistoryEntity) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", item.videoId)
            putExtra("VIDEO_TITLE", item.title)
            putExtra("VIDEO_CHANNEL", item.channel)
            putExtra("VIDEO_THUMB", item.thumbnail)
        }
        startActivity(intent)
    }

    private fun showRelated(item: HistoryEntity) {
        val intent = Intent(this, RelatedActivity::class.java).apply {
            putExtra("VIDEO_ID", item.videoId)
            putExtra("VIDEO_TITLE", item.title)
            putExtra("VIDEO_CHANNEL", item.channel)
        }
        startActivity(intent)
    }
}
