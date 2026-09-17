package com.example.myplayer

import android.view.GestureDetector
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

        // ★ GestureDetector: 탭 / 롱프레스 완벽 분리
        val ctx = holder.itemView.context
        var previewStarted = false
        var menuTriggered = false

        val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {

            // ★ 탭 확정 (더블탭 아님, 스크롤 아님)
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                PreviewPlayer.stop()
                onClick(item)
                return true
            }

            // ★ 롱프레스 시작 (0.5초)
            override fun onLongPress(e: MotionEvent) {
                super.onLongPress(e)
                if (!previewStarted) {
                    previewStarted = true
                    PreviewPlayer.start(ctx, holder.itemView as ViewGroup, item.videoId, item.thumbnail)
                }
            }

            // ★ 스크롤 다운 시 (onScroll) — 취소
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (previewStarted) {
                    PreviewPlayer.stop()
                    previewStarted = false
                }
                return false  // RecyclerView 스크롤에 양보
            }

            // ★ 다운 시점
            override fun onDown(e: MotionEvent): Boolean {
                previewStarted = false
                menuTriggered = false
                return true  // ★ true 반환해야 이후 이벤트 받음
            }

            // ★ 업 시점
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // onSingleTapConfirmed이 처리하므로 여기선 pass
                return false
            }
        })

        // ★ onTouchListener: GestureDetector + 손 떼면 미리보기 유지
        holder.itemView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL) {
                if (previewStarted) {
                    // 미리보기 중 손 뗌 → 6초 더 유지
                    holder.itemView.postDelayed({
                        PreviewPlayer.stop()
                        previewStarted = false
                    }, 6000)
                }
            }
            false  // ★ RecyclerView 스크롤 양보
        }
    }

    private fun showOptions(v: View, item: VideoItem) {
        val ctx = v.context
        val options = arrayOf("📋 대기열에 추가", "⭐ 북마크 저장", "📤 공유")
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val added = QueueManager.add(
                            ctx, HomeVideo(item.videoId, item.title, item.channel, item.thumbnail)
                        )
                        android.widget.Toast.makeText(
                            ctx, if (added) "대기열 추가" else "이미 있음",
                            android.widget.Toast.LENGTH_SHORT
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
                        android.widget.Toast.makeText(ctx, "북마크", android.widget.Toast.LENGTH_SHORT).show()
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
