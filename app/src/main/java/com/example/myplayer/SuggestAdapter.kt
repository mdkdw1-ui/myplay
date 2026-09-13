package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<SuggestAdapter.VH>() {

    private val items = mutableListOf<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val text = items[position]
        holder.tv.text = "🔍  $text"
        holder.tv.setTextColor(0xFFF5F5F7.toInt())
        holder.tv.textSize = 15f
        holder.tv.setPadding(48, 32, 48, 32)
        holder.itemView.setBackgroundColor(0xFF141416.toInt())
        holder.itemView.setOnClickListener { onClick(text) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(android.R.id.text1)
    }
}
