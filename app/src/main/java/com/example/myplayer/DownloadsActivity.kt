package com.example.myplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val emptyBox = findViewById<View>(R.id.emptyBox)

        val adapter = DownloadsAdapter(
            onClick = { item -> playLocal(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            HistoryDatabase.get(applicationContext).downloadDao().getAll().collect { list ->
                adapter.submit(list)
                emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun playLocal(item: DownloadEntity) {
        val f = File(item.filePath)
        if (!f.exists()) {
            android.widget.Toast.makeText(this, "파일이 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_URI", Uri.fromFile(f).toString())
            putExtra("VIDEO_TITLE", item.title)
            putExtra("VIDEO_CHANNEL", item.channel)
            putExtra("VIDEO_THUMB", item.thumbnail)
        }
        startActivity(intent)
    }

    private fun confirmDelete(item: DownloadEntity) {
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("\"${item.title}\" 을(를) 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val f = File(item.filePath)
                            if (f.exists()) f.delete()
                            HistoryDatabase.get(applicationContext).downloadDao().delete(item.videoId)
                        } catch (e: Exception) { }
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
