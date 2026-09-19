package com.example.myplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private val onClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.VH>() {

    private val items = mutableListOf<LyricLine>()
    private var currentIndex = -1

    fun submit(list: List<LyricLine>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setCurrentIndex(idx: Int): Int {
        val old = currentIndex
        currentIndex = idx
        if (old >= 0 && old < items.size) notifyItemChanged(old)
        if (idx >= 0 && idx < items.size) notifyItemChanged(idx)
        return idx
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.text.text = item.text
        val isCurrent = position == currentIndex
        if (isCurrent) {
            holder.text.setTextColor(Color.parseColor("#FF2D55"))
            holder.text.textSize = 20f
            holder.text.setTypeface(null, android.graphics.Typeface.BOLD)
            holder.text.alpha = 1f
        } else {
            holder.text.setTextColor(Color.parseColor("#F5F5F7"))
            holder.text.textSize = 16f
            holder.text.setTypeface(null, android.graphics.Typeface.NORMAL)
            holder.text.alpha = 0.6f
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvLyric)
    }
}
