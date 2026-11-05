package com.example.myplayer

import android.graphics.Color
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.SeekBar
import androidx.core.content.ContextCompat

object UiFx {

    fun glass(view: View) {
        view.background = ContextCompat.getDrawable(view.context, R.drawable.bg_glass_card)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, 28f * v.resources.displayMetrics.density)
            }
        }
        view.elevation = 18f
    }

    fun softConvex(view: View) {
        view.background = ContextCompat.getDrawable(view.context, R.drawable.bg_soft_convex)
        view.clipToOutline = true
        view.elevation = 10f
    }

    fun playButton(view: View) {
        view.background = ContextCompat.getDrawable(view.context, R.drawable.bg_play_button)
        view.clipToOutline = true
        view.elevation = 22f
    }

    // ★ ivArt는 CardView 안에 있으므로 clipToOutline은 무시하고 elevation만 적용
    fun artGlow(view: View) {
        view.elevation = 28f
        view.translationZ = 8f
    }

    fun concaveTrack(bar: SeekBar) {
        bar.progressTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FF7A5CFF"))
        bar.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#22FFFFFF"))
        bar.thumbTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFFFFFF"))
    }

    fun pressScale(view: View, down: Float = 0.94f) {
        view.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(down).scaleY(down).setDuration(120).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }
}
