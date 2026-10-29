package com.example.myplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        BottomNavHelper.setup(this, BottomNavHelper.TAB_VIDEO)

        // 알림 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // 상단 아이콘
        findViewById<android.view.View>(R.id.btnSearchTop)?.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 카테고리 칩
        findViewById<android.view.View>(R.id.chipVideo)?.setOnClickListener { /* current */ }
        findViewById<android.view.View>(R.id.chipAudio)?.setOnClickListener {
            startActivity(Intent(this, AudioHomeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.chipLocal)?.setOnClickListener {
            startActivity(Intent(this, LocalMediaActivity::class.java))
        }

        // 2열 그리드 피드
        val rvFeed = findViewById<RecyclerView>(R.id.rvMainFeed)
        rvFeed.layoutManager = GridLayoutManager(this, 2)
        val feedAdapter = SearchAdapter(
            onMultiSave = null,
            onClick = { item -> PlayModeChooser.show(this, item) }
        )
        rvFeed.adapter = feedAdapter

        // 피드 데이터 로드
        lifecycleScope.launch {
            try {
                val trending = try { YouTubeTrending.fetch() } catch (e: Exception) { emptyList() }
                val history = try {
                    withContext(Dispatchers.IO) {
                        HistoryDatabase.get(applicationContext).historyDao().getAllOnce()
                    }
                } catch (e: Exception) { emptyList() }

                val combined = mutableListOf<VideoItem>()
                combined.addAll(trending)
                val existingIds = combined.map { it.videoId }.toSet()
                history.forEach { h ->
                    if (h.videoId !in existingIds) {
                        combined.add(VideoItem(h.videoId, h.title, h.channel, h.thumbnail))
                    }
                }
                if (combined.isNotEmpty()) {
                    runOnUiThread { feedAdapter.submit(combined) }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "피드 로드 실패: ${e.message}")
            }
        }

        // 워커
        try {
            val req1 = androidx.work.PeriodicWorkRequestBuilder<SubscriptionWorker>(
                6, java.util.concurrent.TimeUnit.HOURS
            ).build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("sub_check",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP, req1)

            val req2 = androidx.work.PeriodicWorkRequestBuilder<ServerHealthWorker>(
                12, java.util.concurrent.TimeUnit.HOURS
            ).build()
            androidx.work.WorkManager.getInstance(applicationContext)
                .enqueueUniquePeriodicWork("server_health",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP, req2)
        } catch (_: Exception) {}
    }
}
