package com.example.myplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class LocalMediaActivity : AppCompatActivity() {

    private enum class TabMode { FOLDER, ARTIST, ALL }
    private var currentTab = TabMode.FOLDER
    private var allMedia: List<LocalMedia> = emptyList()
    private var groupedMode = true  // 그룹별 vs 전체

    private lateinit var adapter: MediaAdapter
    private lateinit var groupAdapter: GroupAdapter
    private var currentGroup: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            doScan()
        } else {
            Toast.makeText(this, "오디오 접근 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun ensurePermission(): Boolean {
        val perms = requiredPerms()
        val ok = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!ok) permLauncher.launch(perms)
        return ok
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_media)

        val tabs = findViewById<TabLayout>(R.id.tabs)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val progress = findViewById<View>(R.id.progress)

        tabs.addTab(tabs.newTab().setText("📁 폴더"))
        tabs.addTab(tabs.newTab().setText("🎤 아티스트"))
        tabs.addTab(tabs.newTab().setText("🎵 전체"))

        adapter = MediaAdapter(
            onClick = { media -> playMedia(media) },
            onDelete = { media -> confirmDelete(media) }
        )
        groupAdapter = GroupAdapter { name ->
            currentGroup = name
            groupedMode = false
            adapter.submit(
                when (currentTab) {
                    TabMode.FOLDER -> allMedia.filter { it.folder == name }
                    TabMode.ARTIST -> allMedia.filter { it.artist == name }
                    else -> allMedia
                }
            )
            recycler.adapter = adapter
        }
        recycler.layoutManager = LinearLayoutManager(this)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = when (tab?.position) {
                    0 -> TabMode.FOLDER
                    1 -> TabMode.ARTIST
                    else -> TabMode.ALL
                }
                currentGroup = null
                groupedMode = (currentTab != TabMode.ALL)
                render(recycler)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        if (ensurePermission()) {
            doScan()
        } else {
            progress.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // 권한 부여 후 돌아오거나, 다른 앱에서 파일 추가한 경우 재스캔
        if (allMedia.isEmpty() && ensurePermission()) {
            doScan()
        }
    }

    private fun doScan() {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val progress = findViewById<View>(R.id.progress)
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            allMedia = LocalMediaScanner.scan(this@LocalMediaActivity)
            progress.visibility = View.GONE
            if (allMedia.isEmpty()) {
                Toast.makeText(this@LocalMediaActivity,
                    "로컬 오디오 없음 · Music 폴더에 파일을 넣어주세요",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@LocalMediaActivity, "${allMedia.size}곡 발견", Toast.LENGTH_SHORT).show()
            }
            render(recycler)
        }
    }

    override fun onBackPressed() {
        if (currentGroup != null) {
            currentGroup = null
            groupedMode = true
            val recycler = findViewById<RecyclerView>(R.id.recycler)
            render(recycler)
        } else {
            super.onBackPressed()
        }
    }

    private fun render(recycler: RecyclerView) {
        if (groupedMode) {
            val groups = when (currentTab) {
                TabMode.FOLDER -> allMedia.groupBy { it.folder }
                TabMode.ARTIST -> allMedia.groupBy { it.artist }
                else -> emptyMap()
            }
            if (currentTab == TabMode.ALL) {
                adapter.submit(allMedia)
                recycler.adapter = adapter
            } else {
                groupAdapter.submit(
                    groups.map { (k, v) -> k to v.size }
                        .sortedBy { it.first.lowercase() }
                )
                recycler.adapter = groupAdapter
            }
        } else {
            recycler.adapter = adapter
        }
    }

    private fun confirmDelete(media: LocalMedia) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("\"${media.title}\" 을(를) 기기에서 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    val ok = LocalMediaScanner.delete(this@LocalMediaActivity, media)
                    if (ok) {
                        allMedia = allMedia.filter { it.id != media.id }
                        Toast.makeText(this@LocalMediaActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                        render(findViewById(R.id.recycler))
                    } else {
                        Toast.makeText(this@LocalMediaActivity,
                            "삭제 실패 (권한 또는 시스템 제약)",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun playMedia(media: LocalMedia) {
        val list = if (currentGroup == null) allMedia else {
            when (currentTab) {
                TabMode.FOLDER -> allMedia.filter { it.folder == currentGroup }
                TabMode.ARTIST -> allMedia.filter { it.artist == currentGroup }
                else -> allMedia
            }
        }
        val idx = list.indexOfFirst { it.id == media.id }
        if (idx < 0) return

        QueueManager.clear(this)
        for (m in list) {
            QueueManager.addLocal(this, m)
        }
        QueueManager.setCurrent(this, "local:${media.id}")

        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("LOCAL_URI", media.uri.toString())
            putExtra("VIDEO_TITLE", media.title)
            putExtra("VIDEO_CHANNEL", media.artist)
            putExtra("FROM_PLAYLIST", true)
        })
    }

    class MediaAdapter(
        val onClick: (LocalMedia) -> Unit,
        val onDelete: (LocalMedia) -> Unit
    ) : RecyclerView.Adapter<MediaAdapter.VH>() {
        private val items = mutableListOf<LocalMedia>()
        fun submit(list: List<LocalMedia>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.t1.text = item.title
            holder.t1.setTextColor(0xFFF5F5F7.toInt())
            holder.t1.textSize = 15f
            holder.t2.text = "${item.artist} · ${fmt(item.durationMs)}"
            holder.t2.setTextColor(0xFF8E8E93.toInt())
            holder.t2.textSize = 12f
            holder.itemView.setPadding(24, 28, 24, 28)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                    .setTitle(item.title)
                    .setItems(arrayOf("🗑 기기에서 삭제", "❌ 취소")) { _, w ->
                        if (w == 0) onDelete(item)
                    }
                    .show()
                true
            }
        }
        override fun getItemCount() = items.size
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val t1: TextView = v.findViewById(android.R.id.text1)
            val t2: TextView = v.findViewById(android.R.id.text2)
        }
        private fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }

    class GroupAdapter(val onClick: (String) -> Unit) : RecyclerView.Adapter<GroupAdapter.VH>() {
        private val items = mutableListOf<Pair<String, Int>>()
        fun submit(list: List<Pair<String, Int>>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, count) = items[position]
            holder.t1.text = "📂 $name"
            holder.t1.setTextColor(0xFFF5F5F7.toInt())
            holder.t1.textSize = 15f
            holder.t2.text = "${count}곡"
            holder.t2.setTextColor(0xFF8E8E93.toInt())
            holder.t2.textSize = 12f
            holder.itemView.setPadding(24, 32, 24, 32)
            holder.itemView.setOnClickListener { onClick(name) }
        }
        override fun getItemCount() = items.size
        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val t1: TextView = v.findViewById(android.R.id.text1)
            val t2: TextView = v.findViewById(android.R.id.text2)
        }
    }
}
