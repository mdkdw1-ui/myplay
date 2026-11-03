package com.example.myplayer

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.net.InetSocketAddress

class DownloaderImpl : Downloader() {

    override fun execute(request: Request): Response {
        // ★ DNS 실패/타임아웃 재시도 (5회로 강화, exponential backoff)
        var lastErr: Exception? = null
        for (attempt in 0 until 5) {
            try {
                return executeOnce(request)
            } catch (e: java.net.UnknownHostException) {
                lastErr = e
                android.util.Log.w("DownloaderImpl",
                    "DNS 실패 (${attempt + 1}/5): ${e.message}")
                if (attempt < 4) {
                    try { Thread.sleep(500L * (1L shl attempt)) } catch (_: Exception) {}
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastErr = e
                android.util.Log.w("DownloaderImpl",
                    "타임아웃 (${attempt + 1}/5): ${e.message}")
                if (attempt < 4) {
                    try { Thread.sleep(800L * (1L shl attempt)) } catch (_: Exception) {}
                }
            }
        }
        throw lastErr ?: Exception("DownloaderImpl: all attempts failed")
    }

    private fun executeOnce(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val warpProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("193.123.167.118", 40001))
        val connection = URL(url).openConnection(warpProxy) as HttpURLConnection
        connection.requestMethod = httpMethod
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true

        headers?.forEach { entry ->
            val key = entry.key
            if (key.equals("User-Agent", ignoreCase = true)) return@forEach
            if (key.equals("Accept-Language", ignoreCase = true)) return@forEach
            connection.setRequestProperty(key, entry.value.joinToString(","))
        }

        // ★ 봇 차단 회피: 실제 브라우저 UA 강제
        // ★ 쿠키 + SAPISIDHASH + visitorData 완벽 적용
        CookieUtil.apply(connection)

        // ★★★ ANDROID_VR 클라이언트 강제 (헤더 + 바디 변환) ★★★
        var finalData = dataToSend
        if (url.contains("youtubei/v1/")) {
            // 헤더 강제 (CookieUtil 이후라 덮어쓰기 유지)
            connection.setRequestProperty("X-YouTube-Client-Name", "28")
            connection.setRequestProperty("X-YouTube-Client-Version", "1.62.27")
            connection.setRequestProperty("User-Agent", "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip")
            connection.setRequestProperty("Origin", "https://www.youtube.com")
            // 바디 변환: context.client를 ANDROID_VR로
            if (dataToSend != null) {
                try {
                    val bodyStr = String(dataToSend, Charsets.UTF_8)
                    if (bodyStr.contains("\"client\"")) {
                        val json = org.json.JSONObject(bodyStr)
                        val context = json.optJSONObject("context") ?: org.json.JSONObject()
                        val client = context.optJSONObject("client") ?: org.json.JSONObject()
                        client.put("clientName", "ANDROID_VR")
                        client.put("clientVersion", "1.62.27")
                        client.put("androidSdkVersion", 30)
                        client.put("osName", "Android")
                        client.put("osVersion", "12")
                        context.put("client", client)
                        json.put("context", context)
                        finalData = json.toString().toByteArray(Charsets.UTF_8)
                        android.util.Log.d("DownloaderImpl", "ANDROID_VR 변환: ${url.take(50)}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DownloaderImpl", "body 변환 실패", e)
                }
            }
        }

        if (finalData != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(finalData) }
        }

        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage

        if (responseCode == 429) {
            throw ReCaptchaException("reCaptcha challenge requested", url)
        }

        val inputStream = if (responseCode in 200..299)
            connection.inputStream
        else
            connection.errorStream

        val responseBody = inputStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

        val latestUrl = connection.url.toString()

        // ★ 핵심: Map<String, List<String>> 형태로 변환
        val responseHeaders = mutableMapOf<String, List<String>>()
        connection.headerFields?.forEach { entry ->
            val key = entry.key
            if (key != null) {
                responseHeaders[key] = entry.value ?: emptyList()
            }
        }

        connection.disconnect()

        return Response(
            responseCode,
            responseMessage,
            responseHeaders,
            responseBody,
            latestUrl
        )
    }
}
