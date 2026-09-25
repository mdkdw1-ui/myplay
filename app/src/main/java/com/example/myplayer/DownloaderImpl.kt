package com.example.myplayer

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class DownloaderImpl : Downloader() {

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val connection = URL(url).openConnection() as HttpURLConnection
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
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
        )
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
        connection.setRequestProperty("Accept", "*/*")

        if (dataToSend != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(dataToSend) }
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
