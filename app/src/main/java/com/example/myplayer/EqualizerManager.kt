package com.example.myplayer

import android.media.audiofx.Equalizer
import android.util.Log

object EqualizerManager {

    private const val TAG = "EqualizerManager"
    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0

    fun attach(audioSessionId: Int): Boolean {
        if (audioSessionId == 0) return false
        if (sessionId == audioSessionId && equalizer != null) return true
        release()
        return try {
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
            sessionId = audioSessionId
            Log.d(TAG, "attached session=$audioSessionId bands=${equalizer?.numberOfBands}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "attach err: ${e.message}", e)
            false
        }
    }

    fun release() {
        try { equalizer?.release() } catch (e: Exception) { }
        equalizer = null
        sessionId = 0
    }

    fun bandCount(): Int = try {
        equalizer?.numberOfBands?.toInt() ?: 5
    } catch (e: Exception) { 5 }

    fun centerFreqHz(band: Int): Int {
        return try {
            val eq = equalizer ?: return 0
            val range = eq.getBandFreqRange(band.toShort())
            ((range[0] + range[1]) / 2 / 1000).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "centerFreqHz err: ${e.message}")
            0
        }
    }

    fun levelRange(): Pair<Int, Int> {
        return try {
            val eq = equalizer ?: return Pair(-1500, 1500)
            val r = eq.bandLevelRange
            Pair(r[0].toInt(), r[1].toInt())
        } catch (e: Exception) {
            Log.e(TAG, "levelRange err: ${e.message}")
            Pair(-1500, 1500)
        }
    }

    fun getLevel(band: Int): Int {
        return try {
            equalizer?.getBandLevel(band.toShort())?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    fun setLevel(band: Int, level: Int) {
        try {
            equalizer?.setBandLevel(band.toShort(), level.toShort())
        } catch (e: Exception) { }
    }

    /** 0=평탄, 1=저음강조, 2=고음강조, 3=V자 */
    fun applyPreset(preset: Int) {
        val bands = bandCount()
        val (minL, maxL) = levelRange()
        for (i in 0 until bands) {
            val factor = i.toFloat() / (bands - 1).coerceAtLeast(1)
            val level = when (preset) {
                1 -> (maxL * 0.6f * (1f - factor)).toInt()
                2 -> (maxL * 0.6f * factor).toInt()
                3 -> {
                    val v = if (factor < 0.5f) (0.5f - factor) * 2f else (factor - 0.5f) * 2f
                    (maxL * 0.7f * v).toInt()
                }
                else -> 0
            }
            setLevel(i, level.coerceIn(minL, maxL))
        }
    }

    fun setEnabled(enabled: Boolean) {
        try { equalizer?.enabled = enabled } catch (e: Exception) { }
    }

    fun isEnabled(): Boolean = try {
        equalizer?.enabled == true
    } catch (e: Exception) { false }
}
