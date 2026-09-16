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
            onDelete = { item -> confirmDelete(item) },
            onExport = { item -> exportToDownload(item) }
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


    private fun exportToDownload(item: DownloadEntity) {
        try {
            val src = java.io.File(item.filePath)
            if (!src.exists()) {
                android.widget.Toast.makeText(this, "파일 없음", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // ★ MediaStore.Downloads 컬렉션 (Download 폴더 전용)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/MyPlayer")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                val uri = contentResolver.insert(collection, values)
                    ?: throw Exception("insert null")

                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw Exception("openOutputStream null")

                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)

                android.widget.Toast.makeText(
                    this,
                    "내보냄: Download/MyPlayer/${src.name}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ), "MyPlayer"
                )
                dir.mkdirs()
                val dst = java.io.File(dir, src.name)
                src.copyTo(dst, overwrite = true)
                android.widget.Toast.makeText(
                    this,
                    "내보냄: ${dst.absolutePath}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "실패: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
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
