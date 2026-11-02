package com.example.myplayer

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

class NewPipePotProvider(private val ctx: Context) : PoTokenProvider {
    private val TAG = "NewPipePot"
    private var webView: WebView? = null
    private var visitorData: String = ""
    private var integrityToken: String = ""
    private var tokenExpiry: Long = 0L

    @JavascriptInterface
    fun onBotguardResponse(response: String) {
        Log.d(TAG, "botguard response received")
        integrityToken = response
        tokenExpiry = System.currentTimeMillis() + 3600_000L
    }

    private fun initWebView() {
        if (webView != null) return
        // ★ 1.478: Android 17 UI 스레드 + try-catch(Throwable)
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    addJavascriptInterface(this@NewPipePotProvider, "PoToken")
                    loadUrl("file:///android_asset/po_token.html")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "WebView init failed: ${e.message}")
                webView = null
            } finally { latch.countDown() }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        initWebView()
        if (integrityToken.isBlank() || System.currentTimeMillis() > tokenExpiry) {
            Log.w(TAG, "POT not ready for $videoId")
            return null
        }
        return PoTokenResult(
            "$integrityToken|$videoId", // playerPot
            "$integrityToken|$visitorData", // streamingPot
            visitorData // p2 (visitorData)
        )
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
