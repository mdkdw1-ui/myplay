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

        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "setContentView 실패", e)
            finish()
            return
        }

        try {
            BottomNavHelper.setup(this, BottomNavHelper.TAB_VIDEO)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "BottomNav setup 실패", e)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                }
            }
        } catch (_: Exception) {}

        findViewById<android.view.View>(R.id.btnSearchTop)?.setOnClickListener {
            try { startActivity(Intent(this, SearchActivity::class.java)) } catch (_: Exception) {}
        }
        findViewById<android.view.View>(R.id.btnSettings)?.setOnClickListener {
            try { startActivity(Intent(this, SettingsActivity::class.java)) } catch (_: Exception) {}
        }

        findViewById<android.view.View>(R.id.chipVideo)?.setOnClickListener { }
        findViewById<android.view.View>(R.id.chipAudio)?.setOnClickListener {
            try { startActivity(Intent(this, AudioHomeActivity::class.java)) } catch (_: Exception) {}
        }
        findViewById<android.view.View>(R.id.chipLocal)?.setOnClickListener {
            try { startActivity(Intent(this, LocalMediaActivity::class.java)) } catch (_: Exception) {}
        }

        val rvFeed = findViewById<RecyclerView>(R.id.rvMainFeed)
        if (rvFeed != null) {
            try {
                rvFeed.layoutManager = GridLayoutManager(this, 2)
                val feedAdapter = SearchAdapter(
                    onMultiSave = null,
                    onClick = { item -> PlayModeChooser.show(this, item) }
                )
                rvFeed.adapter = feedAdapter

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
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "RecyclerView 초기화 실패", e)
            }
        }

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
