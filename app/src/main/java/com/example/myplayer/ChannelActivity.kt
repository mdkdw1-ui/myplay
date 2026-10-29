package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChannelActivity : AppCompatActivity() {

    private fun cdiag(msg: String) {
        android.util.Log.d("ChannelDiag", msg)
        try {
            val f = java.io.File(filesDir, "channel_diag.log")
            if (f.exists() && f.length() > 200_000) f.writeText("")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
    }


    private lateinit var adapter: SearchAdapter
    private val loadedVideos = mutableListOf<VideoItem>()
    private var nextContinuation: String? = null
    private var loadingMore = false
    private var channelId: String = ""
    private var channelName: String = ""
    private var channelAvatar: String = ""
    private var channelSubs: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel)

        channelId = intent.getStringExtra("CHANNEL_ID") ?: ""
        channelName = intent.getStringExtra("CHANNEL_NAME") ?: ""

        val ivAvatar = findViewById<ImageView>(R.id.ivAvatar)
        val tvChannelName = findViewById<TextView>(R.id.tvChannelName)
        val tvSubscribers = findViewById<TextView>(R.id.tvSubscribers)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val btnSubscribe = findViewById<MaterialButton>(R.id.btnSubscribe)

        tvChannelName.text = channelName
        
        // ★ 롱프레스 → 진단 로그 보기
        tvChannelName.setOnLongClickListener {
            val f = java.io.File(filesDir, "channel_diag.log")
            val content = if (f.exists()) f.readText() else "(로그 없음)"
            val lines = content.lines().takeLast(100).joinToString("\n")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("채널 진단 로그 (마지막 100줄)")
                .setMessage(lines)
                .setPositiveButton("닫기", null)
                .setNeutralButton("🗑 지우기") { _, _ -> f.delete() }
                .show()
            true
        }

        adapter = SearchAdapter { item ->
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_ID", item.videoId)
                putExtra("VIDEO_TITLE", item.title)
                putExtra("VIDEO_CHANNEL", item.channel)
                putExtra("VIDEO_THUMB", item.thumbnail)
            })
        }
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as GridLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                if (!loadingMore && nextContinuation != null && lastVisible >= total - 3) {
                    loadMore()
                }
            }
        })

        if (channelId.isBlank()) {
            tvStatus.text = "채널 ID 없음"
            tvStatus.visibility = View.VISIBLE
            btnSubscribe.visibility = View.GONE
            return
        }

        // 구독 상태 반영
        refreshSubscribeButton(btnSubscribe)

        btnSubscribe.setOnClickListener {
            toggleSubscribe()
        }

        // ★ 전체 재생
        findViewById<MaterialButton>(R.id.btnPlayAll)?.setOnClickListener {
            playAll()
        }

        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "채널 정보 불러오는 중..."

        lifecycleScope.launch {
            val (info, page) = YouTubeChannel.fetch(channelId, channelName)
            progress.visibility = View.GONE

            if (info != null) {
                channelName = info.name.ifBlank { channelName }
                channelAvatar = info.avatar
                channelSubs = info.subscribers
                tvChannelName.text = channelName
        
        // ★ 롱프레스 → 진단 로그 보기
        tvChannelName.setOnLongClickListener {
            val f = java.io.File(filesDir, "channel_diag.log")
            val content = if (f.exists()) f.readText() else "(로그 없음)"
            val lines = content.lines().takeLast(100).joinToString("\n")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("채널 진단 로그 (마지막 100줄)")
                .setMessage(lines)
                .setPositiveButton("닫기", null)
                .setNeutralButton("🗑 지우기") { _, _ -> f.delete() }
                .show()
            true
        }
                tvSubscribers.text = listOf(info.subscribers, info.videoCount)
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (info.avatar.isNotBlank()) {
                    Glide.with(ivAvatar).load(info.avatar).circleCrop().into(ivAvatar)
                }
            }

            nextContinuation = page.continuation
            // ★ 로그: 첫 페이지 결과 + continuation 유무
            android.util.Log.d("Channel", "fetch: videos=${page.videos.size} cont=${page.continuation?.take(40)} debug=${YouTubeChannel.lastDebug}")
            tvStatus.text = if (page.videos.isEmpty()) "영상 없음"
                             else "${page.videos.size}개 (더: ${page.continuation != null})"
            adapter.submit(page.videos)
            loadedVideos.clear()
            loadedVideos.addAll(page.videos)
            android.util.Log.d("Channel",
                "초기 로드: ${page.videos.size}개, next=${page.continuation?.take(50)}")
        }
    }

    private fun refreshSubscribeButton(btn: MaterialButton) {
        if (channelId.isBlank()) return
        lifecycleScope.launch {
            val sub = withContext(Dispatchers.IO) {
                try {
                    HistoryDatabase.get(applicationContext).subscriptionDao()
                        .isSubscribed(channelId)
                } catch (e: Exception) { false }
            }
            if (sub) {
                btn.text = "구독중 ✓"
                btn.setBackgroundColor(0xFF26262A.toInt())
                btn.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btn.text = "구독"
                btn.setBackgroundColor(0xFFFF2D55.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    private fun toggleSubscribe() {
        if (channelId.isBlank()) return
        val btn = findViewById<MaterialButton>(R.id.btnSubscribe)
        lifecycleScope.launch {
            try {
                val dao = HistoryDatabase.get(applicationContext).subscriptionDao()
                val sub = withContext(Dispatchers.IO) { dao.isSubscribed(channelId) }
                if (sub) {
                    withContext(Dispatchers.IO) { dao.delete(channelId) }
                    Toast.makeText(this@ChannelActivity, "구독 해제", Toast.LENGTH_SHORT).show()
                } else {
                    withContext(Dispatchers.IO) {
                        dao.insert(
                            SubscriptionEntity(
                                channelId = channelId,
                                name = channelName,
                                avatar = channelAvatar,
                                subscribers = channelSubs,
                                subscribedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    Toast.makeText(this@ChannelActivity, "구독! 홈에서 확인하세요", Toast.LENGTH_SHORT).show()
                }
                refreshSubscribeButton(btn)
            } catch (e: Exception) {
                Toast.makeText(this@ChannelActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAll() {
        val list = loadedVideos.toList()
        if (list.isEmpty()) {
            Toast.makeText(this, "재생할 영상이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        QueueManager.clear(this)
        for (v in list) {
            QueueManager.add(this, HomeVideo(v.videoId, v.title, v.channel, v.thumbnail))
        }
        val first = list.first()
        QueueManager.setCurrent(this, first.videoId)

        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", first.videoId)
            putExtra("VIDEO_TITLE", first.title)
            putExtra("VIDEO_CHANNEL", first.channel)
            putExtra("VIDEO_THUMB", first.thumbnail)
            putExtra("FROM_PLAYLIST", true)
        })
    }

    private fun loadMore() {
        val token = nextContinuation ?: return
        if (token.isBlank()) return
        loadingMore = true
        cdiag("loadMore 시작: token=${token.take(50)}")
        lifecycleScope.launch {
            try {
                val page = YouTubeChannel.fetchMore(token)
                cdiag("fetchMore 응답: videos=${page.videos.size}, next=${page.continuation?.take(50)}")

                // ★ 서버가 빈 값을 주더라도 기존 토큰을 유지
                if (page.continuation != null && page.continuation.isNotBlank()) {
                    nextContinuation = page.continuation
                    cdiag("다음 토큰 갱신: ${nextContinuation?.take(40)}")
                } else if (page.videos.isEmpty()) {
                    cdiag("빈 결과 반환됨. 기존 토큰 유지.")
                } else {
                    nextContinuation = null // 진짜 끝
                    cdiag("다음 페이지 없음 (끝).")
                }

                // ★ 중복 제거
                val existingIds = loadedVideos.map { it.videoId }.toSet()
                val newVideos = page.videos.filter { it.videoId !in existingIds }

                if (newVideos.isNotEmpty()) {
                    adapter.append(newVideos)
                    loadedVideos.addAll(newVideos)
                    cdiag("신규 영상 추가: ${newVideos.size}개 (총 ${loadedVideos.size}개)")
                }

                loadingMore = false

                // ★ tvStatus 업데이트
                val tvStatus = findViewById<TextView>(R.id.tvStatus)
                if (tvStatus != null) {
                    val hasMore = nextContinuation != null
                    tvStatus.text = "${loadedVideos.size}개 (더: $hasMore)"
                    cdiag("UI 갱신: 총 ${loadedVideos.size}개 (더: $hasMore)")
                }
            } catch (e: Exception) {
                cdiag("loadMore 에러: ${e.message}")
                loadingMore = false
            }
        }
    }
}
