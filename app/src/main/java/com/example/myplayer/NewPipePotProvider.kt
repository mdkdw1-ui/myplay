package com.example.myplayer

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

class NewPipePotProvider(private val ctx: Context) : PoTokenProvider {
    private val TAG = \"NewPipePot\"
    private var webView: WebView? = null
    private var visitorData: String = \"\"
    private var integrityToken: String = \"\"
    private var tokenExpiry: Long = 0L

    @JavascriptInterface
    fun onBotguardResponse(response: String) {
        Log.d(TAG, \"botguard response received\")
        // GenerateIT 호출 (간소화 - 실제로는 jnn-pa.googleapis.com 호출 필요)
        integrityToken = response
        tokenExpiry = System.currentTimeMillis() + 3600_000L
    }

    private fun initWebView() {
        if (webView != null) return
        webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            addJavascriptInterface(this@NewPipePotProvider, \"PoToken\")
            loadUrl(\"file:///android_asset/po_token.html\")
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        initWebView()
        if (integrityToken.isBlank() || System.currentTimeMillis() > tokenExpiry) {
            Log.w(TAG, \"POT not ready for $videoId\")
            return null
        }
        // visitorData 기반 streamingPot + videoId 기반 playerPot
        return PoTokenResult(
            playerPot = \"$integrityToken|$videoId\",
            streamingPot = \"$integrityToken|$visitorData\"
        )
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null
}
