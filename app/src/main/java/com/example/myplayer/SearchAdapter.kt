package com.example.myplayer

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class SearchAdapter(
    private val onClick: (VideoItem) -> Unit,
    private val onMultiSave: ((List<VideoItem>) -> Unit)? = null
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()
    private val selected = mutableSetOf<String>()
    private var selectMode = false

    private var isScrolling = false
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
        }
    }

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selectMode = false
        notifyDataSetChanged()
    }

    fun append(list: List<VideoItem>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun getItem(position: Int): VideoItem? = items.getOrNull(position)

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnScrollListener(scrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeOnScrollListener(scrollListener)
    }

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
        } else holder.meta.visibility = View.GONE

        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)

        // 선택 모드 UI
        if (selectMode) {
            val isSel = item.videoId in selected
            holder.itemView.alpha = if (isSel) 1f else 0.6f
            holder.thumb.setColorFilter(if (isSel) 0x6600FF00 else 0x00000000)
        } else {
            holder.itemView.alpha = 1f
            holder.thumb.clearColorFilter()
        }

        holder.itemView.setOnClickListener {
            if (selectMode) {
                if (item.videoId in selected) selected.remove(item.videoId)
                else selected.add(item.videoId)
                notifyItemChanged(position)
                if (selected.isEmpty()) {
                    selectMode = false
                    notifyDataSetChanged()
                }
            } else {
                onClick(item)
            }
        }

        // 롱프레스 → 선택 모드
        holder.itemView.setOnLongClickListener {
            if (!selectMode) {
                selectMode = true
                selected.add(item.videoId)
                notifyDataSetChanged()

                // 선택 개수 저장 Dialog
                android.app.AlertDialog.Builder(holder.itemView.context)
                    .setTitle("다중 선택")
                    .setMessage("영상들을 탭해서 선택하세요.\n완료를 누르면 저장합니다.")
                    .setPositiveButton("완료 (${selected.size})") { _, _ ->
                        val list = items.filter { it.videoId in selected }
                        onMultiSave?.invoke(list)
                        selectMode = false
                        selected.clear()
                        notifyDataSetChanged()
                    }
                    .setNeutralButton("더 선택") { _, _ ->
                        // 다이얼로그만 닫음, 선택 모드 유지
                    }
                    .setNegativeButton("취소") { _, _ ->
                        selectMode = false
                        selected.clear()
                        notifyDataSetChanged()
                    }
                    .show()
            }
            true
        }
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
