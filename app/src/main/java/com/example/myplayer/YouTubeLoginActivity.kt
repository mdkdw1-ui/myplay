package com.example.myplayer

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class YouTubeLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_login)

        webView = findViewById(R.id.webView)
        tvStatus = findViewById(R.id.tvStatus)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.contains("youtube.com") && !url.contains("accounts.google.com")) {
                    tryExtractCookies()
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("youtube.com") && !url.contains("accounts.google.com")) {
                    tryExtractCookies()
                }
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            tryExtractCookies(force = true)
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            YouTubeCookieManager.clear(this)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            tvStatus.text = "쿠키 삭제됨"
            saved = false
            Toast.makeText(this, "쿠키 삭제됨", Toast.LENGTH_SHORT).show()
        }

        // 로그인 페이지로 이동
        webView.loadUrl(
            "https://accounts.google.com/ServiceLogin?service=youtube" +
            "&continue=https://www.youtube.com/"
        )
    }

    private fun tryExtractCookies(force: Boolean = false) {
        if (saved && !force) return
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com")
            ?: return
        if (cookies.isBlank()) return

        // 필수 쿠키만 필터
        val essential = cookies.split("; ").filter {
            it.startsWith("SAPISID=") ||
            it.startsWith("__Secure-3PAPISID=") ||
            it.startsWith("__Secure-1PAPISID=") ||
            it.startsWith("LOGIN_INFO=") ||
            it.startsWith("SID=") ||
            it.startsWith("HSID=") ||
            it.startsWith("SSID=") ||
            it.startsWith("APISID=") ||
            it.startsWith("__Secure-3PSID=") ||
            it.startsWith("__Secure-1PSID=") ||
            it.startsWith("SIDCC=")
        }

        if (essential.none { it.startsWith("LOGIN_INFO=") } &&
            essential.none { it.startsWith("SAPISID=") }) {
            tvStatus.text = "로그인 후 자동 저장 시도 중..."
            return
        }

        val cookieStr = essential.joinToString("; ")
        YouTubeCookieManager.save(this, cookieStr)
        saved = true
        tvStatus.text = "✅ 쿠키 저장됨 (${essential.size}개)"
        Toast.makeText(this, "로그인 완료 · 쿠키 저장됨", Toast.LENGTH_LONG).show()
    }
}
