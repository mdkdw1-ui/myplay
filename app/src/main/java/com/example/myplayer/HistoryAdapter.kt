package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class HistoryAdapter(
    private val onClick: (HistoryEntity) -> Unit,
    private val onRelatedClick: (HistoryEntity) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<HistoryEntity>()

    fun submit(newItems: List<HistoryEntity>) {
        items.clear()
        items.addAll(newItems)
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
        holder.duration.text = ""
        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)
        holder.itemView.setOnClickListener { onClick(item) }
        holder.btnRelated.setOnClickListener { onRelatedClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val duration: TextView = v.findViewById(R.id.duration)
        val btnRelated: TextView = v.findViewById(R.id.btnRelated)
    }
}
