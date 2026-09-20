package com.example.myplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioCaptureManager(
    private val ctx: Context,
    private val onChunkReady: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var capturing = false

    private val chunkBuffer = ByteArrayOutputStream()

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // ★ 2초 단위 (지연 감소)
        const val CHUNK_SECONDS = 2

        // ★ 무음 임계값 (RMS)
        const val SILENCE_RMS = 250.0

        private const val TAG = "AudioCapture"
    }

    private val chunkSizeBytes = SAMPLE_RATE * 2 * CHUNK_SECONDS

    fun start(mediaProjection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "requires Android 10+")
            return false
        }
        if (capturing) return true

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val bufSize = maxOf(minBuf, SAMPLE_RATE * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                release()
                return false
            }

            capturing = true
            audioRecord?.startRecording()

            captureThread = Thread {
                val buf = ByteArray(4096)
                while (capturing) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: -1
                    if (n > 0) {
                        chunkBuffer.write(buf, 0, n)
                        if (chunkBuffer.size() >= chunkSizeBytes) {
                            val chunk = chunkBuffer.toByteArray()
                            chunkBuffer.reset()

                            // ★ VAD: 무음이면 스킵
                            val rms = computeRms(chunk)
                            if (rms < SILENCE_RMS) {
                                Log.d(TAG, "skip silence (rms=$rms)")
                                continue
                            }
                            try {
                                onChunkReady(chunk)
                            } catch (e: Exception) {
                                Log.e(TAG, "onChunkReady err: ${e.message}", e)
                            }
                        }
                    }
                }
            }.apply { start() }

            Log.d(TAG, "capture started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "start err: ${e.message}", e)
            release()
            return false
        }
    }

    /** ★ RMS = 오디오 세기 (16bit PCM) */
    private fun computeRms(data: ByteArray): Double {
        if (data.size < 2) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < data.size) {
            // little-endian 16bit
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort()
            val s = sample.toDouble()
            sum += s * s
            i += 2
        }
        return sqrt(sum / (data.size / 2))
    }

    fun stop() {
        capturing = false
        try { audioRecord?.stop() } catch (e: Exception) { }
        captureThread?.join(500)
        release()
        Log.d(TAG, "capture stopped")
    }

    private fun release() {
        try { audioRecord?.release() } catch (e: Exception) { }
        audioRecord = null
        captureThread = null
        chunkBuffer.reset()
    }
}
