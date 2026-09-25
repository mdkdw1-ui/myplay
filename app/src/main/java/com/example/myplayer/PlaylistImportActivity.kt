package com.example.myplayer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PlaylistImportActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var progress: ProgressBar
    private var importing = false

    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            tvStatus.text = "파일 선택 취소"
            return@registerForActivityResult
        }
        startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_import)

        tvStatus = findViewById(R.id.tvStatus)
        tvProgress = findViewById(R.id.tvProgress)
        tvCurrent = findViewById(R.id.tvCurrent)
        progress = findViewById(R.id.progress)

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            if (!importing) finish()
        }
        findViewById<View>(R.id.btnPickFile).setOnClickListener {
            openDoc.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        tvStatus.text = "JSON 파일을 선택하세요"
    }

    private fun startImport(uri: Uri) {
        importing = true
        progress.visibility = View.VISIBLE
        progress.max = 100

        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) { null }
            }
            if (json.isNullOrBlank()) {
                tvStatus.text = "파일 읽기 실패"
                importing = false
                return@launch
            }

            // parse
            val name: String
            val songs: List<Pair<String, String>>
            try {
                val trimmed = json.trim()
                if (trimmed.startsWith("[")) {
                    name = "가져온 플레이리스트"
                    val arr = JSONArray(trimmed)
                    songs = parseSongs(arr)
                } else {
                    val obj = JSONObject(trimmed)
                    name = obj.optString("name", "가져온 플레이리스트")
                    val arr = obj.optJSONArray("songs")
                        ?: obj.optJSONArray("items")
                        ?: JSONArray()
                    songs = parseSongs(arr)
                }
            } catch (e: Exception) {
                tvStatus.text = "JSON 파싱 실패: ${e.message}"
                importing = false
                return@launch
            }

            if (songs.isEmpty()) {
                tvStatus.text = "곡이 없습니다"
                importing = false
                return@launch
            }

            tvStatus.text = "가져오기 시작: $name (${songs.size}곡)"

            val dao = HistoryDatabase.get(applicationContext).savedPlaylistDao()
            val pid = dao.insertPlaylist(
                SavedPlaylistEntity(
                    name = name,
                    createdAt = System.currentTimeMillis(),
                    itemCount = 0
                )
            )

            var found = 0
            for ((i, song) in songs.withIndex()) {
                val (artist, title) = song
                val query = "$artist $title"
                val pct = ((i + 1) * 100) / songs.size
                runOnUiThread {
                    progress.progress = pct
                    tvProgress.text = "${i + 1} / ${songs.size}"
                    tvCurrent.text = "🔍 $query"
                }

                try {
                    val results = YouTubeSearch.search(query)
                    val first = results.firstOrNull()
                    if (first != null) {
                        dao.insertItem(
                            SavedPlaylistItemEntity(
                                playlistId = pid,
                                videoId = first.videoId,
                                title = "$artist - $title",
                                channel = first.channel.ifBlank { artist },
                                thumbnail = first.thumbnail,
                                position = i
                            )
                        )
                        found++
                    }
                } catch (e: Exception) {
                    // skip
                }

                // rate limit
                delay(400)
            }

            dao.updateCount(pid, found)

            runOnUiThread {
                progress.visibility = View.GONE
                tvStatus.text = "✅ 완료: $found / ${songs.size}곡 저장"
                tvCurrent.text = "\"$name\" 플레이리스트로 저장됨"
                Toast.makeText(this@PlaylistImportActivity,
                    "$found 곡 저장됨", Toast.LENGTH_LONG).show()
                importing = false
            }
        }
    }

    private fun parseSongs(arr: JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val artist = o.optString("artist", "").trim()
            val title = o.optString("title", "").trim()
            if (title.isNotBlank()) {
                out.add(artist to title)
            }
        }
        return out
    }
}
