package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val tvEmpty = findViewById<View>(R.id.tvEmpty)

        val adapter = HistoryAdapter(
            onClick = { item -> playVideo(item) },
            onRelatedClick = { item -> showRelated(item) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            HistoryDatabase.get(applicationContext).historyDao().getAll().collect { list ->
                adapter.submit(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
        }
        startActivity(intent)
    }
}
