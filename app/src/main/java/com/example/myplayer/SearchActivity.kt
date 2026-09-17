package com.example.myplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var adapter: SearchAdapter
    private lateinit var suggestAdapter: SuggestAdapter
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var etQuery: EditText
    private lateinit var rvSuggest: RecyclerView
    private lateinit var scrollFilters: View
    private lateinit var chipSort: TextView
    private lateinit var chipDate: TextView
    private lateinit var chipDuration: TextView

    private var nextContinuation: String? = null
    private var loadingMore = false
    private var suggestJob: Job? = null
    private var suppressSuggest = false

    private var lastQuery: String = ""
    private var filter = SearchFilter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        etQuery = findViewById(R.id.etQuery)
        rvSuggest = findViewById(R.id.rvSuggest)
        scrollFilters = findViewById(R.id.scrollFilters)
        chipSort = findViewById(R.id.chipSort)
        chipDate = findViewById(R.id.chipDate)
        chipDuration = findViewById(R.id.chipDuration)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = SearchAdapter { item ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
                putExtra("VIDEO_CHANNEL", item.channel)
                putExtra("VIDEO_THUMB", item.thumbnail)
            }
            startActivity(intent)
        }
        recycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        recycler.adapter = adapter

        // ★ 터치 리스너 (탭/롱프레스 완벽 분리)
        recycler.addOnItemTouchListener(
            RecyclerTouchListener(
                this, recycler,
                onItemClick = { _, item ->
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra("VIDEO_ID", item.videoId)
                        putExtra("VIDEO_TITLE", item.title)
                        putExtra("VIDEO_CHANNEL", item.channel)
                        putExtra("VIDEO_THUMB", item.thumbnail)
                    }
                    startActivity(intent)
                },
                onItemLongPress = { _, item, child ->
                    PreviewPlayer.start(this, child as android.view.ViewGroup, item.videoId, item.thumbnail)
                },
                onItemRelease = { _, item, child ->
                    // 손 떼면 6초 후 정지
                    child.postDelayed({
                        PreviewPlayer.stop()
                    }, 6000)
                }
            )
        )

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!loadingMore && nextContinuation != null && lastVisible >= total - 3) {
                    loadMore()
                }
            }
        })

        suggestAdapter = SuggestAdapter { picked ->
            suppressSuggest = true
            etQuery.setText(picked)
            etQuery.setSelection(picked.length)
            suppressSuggest = false
            suggestJob?.cancel()
            suggestAdapter.clear()
            rvSuggest.visibility = View.GONE
            hideKeyboard()
            performSearch(picked)
        }
        rvSuggest.layoutManager = LinearLayoutManager(this)
        rvSuggest.adapter = suggestAdapter

        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggest) return
                val q = s?.toString()?.trim() ?: ""
                if (q.length < 1) {
                    rvSuggest.visibility = View.GONE
                    suggestAdapter.clear()
                    return
                }
                suggestJob?.cancel()
                suggestJob = lifecycleScope.launch {
                    delay(220)
                    if (suppressSuggest) return@launch
                    val list = YouTubeSuggest.suggest(q)
                    if (list.isEmpty() || suppressSuggest) rvSuggest.visibility = View.GONE
                    else {
                        suggestAdapter.submit(list)
                        rvSuggest.visibility = View.VISIBLE
                    }
                }
            }
        })

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                suggestJob?.cancel()
                rvSuggest.visibility = View.GONE
                hideKeyboard()
                performSearch(etQuery.text.toString().trim())
                true
            } else false
        }

        findViewById<View>(R.id.btnSearch).setOnClickListener {
            suggestJob?.cancel()
            rvSuggest.visibility = View.GONE
            hideKeyboard()
            performSearch(etQuery.text.toString().trim())
        }

        // ★ 필터 칩
        chipSort.setOnClickListener { showSortDialog() }
        chipDate.setOnClickListener { showDateDialog() }
        chipDuration.setOnClickListener { showDurationDialog() }

        intent.getStringExtra("QUERY")?.takeIf { it.isNotEmpty() }?.let {
            suppressSuggest = true
            etQuery.setText(it)
            etQuery.setSelection(it.length)
            suppressSuggest = false
            hideKeyboard()
            performSearch(it)
        }
    }

    private fun updateChips() {
        chipSort.text = "${filter.sort.label} ▾"
        chipDate.text = if (filter.uploadDate == SearchFilter.UploadDate.ALL) "업로드 날짜 ▾"
                        else "${filter.uploadDate.label} ▾"
        chipDuration.text = if (filter.duration == SearchFilter.Duration.ALL) "길이 ▾"
                            else "${filter.duration.label} ▾"
    }

    private fun showSortDialog() {
        val items = SearchFilter.SortBy.values().map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("정렬")
            .setItems(items) { _, i ->
                filter = filter.copy(sort = SearchFilter.SortBy.values()[i])
                updateChips()
                if (lastQuery.isNotEmpty()) performSearch(lastQuery)
            }
            .show()
    }

    private fun showDateDialog() {
        val items = SearchFilter.UploadDate.values().map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("업로드 날짜")
            .setItems(items) { _, i ->
                filter = filter.copy(uploadDate = SearchFilter.UploadDate.values()[i])
                updateChips()
                if (lastQuery.isNotEmpty()) performSearch(lastQuery)
            }
            .show()
    }

    private fun showDurationDialog() {
        val items = SearchFilter.Duration.values().map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("길이")
            .setItems(items) { _, i ->
                filter = filter.copy(duration = SearchFilter.Duration.values()[i])
                updateChips()
                if (lastQuery.isNotEmpty()) performSearch(lastQuery)
            }
            .show()
    }

    private fun loadMore() {
        val token = nextContinuation ?: return
        loadingMore = true
        lifecycleScope.launch {
            val page = YouTubeSearch.searchMore(token)
            nextContinuation = page.continuation
            adapter.append(page.videos)
            loadingMore = false
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etQuery.windowToken, 0)
        etQuery.clearFocus()
    }

    private fun performSearch(q: String) {
        if (q.isEmpty()) {
            Toast.makeText(this, "검색어를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        lastQuery = q
        RecentSearches.add(this, q)

        scrollFilters.visibility = View.VISIBLE
        updateChips()

        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "검색 중..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val page = YouTubeSearch.searchPage(q, filter)
            val elapsed = System.currentTimeMillis() - start

            progress.visibility = View.GONE
            nextContinuation = page.continuation
            tvStatus.text = "${page.videos.size}개 · ${elapsed}ms"
            adapter.submit(page.videos)
        }
    }
}
