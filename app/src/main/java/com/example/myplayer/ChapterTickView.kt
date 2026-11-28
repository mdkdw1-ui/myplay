package com.example.myplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ChapterTickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var positions = listOf<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 2f
    }

    fun update(chapters: List<Chapter>, durationMs: Long) {
        val duration = durationMs.coerceAtLeast(0L)
        positions = if (duration <= 0L) {
            emptyList()
        } else {
            chapters
                .map { it.startMs.toFloat() / duration.toFloat() }
                .filter { it > 0.001f && it < 0.999f }
                .distinct()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (positions.isEmpty()) return
        val top = height * 0.15f
        val bottom = height * 0.85f
        positions.forEach { ratio ->
            val x = ratio * width
            canvas.drawLine(x, top, x, bottom, paint)
        }
    }
}
