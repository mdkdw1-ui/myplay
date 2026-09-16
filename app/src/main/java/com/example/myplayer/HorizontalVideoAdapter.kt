package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class HomeVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String
)

class HorizontalVideoAdapter(
    private val onClick: (HomeVideo) -> Unit
) : RecyclerView.Adapter<HorizontalVideoAdapter.VH>() {

    private val items = mutableListOf<HomeVideo>()

    fun submit(list: List<HomeVideo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_horizontal, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // ★ 카드 크기 프리셋
        val pref = holder.itemView.context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val sizeKey = pref.getString("card_size", "medium") ?: "medium"
        val (w, h) = when (sizeKey) {
            "small" -> Pair(140, 80)
            "large" -> Pair(220, 124)
            else -> Pair(170, 96)
        }
        val density = holder.itemView.context.resources.displayMetrics.density
        holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
            width = (w * density).toInt()
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        holder.thumb.layoutParams = holder.thumb.layoutParams.apply {
            height = (h * density).toInt()
        }
        holder.title.text = item.title
        holder.channel.text = item.channel
        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
    }
}
