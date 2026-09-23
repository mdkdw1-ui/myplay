package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class PlaylistDetailActivity : AppCompatActivity() {

    private var playlistId: Long = 0
    private var playlistName: String = ""
    private lateinit var adapter: ItemAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)

        playlistId = intent.getLongExtra("PLAYLIST_ID", 0L)
        playlistName = intent.getStringExtra("PLAYLIST_NAME") ?: "플레이리스트"

        findViewById<TextView>(R.id.tvTitle).text = playlistName
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = ItemAdapter(
            onClick = { item -> playFrom(item) },
            onDelete = { item -> deleteItem(item) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<View>(R.id.btnPlayAll).setOnClickListener {
            val items = adapter.currentItems()
            if (items.isEmpty()) {
                Toast.makeText(this, "빈 플레이리스트", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playFrom(items.first())
        }

        findViewById<View>(R.id.btnDelete).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("플레이리스트 삭제")
                .setMessage("\"$playlistName\" 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    lifecycleScope.launch {
                        val dao = HistoryDatabase.get(applicationContext).savedPlaylistDao()
                        dao.deleteItems(playlistId)
                        dao.deletePlaylist(playlistId)
                        Toast.makeText(this@PlaylistDetailActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }

        loadItems()
    }

    private fun loadItems() {
        lifecycleScope.launch {
            val dao = HistoryDatabase.get(applicationContext).savedPlaylistDao()
            val items = dao.getItems(playlistId)
            adapter.submit(items)
        }
    }

    private fun playFrom(startItem: SavedPlaylistItemEntity) {
        val items = adapter.currentItems()
        val idx = items.indexOfFirst { it.id == startItem.id }
        if (idx < 0) return

        // Feature 8: 전체 플레이리스트를 큐에 넣음
        QueueManager.clear(this)
        for (v in items) {
            QueueManager.add(this, HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
        }
        QueueManager.setCurrent(this, startItem.videoId)
        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", startItem.videoId)
            putExtra("VIDEO_TITLE", startItem.title)
            putExtra("VIDEO_CHANNEL", startItem.channel)
            putExtra("VIDEO_THUMB", startItem.thumbnail)
            putExtra("FROM_PLAYLIST", true)
        })
    }

    private fun deleteItem(item: SavedPlaylistItemEntity) {
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("\"${item.title}\" 을(를) 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    val db = HistoryDatabase.get(applicationContext)
                    db.savedPlaylistDao().deleteItems(playlistId)
                    // 남은 항목 재삽입 (position 재조정)
                    val items = adapter.currentItems().filter { it.id != item.id }
                    items.forEachIndexed { i, it ->
                        db.savedPlaylistDao().insertItem(it.copy(position = i))
                    }
                    db.savedPlaylistDao().updateCount(playlistId, items.size)
                    loadItems()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    class ItemAdapter(
        val onClick: (SavedPlaylistItemEntity) -> Unit,
        val onDelete: (SavedPlaylistItemEntity) -> Unit
    ) : RecyclerView.Adapter<ItemAdapter.VH>() {

        private val items = mutableListOf<SavedPlaylistItemEntity>()

        fun submit(list: List<SavedPlaylistItemEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun currentItems(): List<SavedPlaylistItemEntity> = items.toList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_local_media, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvSub.text = item.channel
            holder.tvDuration.text = ""

            if (item.thumbnail.isNotBlank()) {
                com.bumptech.glide.Glide.with(holder.albumArt)
                    .load(item.thumbnail)
                    .placeholder(android.R.drawable.ic_media_play)
                    .error(android.R.drawable.ic_media_play)
                    .centerCrop()
                    .into(holder.albumArt)
            } else {
                holder.albumArt.setImageResource(android.R.drawable.ic_media_play)
            }

            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                    .setTitle(item.title)
                    .setItems(arrayOf("🗑 이 곡 삭제")) { _, w ->
                        if (w == 0) onDelete(item)
                    }
                    .show()
                true
            }
        }

        override fun getItemCount() = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val albumArt: android.widget.ImageView = v.findViewById(R.id.albumArt)
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvSub: TextView = v.findViewById(R.id.tvSub)
            val tvDuration: TextView = v.findViewById(R.id.tvDuration)
        }
    }
}
