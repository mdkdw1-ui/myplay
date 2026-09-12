package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var adapter: SearchAdapter
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        progress = findViewById(R.id.progress)
        val etQuery = findViewById<EditText>(R.id.etQuery)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = SearchAdapter { item ->
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
            }
            startActivity(intent)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(etQuery.text.toString().trim())
                true
            } else false
        }

        findViewById<View>(R.id.btnSearch).setOnClickListener {
            performSearch(etQuery.text.toString().trim())
        }
    }

    private fun performSearch(q: String) {
        if (q.isEmpty()) {
            Toast.makeText(this, "검색어를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = YouTubeSearch.search(q)
            progress.visibility = View.GONE
            adapter.submit(results)
            if (results.isEmpty()) {
                Toast.makeText(this@SearchActivity, "결과 없음", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
