package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GroqSttManager(private val apiKey: String) {

    private val api: GroqSttApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GroqSttApi::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val busy = AtomicBoolean(false)

    // ★ Whisper 컨텍스트 (이전 텍스트)
    private var lastContext: String = ""

    fun reset() {
        lastContext = ""
    }

    fun transcribeChunk(
        pcmData: ByteArray,
        language: String = "ko",
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (busy.get()) return
        busy.set(true)

        scope.launch {
            try {
                val wavData = pcmToWav(pcmData, 16000, 1, 16)
                val requestFile = wavData.toRequestBody("audio/wav".toMediaType())
                val body = MultipartBody.Part.createFormData("file", "audio.wav", requestFile)

                val response = api.transcribe(
                    bearer = "Bearer $apiKey",
                    file = body,
                    model = "whisper-large-v3-turbo".toRequestBody("text/plain".toMediaType()),
                    language = language.toRequestBody("text/plain".toMediaType()),
                    format = "json".toRequestBody("text/plain".toMediaType()),
                    // ★ 이전 텍스트를 컨텍스트로 (Whisper 정확도↑)
                    prompt = lastContext.toRequestBody("text/plain".toMediaType()),
                    // ★ 0.0 = 일관성 최우선
                    temperature = "0.0".toRequestBody("text/plain".toMediaType())
                )

                if (response.isSuccessful) {
                    val raw = response.body()?.text?.trim() ?: ""
                    if (raw.isNotBlank()) {
                        // ★ 이전 결과와 겹치는 부분 제거
                        val cleaned = removeOverlap(lastContext, raw)
                        if (cleaned.isNotBlank()) {
                            lastContext = raw
                            withContext(Dispatchers.Main) { onResult(cleaned) }
                        }
                    }
                } else {
                    val err = response.errorBody()?.string() ?: ""
                    Log.e("GroqStt", "API ${response.code()} - $err")
                    withContext(Dispatchers.Main) { onError?.invoke("${response.code()}") }
                }
            } catch (e: Exception) {
                Log.e("GroqStt", "실패: ${e.message}", e)
                withContext(Dispatchers.Main) { onError?.invoke(e.message ?: "err") }
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * ★ 이전 텍스트와 겹치는 접두사 제거
     * 예: prev="안녕하세요 오늘은", curr="오늘은 날씨가" → "날씨가"
     */
    private fun removeOverlap(prev: String, curr: String): String {
        if (prev.isBlank()) return curr
        val prevWords = prev.split(" ").filter { it.isNotBlank() }
        val currWords = curr.split(" ").filter { it.isNotBlank() }
        if (prevWords.isEmpty() || currWords.isEmpty()) return curr

        // 겹치는 접두사 탐색 (3~10단어)
        val maxCheck = minOf(prevWords.size, currWords.size, 10)
        for (n in maxCheck downTo 3) {
            val prevTail = prevWords.takeLast(n).joinToString(" ")
            val currHead = currWords.take(n).joinToString(" ")
            if (prevTail == currHead) {
                return currWords.drop(n).joinToString(" ").trim()
            }
        }
        return curr
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(intToByteArray(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToByteArray(16))
        out.write(shortToByteArray(1))
        out.write(shortToByteArray(channels.toShort()))
        out.write(intToByteArray(sampleRate))
        out.write(intToByteArray(byteRate))
        out.write(shortToByteArray((channels * bitsPerSample / 8).toShort()))
        out.write(shortToByteArray(bitsPerSample.toShort()))
        out.write("data".toByteArray())
        out.write(intToByteArray(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortToByteArray(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xff).toByte(),
        ((value.toInt() shr 8) and 0xff).toByte()
    )

    fun release() {
        scope.cancel()
    }
}
