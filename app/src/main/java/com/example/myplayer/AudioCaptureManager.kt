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

class AudioCaptureManager(
    private val ctx: Context,
    private val onChunkReady: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile private var capturing = false

    private val chunkBuffer = ByteArrayOutputStream()
    private val chunkSizeBytes = (SAMPLE_RATE * 2 * CHUNK_SECONDS)

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SECONDS = 3
        private const val TAG = "AudioCapture"
    }

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
