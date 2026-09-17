package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SearchAdapter(
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun getItem(position: Int): VideoItem? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.channel.text = item.channel
        holder.duration.text = item.duration

        val metaParts = mutableListOf<String>()
        if (item.viewCount.isNotBlank()) metaParts.add(item.viewCount)
        if (item.uploadDate.isNotBlank()) metaParts.add(item.uploadDate)
        if (metaParts.isNotEmpty()) {
            holder.meta.text = metaParts.joinToString(" · ")
            holder.meta.visibility = View.VISIBLE
        } else {
            holder.meta.visibility = View.GONE
        }

        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)
        // ★ 탭/롱프레스는 RecyclerView.OnItemTouchListener에서 처리
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnTouchListener(null)
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val duration: TextView = v.findViewById(R.id.duration)
        val meta: TextView = v.findViewById(R.id.meta)
    }
}
