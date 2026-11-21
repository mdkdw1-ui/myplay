package com.example.myplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class ChapterReidentifyService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var lastSentMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.d("ChapterReidentify", "onServiceConnected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            packageNames = arrayOf("com.google.android.googlequicksearchbox")
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        android.util.Log.d("ChapterReidentify", "event pkg=${event?.packageName} type=${event?.eventType}")
        if (event?.packageName?.toString() != "com.google.android.googlequicksearchbox") return
        val now = System.currentTimeMillis()

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        android.util.Log.d("ChapterReidentify", "collecting texts")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { if (it.isNotBlank()) texts.add(it.toString()) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        texts.forEachIndexed { index, text ->
            android.util.Log.d("ChapterReidentify", "text[$index]=${text.replace("\n", " / ")}")
        }
        val cardMarkers = setOf("AI 모드", "전체", "동영상", "이미지")
        if (texts.none { it.trim() in cardMarkers }) return

        val uiLabels = setOf("홈", "검색", "알림", "활동", "AI 모드", "전체", "동영상", "이미지", "실행 중인 앱", "모두 닫기", "화면에", "노래를", "물어보기", "재생하거나", "흥얼거려", "Termux", "Perplexity", "DeepSeek")
        val songText = texts.firstOrNull {
            val t = it.trim()
            t !in uiLabels && t.length >= 4 && uiLabels.none { t.contains(it) }
        }?.trim() ?: return
        val parts = songText.split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) return
        val parsed = parts[0] + " - " + parts[1]

        val prefs = getSharedPreferences("audio_prefs", MODE_PRIVATE)
        val vid = prefs.getString("current_video_id", null) ?: return
        val pos = PlaybackService.exoPlayer?.currentPosition ?: 0L

        if (now - lastSentMs < 3000) return
        lastSentMs = now

        scope.launch {
            try {
                val artist = parts[0]
                val songTitle = parts[1]
                val r = ChapterReidentifyClient.fixChapter(vid, pos, songTitle, artist)
            if (r.ok && r.title.isNotBlank()) {
                prefs.edit().putString("current_title", r.title).apply()
                val i = android.content.Intent("com.example.myplayer.ACTION_CHAPTER_TITLE_UPDATED").setPackage(packageName)
                i.putExtra("title", r.title)
                sendBroadcast(i)
                android.util.Log.d("ChapterReidentify", "title updated: ${r.title}")
            }
            } catch (e: Exception) {
                android.util.Log.d("ChapterReidentify", "reidentify error: ${e.message}")
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
