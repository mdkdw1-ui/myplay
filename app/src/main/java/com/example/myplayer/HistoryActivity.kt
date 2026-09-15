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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val tvEmpty = findViewById<View>(R.id.tvEmpty)
        val tvClear = findViewById<TextView>(R.id.tvClearHistory)
        val etSearch = findViewById<EditText>(R.id.etSearch)

        val adapter = HistoryAdapter(
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

        // 검색어 흐름
        var currentQuery = ""
        var collectJob: Job? = null

        fun reload(q: String) {
            collectJob?.cancel()
            collectJob = lifecycleScope.launch {
                val dao = HistoryDatabase.get(applicationContext).historyDao()
                val flow = if (q.isBlank()) dao.getAll() else dao.search(q)
                flow.collect { list ->
                    adapter.submit(list)
                    tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    tvClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q != currentQuery) {
                    currentQuery = q
                    reload(q)
                }
            }
        })

        reload("")
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
