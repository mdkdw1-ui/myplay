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
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = mutableListOf<VideoItem>()

    // ★ 스크롤 상태 추적
    private var isScrolling = false
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            isScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
        }
    }

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
        } else {
            holder.meta.visibility = View.GONE
        }

        Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb)

        // 클릭 리스너는 onTouch에서 직접 처리
        holder.itemView.setOnClickListener(null)

        val host = holder.itemView as ViewGroup
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var previewStarted = false
        var menuTriggered = false
        var canceled = false

        val previewRunnable = Runnable {
            if (!isScrolling && !canceled) {
                previewStarted = true
                PreviewPlayer.start(host.context, host, item.videoId, item.thumbnail)
            }
        }
        val menuRunnable = Runnable {
            if (!isScrolling && !canceled && previewStarted) {
                menuTriggered = true
                PreviewPlayer.stop()
                showOptions(host, item)
            }
        }

        host.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downTime = System.currentTimeMillis()
                    previewStarted = false
                    menuTriggered = false
                    canceled = isScrolling  // ★ 이미 스크롤 중이면 즉시 취소
                    host.postDelayed(previewRunnable, 500)
                    host.postDelayed(menuRunnable, 1500)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    // ★ 어떤 방향이든 20px 이상 이동 → 취소
                    val dx = abs(event.rawX - downX)
                    val dy = abs(event.rawY - downY)
                    if (dx > 20 || dy > 20) {
                        if (!canceled) {
                            canceled = true
                            host.removeCallbacks(previewRunnable)
                            host.removeCallbacks(menuRunnable)
                            PreviewPlayer.stop()
                        }
                    }
                    // ★ MOVE가 여러 번 오면 스크롤 확정
                    if (dx > 5 || dy > 5) {
                        canceled = true
                    }
                    false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.removeCallbacks(previewRunnable)
                    host.removeCallbacks(menuRunnable)

                    val elapsed = System.currentTimeMillis() - downTime
                    val wasPreview = previewStarted
                    val wasMenu = menuTriggered
                    val wasCanceled = canceled
                    val wasScrolling = isScrolling

                    previewStarted = false
                    menuTriggered = false
                    canceled = false

                    when {
                        wasMenu -> true
                        wasPreview -> {
                            host.postDelayed({ PreviewPlayer.stop() }, 6000)
                            true
                        }
                        wasCanceled || wasScrolling -> {
                            // 스크롤이었음 → 아무 동작 X
                            true
                        }
                        // ★ 짧은 탭도 최소 150ms 이상 유지된 경우만 재생
                        elapsed < 150 -> {
                            // 관성 스크롤의 짧은 DOWN-UP → 무시
                            true
                        }
                        else -> {
                            PreviewPlayer.stop()
                            onClick(item)
                            true
                        }
                    }
                }

                else -> false
            }
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
