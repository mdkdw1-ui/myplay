package com.example.myplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView 전용 탭/롱프레스 리스너
 * - GestureDetector를 RecyclerView 레벨에서 처리
 * - 스크롤과 완벽히 분리됨
 */
class RecyclerTouchListener(
    private val ctx: Context,
    private val recyclerView: RecyclerView,
    private val onItemClick: (Int, VideoItem) -> Unit,
    private val onItemLongPress: (Int, VideoItem, View) -> Unit,
    private val onItemRelease: (Int, VideoItem, View) -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {

    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var longPressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            longPressed = false
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (longPressed) return true
            val pos = findChildPosition(e)
            if (pos >= 0) {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return false
                val item = getItemAt(pos) ?: return false
                onItemClick(pos, item)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            super.onLongPress(e)
            longPressed = true
            val pos = findChildPosition(e)
            if (pos >= 0) {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val item = getItemAt(pos) ?: return
                onItemLongPress(pos, item, child)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // 스크롤 시작 → 롱프레스 취소
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressed = false
            return false
        }
    })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(e)
        return false  // ★ 스크롤 양보 (중요)
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
            if (longPressed) {
                val pos = findChildPosition(e)
                if (pos >= 0) {
                    val child = rv.findChildViewUnder(e.x, e.y)
                    val item = getItemAt(pos)
                    if (child != null && item != null) {
                        onItemRelease(pos, item, child)
                    }
                }
                longPressed = false
            }
        }
        gestureDetector.onTouchEvent(e)
    }

    private fun findChildPosition(e: MotionEvent): Int {
        val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return -1
        return recyclerView.getChildAdapterPosition(child)
    }

    private fun getItemAt(pos: Int): VideoItem? {
        val adapter = recyclerView.adapter as? SearchAdapter ?: return null
        return adapter.getItem(pos)
    }
}
