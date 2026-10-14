package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * "나중에" 저장한 영상 목록
 * SharedPreferences("sub_notif_later")에서 읽음
 */
class LaterListActivity : AppCompatActivity() {

    data class LaterItem(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnail: String,
        val savedAt: Long
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)   // 재사용

        findViewById<TextView>(R.id.btnImportDownload)?.apply {
            text = "🗑 전체 삭제"
            setOnClickListener {
                getSharedPreferences("sub_notif_later", MODE_PRIVATE)
                    .edit().clear().apply()
                Toast.makeText(this@LaterListActivity, "전체 삭제됨", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val emptyBox = findViewById<View>(R.id.emptyBox)

        val items = loadLaterItems()

        if (items.isEmpty()) {
            emptyBox.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyBox.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = SearchAdapter { item ->
                // 재생
                startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", item.videoId)
                    putExtra("VIDEO_TITLE", item.title)
                    putExtra("VIDEO_CHANNEL", item.channel)
                    putExtra("VIDEO_THUMB", item.thumbnail)
                    putExtra("FROM_PLAYLIST", false)
                })
            }.also { adapter ->
                adapter.submit(items.map {
                    VideoItem(it.videoId, it.title, it.channel, it.thumbnail)
                })
            }
        }
    }

    private fun loadLaterItems(): List<LaterItem> {
        val prefs = getSharedPreferences("sub_notif_later", MODE_PRIVATE)
        val raw = prefs.getStringSet("items", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size < 5) return@mapNotNull null
            LaterItem(
                videoId = parts[0],
                title = parts[1],
                channel = parts[2],
                thumbnail = parts[3],
                savedAt = parts[4].toLongOrNull() ?: 0
            )
        }.sortedByDescending { it.savedAt }
    }
}
