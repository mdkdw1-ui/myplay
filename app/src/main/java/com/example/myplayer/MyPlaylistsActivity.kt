package com.example.myplayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class MyPlaylistsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_playlists)

        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val emptyBox = findViewById<View>(R.id.emptyBox)

        val adapter = Adapter(
            onClick = { pl ->
                startActivity(Intent(this, PlaylistDetailActivity::class.java).apply {
                    putExtra("PLAYLIST_ID", pl.id)
                    putExtra("PLAYLIST_NAME", pl.name)
                })
            },
            onDelete = { pl -> confirmDelete(pl) }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            HistoryDatabase.get(applicationContext).savedPlaylistDao()
                .getAllPlaylists().collect { list ->
                    adapter.submit(list)
                    emptyBox.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
        }
    }

    private fun confirmDelete(pl: SavedPlaylistEntity) {
        AlertDialog.Builder(this)
            .setTitle(pl.name)
            .setMessage("삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    val dao = HistoryDatabase.get(applicationContext).savedPlaylistDao()
                    dao.deleteItems(pl.id)
                    dao.deletePlaylist(pl.id)
                    Toast.makeText(this@MyPlaylistsActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    class Adapter(
        val onClick: (SavedPlaylistEntity) -> Unit,
        val onDelete: (SavedPlaylistEntity) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        private val items = mutableListOf<SavedPlaylistEntity>()

        fun submit(list: List<SavedPlaylistEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tv1.text = "📂 ${item.name}"
            holder.tv1.setTextColor(0xFFF5F5F7.toInt())
            holder.tv1.textSize = 15f
            holder.tv2.text = "${item.itemCount}곡"
            holder.tv2.setTextColor(0xFF8E8E93.toInt())
            holder.tv2.textSize = 12f

            holder.itemView.setPadding(24, 32, 24, 32)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle(item.name)
                    .setItems(arrayOf("🗑 삭제")) { _, _ -> onDelete(item) }
                    .show()
                true
            }
        }

        override fun getItemCount() = items.size

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv1: TextView = v.findViewById(android.R.id.text1)
            val tv2: TextView = v.findViewById(android.R.id.text2)
        }
    }
}
