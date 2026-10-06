package com.example.myplayer

import android.media.audiofx.LoudnessEnhancer
import android.util.Log

object LoudnessManager {

    private const val TAG = "LoudnessManager"
    private var enhancer: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    /** @param targetGainMb 0~1500 (0dB ~ +15dB) */
    fun attach(audioSessionId: Int, targetGainMb: Int = 0): Boolean {
        if (audioSessionId == 0) return false
        if (sessionId == audioSessionId && enhancer != null) {
            if (targetGainMb > 0) setTargetGain(targetGainMb)
            return true
        }
        release()
        return try {
            enhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(targetGainMb)
                enabled = targetGainMb > 0
            }
            sessionId = audioSessionId
            Log.d(TAG, "attached session=$audioSessionId gain=$targetGainMb mb")
            true
        } catch (e: Exception) {
            Log.e(TAG, "attach err: ${e.message}", e)
            false
        }
    }

    fun setTargetGain(mb: Int) {
        try {
            enhancer?.setTargetGain(mb)
            enhancer?.enabled = mb > 0
        } catch (e: Exception) {
            Log.e(TAG, "setTargetGain err: ${e.message}", e)
        }
    }

    fun getTargetGain(): Int = try {
        enhancer?.targetGain?.toInt() ?: 0
    } catch (e: Exception) { 0 }

    fun isEnabled(): Boolean = try {
        enhancer?.enabled == true
    } catch (e: Exception) { false }

    fun release() {
        try { enhancer?.release() } catch (_: Exception) {}
        enhancer = null
        sessionId = 0
    }
}
