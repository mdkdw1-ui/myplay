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
            level = HttpLoggingInterceptor.Level.BASIC
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
                    format = "json".toRequestBody("text/plain".toMediaType())
                )

                if (response.isSuccessful) {
                    val text = response.body()?.text?.trim() ?: ""
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) { onResult(text) }
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
