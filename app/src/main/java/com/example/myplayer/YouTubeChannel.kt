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

    // 채널 "동영상" 탭 파라미터
    private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

    /** 채널 정보 + 첫 영상 페이지 */
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
                    put("params", VIDEOS_TAB_PARAMS)
                }
                val response = post(BROWSE, body.toString())
                    ?: return@withContext Pair(null, ChannelVideosPage(emptyList(), null))

                val json = JSONObject(response)
                info = parseHeader(json, channelId)
                collectVideos(json, videos)
                cont = findContinuation(json)
                Log.d(TAG, "channel $channelId -> info=${info?.name}, videos=${videos.size}")
            } catch (e: Exception) {
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
        if (code !in 200..299) return null
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""
    }

    private fun parseHeader(root: JSONObject, channelId: String): ChannelInfo? {
        // 페이지 헤더 위치가 여러 가능성
        val header = root.optJSONObject("header")
            ?: root.optJSONObject("headerRenderer")

        // 1) c4TabbedHeaderRenderer (데스크톱)
        header?.optJSONObject("c4TabbedHeaderRenderer")?.let { c4 ->
            val name = extractText(c4.optJSONObject("title"))
            val avatar = c4.optJSONObject("avatar")
                ?.optJSONObject("thumbnails")
                ?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""
            val subs = extractText(c4.optJSONObject("subscriberCountText"))
            return ChannelInfo(channelId, name, avatar, subs)
        }

        // 2) pageHeaderRenderer (신규)
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
                ?.optJSONArray("metadataRows")
                ?.optJSONObject(0)
                ?.optJSONArray("metadataParts")
                ?.optJSONObject(0)
                ?.optJSONObject("text")
                ?.optString("content") ?: ""
            return ChannelInfo(channelId, name, avatar, subs)
        }

        return null
    }

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
                // richItemRenderer.content.videoRenderer
                node.optJSONObject("richItemRenderer")?.let { ri ->
                    ri.optJSONObject("content")?.optJSONObject("videoRenderer")?.let {
                        parseVideo(it)?.let { v -> out.add(v) }
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collectVideos(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectVideos(node.opt(i), out)
        }
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
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
        val thumbnail = v.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(v.optJSONObject("lengthText"))
        val viewCount = extractText(v.optJSONObject("viewCountText"))
            .ifBlank { extractText(v.optJSONObject("shortViewCountText")) }
        val uploadDate = extractText(v.optJSONObject("publishedTimeText"))
        return VideoItem(videoId, title, channel, thumbnail, duration, viewCount, uploadDate)
    }
}
