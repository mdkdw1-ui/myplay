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
        // ★ visitorData 강제 로드
        if (visitorData.isBlank()) {
            visitorData = YouTubeVisitorData.load(ctx)
            if (visitorData.isBlank()) {
                try {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(2000) {
                            YouTubeVisitorFetcher.ensure(ctx)
                        }
                    }
                    visitorData = YouTubeVisitorData.load(ctx)
                } catch (_: Exception) {}
            }
        }
        // ★ visitorData 있으면 integrityToken 없어도 POT 반환
        if (visitorData.isBlank()) {
            Log.w(TAG, "POT: visitorData 없음, skip")
            return null
        }
        val token = if (integrityToken.isNotBlank() && System.currentTimeMillis() < tokenExpiry) {
            integrityToken
        } else {
            "visitor_only"   // ★ 가짜 토큰 (NewPipe가 visitorData 사용하도록 유도)
        }
        Log.d(TAG, "POT 반환: token=${token.take(20)}, visitor=${visitorData.take(20)}")
        return PoTokenResult(
            "$token|$videoId",
            "$token|$visitorData",
            visitorData
        )
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
