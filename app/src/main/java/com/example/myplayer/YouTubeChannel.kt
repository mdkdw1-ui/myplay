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

    // ★ "동영상" 탭 params (2026년 검증됨)
    private const val VIDEOS_TAB_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val CLIENT_VERSION = "2.20250101.00.00"

    var lastDebug: String = ""

    // ─────────────────────────────────────────
    // 채널 첫 페이지 (동영상 탭)
    // ─────────────────────────────────────────
    suspend fun fetch(
        channelId: String,
        channelName: String = ""
    ): Pair<ChannelInfo?, ChannelVideosPage> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", CLIENT_VERSION)
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("browseId", channelId)
                put("params", VIDEOS_TAB_PARAMS)
            }

            val response = post(BROWSE, body.toString()) ?: return@withContext Pair(
                null, ChannelVideosPage(emptyList(), null)
            )

            val json = JSONObject(response)

            // 채널 정보 추출
            val metadata = json.optJSONObject("metadata")
                ?.optJSONObject("channelMetadataRenderer")
            val name = metadata?.optString("title") ?: channelName
            val avatar = metadata?.optJSONObject("avatar")
                ?.optJSONArray("thumbnails")
                ?.let { it.optJSONObject(it.length() - 1)?.optString("url") } ?: ""

            val chInfo = ChannelInfo(
                channelId = channelId,
                name = name,
                avatar = avatar,
                subscribers = "",
                videoCount = ""
            )

            // 영상 + continuation 추출
            val videos = mutableListOf<VideoItem>()
            extractVideos(json, videos)
            val continuation = findContinuation(json)

            lastDebug = "fetch: videos=${videos.size}, cont=${continuation?.take(20)}"
            Log.d(TAG, lastDebug)

            Pair(chInfo, ChannelVideosPage(videos, continuation))
        } catch (e: Exception) {
            Log.e(TAG, "fetch err: ${e.message}", e)
            Pair(null, ChannelVideosPage(emptyList(), null))
        }
    }

    // ─────────────────────────────────────────
    // 다음 페이지
    // ─────────────────────────────────────────
    suspend fun fetchMore(continuation: String): ChannelVideosPage = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", CLIENT_VERSION)
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("continuation", continuation)
            }

            val response = post(BROWSE, body.toString())
                ?: return@withContext ChannelVideosPage(emptyList(), null)

            val json = JSONObject(response)
            val videos = mutableListOf<VideoItem>()
            extractVideos(json, videos)
            val next = findContinuation(json)

            lastDebug = "fetchMore: videos=${videos.size}, next=${next?.take(20)}"
            Log.d(TAG, lastDebug)

            ChannelVideosPage(videos, next)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMore err: ${e.message}", e)
            ChannelVideosPage(emptyList(), null)
        }
    }

    // ─────────────────────────────────────────
    // 영상 추출 (videoRenderer + lockupViewModel 모두)
    // ─────────────────────────────────────────
    private fun extractVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                // 1) videoRenderer (구형)
                node.optJSONObject("videoRenderer")?.let { v ->
                    val vid = v.optString("videoId", "")
                    if (vid.isNotBlank() && out.none { it.videoId == vid }) {
                        val title = v.optJSONObject("title")?.let { t ->
                            t.optString("simpleText").ifBlank {
                                t.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                            }
                        } ?: ""
                        val channel = v.optJSONObject("ownerText")?.let { o ->
                            o.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "") ?: ""
                        } ?: ""
                        val thumb = v.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                            ?.let { it.optJSONObject(it.length() - 1)?.optString("url", "") } ?: ""
                        val dur = v.optJSONObject("lengthText")?.optString("simpleText", "") ?: ""
                        out.add(VideoItem(vid, title, channel, thumb, dur))
                    }
                }

                // 2) gridVideoRenderer (구형)
                node.optJSONObject("gridVideoRenderer")?.let { v ->
                    val vid = v.optString("videoId", "")
                    if (vid.isNotBlank() && out.none { it.videoId == vid }) {
                        val title = v.optJSONObject("title")?.optString("simpleText", "") ?: ""
                        out.add(VideoItem(vid, title, "", "https://i.ytimg.com/vi/$vid/mqdefault.jpg"))
                    }
                }

                // 3) lockupViewModel (2026년 신형)
                node.optJSONObject("lockupViewModel")?.let { lv ->
                    val vid = lv.optString("contentId", "")
                    if (vid.length == 11 && out.none { it.videoId == vid }) {
                        val meta = lv.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        val title = meta?.optJSONObject("title")?.optString("content", "") ?: ""
                        val channel = meta?.optJSONObject("metadata")
                            ?.optJSONObject("contentMetadataViewModel")
                            ?.optJSONArray("metadataRows")?.optJSONObject(0)
                            ?.optJSONArray("metadataParts")?.optJSONObject(0)
                            ?.optJSONObject("text")?.optString("content", "") ?: ""
                        val thumb = lv.optJSONObject("contentImage")
                            ?.optJSONObject("thumbnailViewModel")
                            ?.optJSONObject("image")
                            ?.optJSONArray("sources")
                            ?.let { it.optJSONObject(it.length() - 1)?.optString("url", "") }
                            ?: "https://i.ytimg.com/vi/$vid/mqdefault.jpg"
                        out.add(VideoItem(vid, title, channel, thumb))
                    }
                }

                // 재귀
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractVideos(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) extractVideos(node.opt(i), out)
        }
    }

    // ─────────────────────────────────────────
    // continuation 추출
    // ─────────────────────────────────────────
    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank()) return token
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

    private fun post(urlStr: String, bodyStr: String): String? {
        val fullUrl = "$urlStr?key=$API_KEY&prettyPrint=false"
        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
        conn.setRequestProperty("Origin", "https://www.youtube.com")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.outputStream.use { it.write(bodyStr.toByteArray()) }
        val code = conn.responseCode
        Log.d(TAG, "HTTP $code")
        if (code !in 200..299) return null
        return conn.inputStream.bufferedReader().use(BufferedReader::readText)
    }
}
