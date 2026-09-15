package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ChannelAdapter(
    private val onClick: (ChannelItem) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<ChannelItem>()

    fun submit(list: List<ChannelItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.subs.text = item.subscribers

        if (item.thumbnail.isNotEmpty()) {
            holder.avatar.visibility = View.VISIBLE
            holder.initial.visibility = View.GONE
            Glide.with(holder.avatar).load(item.thumbnail).circleCrop().into(holder.avatar)
        } else {
            // 아바타 없음 → 이니셜 표시
            holder.avatar.visibility = View.GONE
            holder.initial.visibility = View.VISIBLE
            holder.initial.text = item.name.firstOrNull()?.toString() ?: "?"
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val initial: TextView = v.findViewById(R.id.initial)
        val name: TextView = v.findViewById(R.id.name)
        val subs: TextView = v.findViewById(R.id.subs)
    }
}
