package com.example.myplayer

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TranscriptAdapter(
    private val onClick: (TranscriptLine) -> Unit
) : RecyclerView.Adapter<TranscriptAdapter.VH>() {

    private var items: List<TranscriptLine> = emptyList()
    private var query: String = ""
    private var currentPosMs: Long = 0L

    fun submit(list: List<TranscriptLine>, highlightQuery: String = "") {
        items = list
        query = highlightQuery
        notifyDataSetChanged()
    }

    fun setCurrentPosition(posMs: Long) {
        currentPosMs = posMs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transcript, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTime.text = formatMs(item.startMs)

        // 검색어 하이라이트
        if (query.isBlank()) {
            holder.tvText.text = item.text
            holder.tvText.setTextColor(Color.parseColor("#F5F5F7"))
        } else {
            val spannable = SpannableString(item.text)
            val lower = item.text.lowercase()
            val q = query.lowercase()
            var idx = lower.indexOf(q)
            while (idx >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(Color.parseColor("#FF2D55")),
                    idx, idx + q.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    idx, idx + q.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                idx = lower.indexOf(q, idx + q.length)
            }
            holder.tvText.text = spannable
        }

        // 현재 재생 위치 강조
        val isCurrent = item.startMs <= currentPosMs && currentPosMs < item.endMs
        holder.itemView.setBackgroundColor(
            if (isCurrent) Color.parseColor("#33FF2D55") else Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    private fun formatMs(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
        val tvText: TextView = v.findViewById(R.id.tvText)
    }
}
