package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        findViewById<View>(R.id.cardYtLogin).setOnClickListener {
            startActivity(Intent(this, YouTubeLoginActivity::class.java))
        }

        findViewById<View>(R.id.cardYtClear).setOnClickListener {
            YouTubeCookieManager.clear(this)
            Toast.makeText(this, "YouTube 쿠키 삭제됨", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.tvYtStatus) ?: return
        if (YouTubeCookieManager.hasCookie(this)) {
            tv.text = "✅ 로그인됨 (쿠키 저장됨)"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "❌ 로그인 안 됨 (봇 차단 가능성)"
            tv.setTextColor(0xFFFF2D55.toInt())
        }
    }
}
