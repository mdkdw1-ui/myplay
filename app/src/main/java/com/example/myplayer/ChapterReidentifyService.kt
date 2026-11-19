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
        if (event?.packageName?.toString() != "com.google.android.googlequicksearchbox") return
        val now = System.currentTimeMillis()
        if (now - lastSentMs < 3000) return
        lastSentMs = now

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { if (it.isNotBlank()) texts.add(it.toString()) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)

        val parsed = texts.firstOrNull {
            Regex("^(.{2,60})\\s*[·\\-|]\\s*(.{2,60})$").matches(it.trim())
        }?.trim() ?: return

        val prefs = getSharedPreferences("audioprefs", MODE_PRIVATE)
        val vid = prefs.getString("currentvideoid", null) ?: return
        val pos = PlaybackService.exoPlayer?.currentPosition ?: 0L

        scope.launch {
            try {
                ChapterReidentifyClient.reidentify(vid, pos / 1000.0, parsed)
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
