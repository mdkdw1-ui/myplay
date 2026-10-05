package com.example.myplayer

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class DownloaderImpl : Downloader() {

    private val ipv4Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 else all
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4Dns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        // ★ visitor_id 요청 가로채기
        if (url.contains("youtubei/v1/visitor_id")) {
            try {
                val ctx = MyApp.instance.applicationContext
                val cached = YouTubeVisitorData.load(ctx)
                if (cached.isNotBlank()) {
                    val fakeJson = """{"responseContext":{"visitorData":"$cached"}}"""
                    android.util.Log.d("Downloader", "visitor_id 가로채기 len=${cached.length}")
                    return Response(200, "OK", emptyMap(), fakeJson, url)
                } else {
                    android.util.Log.d("Downloader", "visitor_id 요청 but 캐시 비었음")
                }
            } catch (_: Exception) {}
        }

        val builder = okhttp3.Request.Builder().url(url)

        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

        headers?.forEach { entry ->
            val key = entry.key
            if (key.equals("User-Agent", ignoreCase = true)) return@forEach
            if (key.equals("Accept-Language", ignoreCase = true)) return@forEach
            if (key.equals("Content-Length", ignoreCase = true)) return@forEach
            try {
                builder.header(key, entry.value.joinToString(","))
            } catch (_: Exception) {}
        }

        builder.header("User-Agent", ua)
        builder.header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        builder.header("Accept", "*/*")
        builder.header("Origin", "https://www.youtube.com")
        builder.header("Referer", "https://www.youtube.com/")
        builder.header("X-YouTube-Client-Name", "1")
        builder.header("X-YouTube-Client-Version", "2.20240101.00.00")
        builder.header("X-Origin", "https://www.youtube.com")
        builder.header("X-Goog-AuthUser", "0")
        builder.header("Sec-Fetch-Site", "same-origin")
        builder.header("Sec-Fetch-Mode", "cors")
        builder.header("Sec-Fetch-Dest", "empty")

        try {
            val ctx = MyApp.instance.applicationContext
            val cookie = YouTubeCookieManager.load(ctx)
            if (cookie.isNotBlank()) {
                builder.header("Cookie", cookie)
                try {
                    val hash = YouTubeVisitorData.sapisidHash(cookie)
                    if (hash != null) builder.header("Authorization", hash)
                } catch (_: Exception) {}
            }
            val vd = YouTubeVisitorData.load(ctx)
            if (vd.isNotBlank()) {
                builder.header("X-Goog-Visitor-Id", vd)
            }
        } catch (_: Exception) {}

        if (dataToSend != null && httpMethod != "GET" && httpMethod != "HEAD") {
            val ct = (headers?.get("Content-Type")?.firstOrNull() ?: "application/json")
                .toMediaType()
            builder.method(httpMethod, dataToSend.toRequestBody(ct))
        } else {
            builder.method(httpMethod, null)
        }

        val okResp = client.newCall(builder.build()).execute()
        val code = okResp.code
        if (code == 429) {
            okResp.close()
            throw ReCaptchaException("reCaptcha challenge requested", url)
        }
        val body = okResp.body?.string() ?: ""
        val respHeaders = mutableMapOf<String, List<String>>()
        // ★ OkHttp headers 순회 (각 값 개별)
        for (i in 0 until okResp.headers.size) {
            val name = okResp.headers.name(i)
            val value = okResp.headers.value(i)
            val existing = respHeaders[name]
            if (existing == null) {
                respHeaders[name] = listOf(value)
            } else {
                respHeaders[name] = existing + value
            }
        }
        val latestUrl = okResp.request.url.toString()
        val msg = okResp.message
        okResp.close()

        @Suppress("UNCHECKED_CAST")
        return Response(
            code,
            msg,
            respHeaders as Map<String, List<String>>,
            body,
            latestUrl
        )
    }
}
