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

        // onClick은 onTouch에서 직접 처리 (중복 방지)
        holder.itemView.setOnClickListener(null)

        val host = holder.itemView as ViewGroup
        var downX = 0f
        var downY = 0f
        var previewStarted = false
        var menuTriggered = false

        // 0.5초 후 미리보기 시작
        val previewRunnable = Runnable {
            previewStarted = true
            PreviewPlayer.start(host.context, host, item.videoId, item.thumbnail)
        }

        // 1.5초 후 옵션 메뉴
        val menuRunnable = Runnable {
            menuTriggered = true
            PreviewPlayer.stop()
            showOptions(host, item)
        }

        host.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    previewStarted = false
                    menuTriggered = false
                    host.postDelayed(previewRunnable, 500)
                    host.postDelayed(menuRunnable, 1500)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - downX) > 20 ||
                        Math.abs(event.rawY - downY) > 20) {
                        host.removeCallbacks(previewRunnable)
                        host.removeCallbacks(menuRunnable)
                        PreviewPlayer.stop()
                        previewStarted = false
                        menuTriggered = false
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.removeCallbacks(previewRunnable)
                    host.removeCallbacks(menuRunnable)
                    val wasPreview = previewStarted
                    val wasMenu = menuTriggered
                    previewStarted = false
                    menuTriggered = false

                    when {
                        wasMenu -> {
                            // 메뉴는 이미 떴음
                            true
                        }
                        wasPreview -> {
                            // ★ 미리보기 유지 (손 떼도) → 3초 뒤 자동 종료
                            host.postDelayed({ PreviewPlayer.stop() }, 6000)
                            true
                        }
                        else -> {
                            // 일반 탭 (0.5초 이하) → 재생
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
                            ctx,
                            if (added) "대기열 추가" else "이미 있음",
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
