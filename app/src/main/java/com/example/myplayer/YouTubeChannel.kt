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

    suspend fun fetch(channelId: String): Pair<ChannelInfo?, ChannelVideosPage> =
        withContext(Dispatchers.IO) {
            var info: ChannelInfo? = null
            val videos = mutableListOf<VideoItem>()
            var cont: String? = null
            try {
                val body = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "WEB")
                            put("clientVersion", "2.20240101.00.00")
                            put("hl", "ko")
                            put("gl", "KR")
                        })
                    })
                    put("browseId", channelId)
                }
                val response = post(BROWSE, body.toString())
                if (response == null) {
                    lastDebug = "HTTP fail"
                    return@withContext Pair(null, ChannelVideosPage(emptyList(), null))
                }

                val json = JSONObject(response)
                info = parseHeader(json, channelId)
                collectVideos(json, videos)
                cont = findContinuation(json)
                lastDebug = "info=${info?.name ?: "null"}, videos=${videos.size}, cont=${cont != null}"

                // ★ 초기 응답에 영상이 없고 continuation이 있으면 즉시 로드
                if (videos.isEmpty() && cont != null) {
                    Log.d(TAG, "initial empty, following continuation")
                    val page2 = fetchMore(cont)
                    videos.addAll(page2.videos)
                    cont = page2.continuation
                    lastDebug += " → +${page2.videos.size} after cont"
                }

                Log.d(TAG, lastDebug)
            } catch (e: Exception) {
                lastDebug = "err: ${e.message}"
                Log.e(TAG, "err: ${e.message}", e)
            }
            Pair(info, ChannelVideosPage(videos, cont))
        }

    suspend fun fetchMore(continuation: String): ChannelVideosPage = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoItem>()
        var cont: String? = null
        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("continuation", continuation)
            }
            val response = post(BROWSE, body.toString())
                ?: return@withContext ChannelVideosPage(emptyList(), null)
            val json = JSONObject(response)
            collectVideos(json, videos)
            cont = findContinuation(json)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        ChannelVideosPage(videos, cont)
    }

    private fun post(urlStr: String, bodyStr: String): String? {
        val url = URL("$urlStr?key=$API_KEY&prettyPrint=false")
        val conn = url.openConnection() as HttpURLConnection
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
        if (code !in 200..299) {
            Log.e(TAG, "HTTP $code")
            return null
        }
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
    }

    private fun parseHeader(root: JSONObject, channelId: String): ChannelInfo? {
        val header = root.optJSONObject("header")
            ?: root.optJSONObject("headerRenderer")

        // 1) c4TabbedHeaderRenderer
        header?.optJSONObject("c4TabbedHeaderRenderer")?.let { c4 ->
            val name = extractText(c4.optJSONObject("title"))
            val avatar = c4.optJSONObject("avatar")?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
            val subs = extractText(c4.optJSONObject("subscriberCountText"))
            return ChannelInfo(channelId, name, avatar, subs)
        }

        // 2) pageHeaderRenderer
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

        // 3) metadata.channelMetadataRenderer (폴백)
        root.optJSONObject("metadata")
            ?.optJSONObject("channelMetadataRenderer")?.let { cm ->
                val name = cm.optString("title")
                val avatar = cm.optJSONObject("avatar")
                    ?.optJSONObject("thumbnails")
                    ?.optJSONArray("thumbnails")
                    ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
                    ?: cm.optJSONObject("avatar")
                        ?.optJSONArray("thumbnails")
                        ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
                    ?: ""
                val desc = cm.optString("description")
                return ChannelInfo(channelId, name, avatar, "", "", desc)
            }

        return null
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let {
                    parseVideo(it)?.let { v -> out.add(v) }
                }
                node.optJSONObject("gridVideoRenderer")?.let {
                    parseVideo(it)?.let { v -> out.add(v) }
                }
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
                // continuationItemRenderer는 array 안에 있기도 함
                node.optJSONArray("continuationItemRenderer")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val token = arr.optJSONObject(i)
                            ?.optJSONObject("continuationEndpoint")
                            ?.optJSONObject("continuationCommand")
                            ?.optString("token")
                        if (!token.isNullOrEmpty()) return token
                    }
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
