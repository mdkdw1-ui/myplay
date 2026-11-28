package com.example.myplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class ChapterReidentifyService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var inFlight = false
    private var lastSubmittedKey: List<String>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.d("ChapterReidentify", "onServiceConnected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            // Observe window changes to detect leaving Google.
            // Text is collected only from Google below.
            packageNames = null
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.packageName?.toString() != "com.google.android.googlequicksearchbox") {
            if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                ev.packageName != null) {
                lastSubmittedKey = null
            }
            return
        }

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        android.util.Log.d("ChapterReidentify", "collecting texts")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { if (it.isNotBlank()) texts.add(it.toString()) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        if (inFlight) return

        walk(root)
        val cardMarkers = setOf("AI 모드", "전체", "동영상", "이미지")
        if (texts.none { it.trim() in cardMarkers }) return

        texts.forEachIndexed { index, text ->
            android.util.Log.d("ChapterReidentify", "text[$index]=${text.replace("\n", " / ")}")
        }

        val normalized = texts.map {
            it.replace('\u00A0', ' ').trim()
        }
        val songMeta = Regex("""^(.+)의\s+노래\s*[·‧•・]\s*\d{4}년$""")
        val metaIndex = normalized.indexOfFirst {
            songMeta.matchEntire(it) != null
        }
        if (metaIndex <= 0) return

        val match = songMeta.matchEntire(normalized[metaIndex]) ?: return
        val artist = match.groupValues[1].trim()
        val songTitle = normalized[metaIndex - 1]

        if (artist.isBlank() || songTitle.isBlank()) return
        if (songTitle in setOf("검색결과", "검색 결과", "전체", "동영상", "이미지")) return

        val prefs = getSharedPreferences("audio_prefs", MODE_PRIVATE)
        scope.launch {
            try {
                var vid = prefs.getString("current_video_id", null)
                if (vid.isNullOrBlank()) {
                    vid = ChapterReidentifyClient.fetchCurrentVideoId()
                    if (vid.isNullOrBlank()) return@launch
                    prefs.edit().putString("current_video_id", vid).apply()
                }
                val pos = PlaybackService.exoPlayer?.currentPosition ?: 0L
                val requestKey = listOf(vid, artist, songTitle)
                if (inFlight || lastSubmittedKey == requestKey) return@launch
                inFlight = true
                lastSubmittedKey = requestKey
                android.util.Log.d("ChapterReidentify", "sending video=$vid hint_ms=$pos title=$songTitle artist=$artist")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "챕터 식별 중… (최대 1분)", android.widget.Toast.LENGTH_SHORT).show()
                }
                val r = ChapterReidentifyClient.fixChapter(vid, pos, songTitle, artist)
                android.util.Log.d("ChapterReidentify", "fix result ok=${r.ok} title=${r.title} source=${r.source}")
                if (!r.added) {
                    val msg = when (r.reason) {
                        "duplicate" -> "이미 챕터에 있는 곡입니다: $songTitle"
                        "no_duration" -> "곡 길이를 찾지 못했습니다"
                        else -> "챕터를 추가하지 못했습니다"
                    }
                    if (r.reason != "duplicate") lastSubmittedKey = emptyList()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                if (r.ok && r.title.isNotBlank()) {
                    val i = android.content.Intent("com.example.myplayer.ACTION_CHAPTER_TITLE_UPDATED").setPackage(packageName)
                    i.putExtra("title", r.title)
                    i.putExtra("startMs", r.startMs)
                    i.putExtra("source", r.source)
                    r.chaptersJson?.let { i.putExtra("chaptersJson", it) }
                    sendBroadcast(i)
                    android.util.Log.d("ChapterReidentify", "title updated: ${r.title}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.d("ChapterReidentify", "reidentify error: ${e.message}")
            } finally {
                inFlight = false
            }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
