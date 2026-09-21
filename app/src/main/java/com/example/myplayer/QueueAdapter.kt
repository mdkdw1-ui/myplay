package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class QueueAdapter(
    private val onClick: (Int, HomeVideo) -> Unit,
    private val onRemove: (HomeVideo) -> Unit,
    private val onMoveTop: (HomeVideo) -> Unit
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val items = mutableListOf<HomeVideo>()

    private var currentId: String? = null

    fun submit(list: List<HomeVideo>, current: String? = null) {
        items.clear()
        items.addAll(list)
        currentId = current
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.idx.text = "${position + 1}"
        holder.title.text = item.title
        holder.channel.text = item.channel
        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)

        val isCurrent = item.videoId == currentId
        holder.title.setTypeface(
            null,
            if (isCurrent) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL
        )
        holder.itemView.setBackgroundColor(
            if (isCurrent) 0x2233B5E5 else android.graphics.Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener { onClick(position, item) }
        holder.btnRemove.setOnClickListener { onRemove(item) }
        holder.btnTop.setOnClickListener { onMoveTop(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val idx: TextView = v.findViewById(R.id.tvIdx)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val btnRemove: TextView = v.findViewById(R.id.btnRemove)
        val btnTop: TextView = v.findViewById(R.id.btnTop)
    }
}
