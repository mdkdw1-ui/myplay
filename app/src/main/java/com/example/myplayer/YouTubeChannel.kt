package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChannelInfo(
    val channelId: String,
    val name: String,
    val avatar: String,
    val subscribers: String,
    val videoCount: String = "",
    val description: String = ""
)

data class ChannelVideosPage(
    val videos: List<VideoItem>,
    val continuation: String?
)

object YouTubeChannel {

    private const val TAG = "YouTubeChannel"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val BROWSE = "https://www.youtube.com/youtubei/v1/browse"

    var lastDebug: String = ""

    suspend fun fetch(
        channelId: String,
        channelName: String = ""
    ): Pair<ChannelInfo?, ChannelVideosPage> = withContext(Dispatchers.IO) {
        // Piped/Invidious로 채널 정보 및 첫 페이지 가져오기
        val page = YouTubeApiHelper.channelVideos(channelId)
        if (page.videos.isNotEmpty()) {
            return@withContext Pair(null, page)
        }
        Pair(null, ChannelVideosPage(emptyList(), null))
    }

    /** 다음 페이지 (Piped nextpage 또는 Invidious continuation) */
    suspend fun fetchMore(continuation: String): ChannelVideosPage = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchMore: token=${continuation.take(60)}")

        // ─────────────────────────────────────
        // 1. Piped nextpage 토큰 ("piped:<token>" or "piped:<channelId>|<token>")
        // ─────────────────────────────────────
        if (continuation.startsWith("piped:")) {
            val rest = continuation.removePrefix("piped:")
            val channelId = if (rest.contains("|")) rest.substringBefore("|") else ""
            val nextpage = if (rest.contains("|")) rest.substringAfter("|") else rest

            for (inst in YouTubeApiHelper.PIPED) {
                try {
                    val url = if (channelId.isNotBlank() && nextpage.isNotBlank()) {
                        "$inst/nextpage/channel/$channelId?nextpage=${java.net.URLEncoder.encode(nextpage, "UTF-8")}"
                    } else if (channelId.isNotBlank()) {
                        "$inst/nextpage/channel/$channelId"
                    } else if (nextpage.isNotBlank()) {
                        "$inst/nextpage?nextpage=${java.net.URLEncoder.encode(nextpage, "UTF-8")}"
                    } else {
                        continue
                    }

                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode !in 200..299) continue

                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = org.json.JSONObject(jsonStr)

                    val out = mutableListOf<VideoItem>()
                    val streams = obj.optJSONArray("relatedStreams")
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val v = streams.getJSONObject(i)
                            val id = v.optString("url", "").substringAfter("/watch?v=", "").substringBefore("&")
                            if (id.isBlank()) continue
                            out.add(VideoItem(
                                videoId = id,
                                title = v.optString("title", ""),
                                channel = v.optString("uploaderName", ""),
                                thumbnail = v.optString("thumbnail", ""),
                                duration = YouTubeApiHelper.fmtSec(v.optInt("duration", 0))
                            ))
                        }
                    }
                    if (out.isNotEmpty()) {
                        var next = obj.optString("nextpage", "")
                        if (next.isBlank()) next = nextpage
                        Log.d(TAG, "Piped fetchMore 성공: ${out.size}개, next=${next.take(30)}")
                        return@withContext ChannelVideosPage(
                            out,
                            if (next.isNotBlank()) "piped:$next" else null
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Piped fetchMore err: ${e.message}")
                }
            }
        }

        // ─────────────────────────────────────
        // 2. Invidious continuation 토큰 ("inv:<channelId>|<token>")
        // ─────────────────────────────────────
        if (continuation.startsWith("inv:")) {
            val rest = continuation.removePrefix("inv:")
            val idx = rest.indexOf('|')
            if (idx > 0) {
                val channelId = rest.substring(0, idx)
                val contToken = rest.substring(idx + 1)
                Log.d(TAG, "Invidious fetchMore: channelId=$channelId")
                return@withContext YouTubeApiHelper.channelVideos(channelId, contToken)
            }
        }

        // ─────────────────────────────────────
        // 3. 그 외(또는 폴백): 빈 결과
        // ─────────────────────────────────────
        Log.d(TAG, "fetchMore: 알 수 없는 토큰 형식 - $continuation")
        ChannelVideosPage(emptyList(), null)
    }


    private fun post(urlStr: String, bodyStr: String): String? {
        val fullUrl = "$urlStr?key=$API_KEY&prettyPrint=false"
        // ★ IPv4 강제
        val conn = try {
            val parsedUrl = java.net.URL(fullUrl)
            val host = parsedUrl.host
            val port = if (parsedUrl.port > 0) parsedUrl.port
                       else if (parsedUrl.protocol == "https") 443 else 80
            val ipv4 = java.net.InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()
            if (ipv4 != null) {
                val newUrl = java.net.URL(parsedUrl.protocol, ipv4.hostAddress, port, parsedUrl.file)
                val c = newUrl.openConnection() as HttpURLConnection
                c.setRequestProperty("Host", host)
                c
            } else {
                java.net.URL(fullUrl).openConnection() as HttpURLConnection
            }
        } catch (e: Exception) {
            java.net.URL(fullUrl).openConnection() as HttpURLConnection
        }
            CookieUtil.apply(conn)
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Origin", "https://www.youtube.com")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) return null
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
    }

    private fun parseHeader(root: JSONObject, channelId: String): ChannelInfo? {
        val header = root.optJSONObject("header") ?: root.optJSONObject("headerRenderer")

        header?.optJSONObject("c4TabbedHeaderRenderer")?.let { c4 ->
            val name = extractText(c4.optJSONObject("title"))
            val avatar = c4.optJSONObject("avatar")?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
            val subs = extractText(c4.optJSONObject("subscriberCountText"))
            return ChannelInfo(channelId, name, avatar, subs)
        }

        header?.optJSONObject("pageHeaderRenderer")?.let { ph ->
            val vm = ph.optJSONObject("pageHeaderViewModel") ?: return@let
            val name = extractText(
                vm.optJSONObject("title")?.optJSONObject("dynamicTextViewModel")
                    ?.optJSONObject("text")
            )
            val avatar = vm.optJSONObject("image")
                ?.optJSONObject("decoratedAvatarViewModel")
                ?.optJSONObject("avatar")
                ?.optJSONObject("avatarViewModel")
                ?.optJSONObject("image")
                ?.optJSONArray("sources")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
            val subs = vm.optJSONObject("metadata")
                ?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")?.optJSONObject(0)
                ?.optJSONArray("metadataParts")?.optJSONObject(0)
                ?.optJSONObject("text")?.optString("content") ?: ""
            return ChannelInfo(channelId, name, avatar, subs)
        }

        root.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")?.let { cm ->
            val name = cm.optString("title")
            val avatar = cm.optJSONObject("avatar")
                ?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
                ?: cm.optJSONObject("avatar")
                    ?.optJSONArray("thumbnails")
                    ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
            val desc = cm.optString("description")
            return ChannelInfo(channelId, name, avatar, "", "", desc)
        }

        return null
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                node.optJSONObject("gridVideoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                node.optJSONObject("richItemRenderer")?.let { ri ->
                    ri.optJSONObject("content")?.let { collectVideos(it, out) }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "videoRenderer" || key == "gridVideoRenderer" || key == "richItemRenderer") continue
                    collectVideos(node.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out)
        }
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")?.optString("token")
                    if (!token.isNullOrEmpty()) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val r = findContinuation(node.opt(keys.next()))
                    if (r != null) return r
                }
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val r = findContinuation(node.opt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun parseVideo(v: JSONObject): VideoItem? {
        val videoId = v.optString("videoId").takeIf { it.isNotEmpty() } ?: return null
        val title = extractText(v.optJSONObject("title"))
        val channel = extractText(v.optJSONObject("ownerText"))
            .ifBlank { extractText(v.optJSONObject("longBylineText")) }
            .ifBlank { extractText(v.optJSONObject("shortBylineText")) }
        val thumbnail = v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(v.optJSONObject("lengthText"))
        val viewCount = extractText(v.optJSONObject("viewCountText"))
            .ifBlank { extractText(v.optJSONObject("shortViewCountText")) }
        val uploadDate = extractText(v.optJSONObject("publishedTimeText"))
        return VideoItem(videoId, title, channel, thumbnail, duration, viewCount, uploadDate)
    }
}
