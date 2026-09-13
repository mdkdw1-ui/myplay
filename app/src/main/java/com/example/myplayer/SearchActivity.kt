package com.example.myplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

    private var nextContinuation: String? = null
    private var loadingMore = false
    private var suggestJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        etQuery = findViewById(R.id.etQuery)
        rvSuggest = findViewById(R.id.rvSuggest)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        // 결과 어댑터
        adapter = SearchAdapter { item ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
                putExtra("VIDEO_CHANNEL", item.channel)
                putExtra("VIDEO_THUMB", item.thumbnail)
            }
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // 무한 스크롤
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!loadingMore && nextContinuation != null && lastVisible >= total - 3) {
                    loadMore()
                }
            }
        })

        // 자동완성 어댑터
        suggestAdapter = SuggestAdapter { picked ->
            etQuery.setText(picked)
            etQuery.setSelection(picked.length)
            rvSuggest.visibility = View.GONE
            hideKeyboard()
            performSearch(picked)
        }
        rvSuggest.layoutManager = LinearLayoutManager(this)
        rvSuggest.adapter = suggestAdapter

        // 텍스트 입력 → 자동완성
        etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q.length < 1) {
                    rvSuggest.visibility = View.GONE
                    suggestAdapter.clear()
                    return
                }
                suggestJob?.cancel()
                suggestJob = lifecycleScope.launch {
                    delay(220)  // 디바운스
                    val list = YouTubeSuggest.suggest(q)
                    if (list.isEmpty()) {
                        rvSuggest.visibility = View.GONE
                    } else {
                        suggestAdapter.submit(list)
                        rvSuggest.visibility = View.VISIBLE
                    }
                }
            }
        })

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                rvSuggest.visibility = View.GONE
                hideKeyboard()
                performSearch(etQuery.text.toString().trim())
                true
            } else false
        }

        findViewById<View>(R.id.btnSearch).setOnClickListener {
            rvSuggest.visibility = View.GONE
            hideKeyboard()
            performSearch(etQuery.text.toString().trim())
        }

        intent.getStringExtra("QUERY")?.takeIf { it.isNotEmpty() }?.let {
            etQuery.setText(it)
            etQuery.setSelection(it.length)
            hideKeyboard()
            performSearch(it)
        }
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

        RecentSearches.add(this, q)

        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "검색 중..."

        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val page = YouTubeSearch.searchPage(q)
            val elapsed = System.currentTimeMillis() - start

            progress.visibility = View.GONE
            nextContinuation = page.continuation
            tvStatus.text = "${page.videos.size}개 · ${elapsed}ms"
            adapter.submit(page.videos)
        }
    }
}
