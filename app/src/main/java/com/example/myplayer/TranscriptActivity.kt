package com.example.myplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class TranscriptActivity : AppCompatActivity() {

    private lateinit var adapter: TranscriptAdapter
    private var allLines: List<TranscriptLine> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript)

        val subUrl = intent.getStringExtra("SUBTITLE_URL") ?: ""
        val startPos = intent.getLongExtra("CURRENT_MS", 0L)
        val title = intent.getStringExtra("VIDEO_TITLE") ?: ""

        val etSearch = findViewById<EditText>(R.id.etSearch)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = TranscriptAdapter { line ->
            // 선택 → PlayerActivity에 결과 전달
            val result = Intent().apply {
                putExtra("SEEK_MS", line.startMs)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        adapter.setCurrentPosition(startPos)

        // 검색
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                applyFilter(q)
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                applyFilter(etSearch.text.toString().trim())
                true
            } else false
        }

        if (subUrl.isBlank()) {
            tvStatus.text = "이 영상엔 자막이 없습니다"
            progress.visibility = View.GONE
            return
        }

        progress.visibility = View.VISIBLE
        tvStatus.text = "자막 불러오는 중..."

        lifecycleScope.launch {
            val lines = YouTubeTranscript.fetchLines(subUrl)
            progress.visibility = View.GONE
            allLines = lines

            if (lines.isEmpty()) {
                tvStatus.text = "자막을 불러올 수 없습니다"
                return@launch
            }

            tvStatus.text = "${lines.size}줄"
            adapter.submit(lines)

            // 현재 재생 위치로 스크롤
            val idx = lines.indexOfLast { it.startMs <= startPos }
            if (idx > 0) {
                (recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 100)
            }
        }
    }

    private fun applyFilter(q: String) {
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        if (q.isBlank()) {
            adapter.submit(allLines)
            tvStatus.text = "${allLines.size}줄"
        } else {
            val lower = q.lowercase()
            val filtered = allLines.filter { it.text.lowercase().contains(lower) }
            adapter.submit(filtered, q)
            tvStatus.text = "\"$q\" · ${filtered.size}건"
        }
    }
}
