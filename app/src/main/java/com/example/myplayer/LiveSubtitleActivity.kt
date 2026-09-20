package com.example.myplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class LiveSubtitleActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MP = 7001
        private const val TAG = "LiveSubtitle"
    }

    private var mediaProjection: MediaProjection? = null
    private var captureManager: AudioCaptureManager? = null
    private var groqManager: GroqSttManager? = null

    private lateinit var tvFinal: TextView
    private lateinit var tvPartial: TextView
    private lateinit var tvStatus: TextView
    private lateinit var scroll: ScrollView

    private val finalBuilder = StringBuilder()
    private var lastText = ""
    private var currentLang = "ko"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_subtitle)

        tvFinal = findViewById(R.id.tvFinalText)
        tvPartial = findViewById(R.id.tvPartialText)
        tvStatus = findViewById(R.id.tvStatus)
        scroll = findViewById(R.id.scroll)

        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            Toast.makeText(this, "GROQ_API_KEY 없음", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        groqManager = GroqSttManager(apiKey)

        findViewById<MaterialButton>(R.id.btnLangKo).setOnClickListener { setLang("ko") }
        findViewById<MaterialButton>(R.id.btnLangEn).setOnClickListener { setLang("en") }
        findViewById<MaterialButton>(R.id.btnLangJa).setOnClickListener { setLang("ja") }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            finalBuilder.setLength(0)
            tvFinal.text = ""
            tvPartial.text = ""
            lastText = ""
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            tvStatus.text = "Android 10+ 필요"
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MP)
    }

    private fun setLang(lang: String) {
        currentLang = lang
        tvStatus.text = "언어: $lang"
        lastText = ""
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MP) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, "화면 캡처 권한 필요", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        startCapture()
    }

    private fun startCapture() {
        val mp = mediaProjection ?: return
        tvStatus.text = "🎙 캡처 중 · 언어: $currentLang"

        captureManager = AudioCaptureManager(this) { chunk ->
            groqManager?.transcribeChunk(
                chunk,
                language = currentLang,
                onResult = { text ->
                    val newText = if (lastText.isNotEmpty() && text.startsWith(lastText)) {
                        text.removePrefix(lastText).trim()
                    } else {
                        text
                    }
                    if (newText.isNotBlank()) {
                        lastText = text
                        finalBuilder.append(newText).append("\n")
                        tvFinal.text = finalBuilder.toString()
                        tvPartial.text = ""
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                },
                onError = { err ->
                    Log.e(TAG, "STT err: $err")
                }
            )
        }

        val ok = captureManager?.start(mp) ?: false
        tvStatus.text = if (ok) "🎙 캡처 중 · 언어: $currentLang" else "캡처 실패"
    }

    override fun onDestroy() {
        super.onDestroy()
        captureManager?.stop()
        try { mediaProjection?.stop() } catch (e: Exception) { }
        groqManager?.release()
    }
}
