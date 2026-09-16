package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class DownloadsAdapter(
    private val onClick: (DownloadEntity) -> Unit,
    private val onDelete: (DownloadEntity) -> Unit,
    private val onExport: (DownloadEntity) -> Unit = {}
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private val items = mutableListOf<DownloadEntity>()

    fun submit(list: List<DownloadEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.channel.text = item.channel
        val sizeMb = item.sizeBytes / 1024 / 1024
        holder.duration.text = "${sizeMb}MB"
        holder.progressTrack.visibility = View.GONE

        if (item.thumbnail.isNotBlank())
            Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)

        holder.btnRelated.text = "⋯"

        holder.itemView.setOnClickListener { onClick(item) }
        holder.btnRelated.setOnClickListener {
            val ctx = holder.itemView.context
            val options = arrayOf("📤 Download 폴더로 내보내기", "🗑 삭제")
            AlertDialog.Builder(ctx)
                .setTitle(item.title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> onExport(item)
                        1 -> onDelete(item)
                    }
                }
                .show()
        }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val duration: TextView = v.findViewById(R.id.duration)
        val btnRelated: TextView = v.findViewById(R.id.btnRelated)
        val progressTrack: View = v.findViewById(R.id.progressTrack)
    }
}
