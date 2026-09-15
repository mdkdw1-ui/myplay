package com.example.myplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        val percent = item.progressPercent
        if (percent in 1..94) {
            holder.progressTrack.visibility = View.VISIBLE
            holder.progressTrack.post {
                val w = (holder.progressTrack.width * percent / 100f).toInt()
                val lp = holder.progressFill.layoutParams
                lp.width = w
                holder.progressFill.layoutParams = lp
            }
        } else {
            holder.progressTrack.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.btnRelated.setOnClickListener { onRelatedClick(item) }

        // ★ 롱프레스 → 옵션 메뉴
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            val options = arrayOf("🗑 기록에서 삭제", "⭐ 북마크 저장", "📤 공유")
            AlertDialog.Builder(ctx)
                .setTitle(item.title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            GlobalScope.launch(Dispatchers.IO) {
                                try {
                                    HistoryDatabase.get(ctx).historyDao()
                                        .deleteById(item.videoId)
                                } catch (e: Exception) { }
                            }
                            android.widget.Toast.makeText(
                                ctx, "삭제됨", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        1 -> {
                            GlobalScope.launch(Dispatchers.IO) {
                                try {
                                    HistoryDatabase.get(ctx).bookmarkDao().insert(
                                        BookmarkEntity(
                                            videoId = item.videoId, title = item.title,
                                            channel = item.channel, thumbnail = item.thumbnail,
                                            savedAt = System.currentTimeMillis()
                                        )
                                    )
                                } catch (e: Exception) { }
                            }
                            android.widget.Toast.makeText(ctx, "북마크 저장", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            val url = "https://www.youtube.com/watch?v=${item.videoId}"
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "${item.title}\n$url")
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, "공유"))
                        }
                    }
                }
                .show()
            true
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
        val progressFill: View = v.findViewById(R.id.progressFill)
    }
}
