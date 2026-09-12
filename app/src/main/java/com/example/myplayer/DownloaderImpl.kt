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
            connection.setRequestProperty(entry.key, entry.value.joinToString(","))
        }

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
