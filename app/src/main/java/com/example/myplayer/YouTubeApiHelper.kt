package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object YouTubeApiHelper {

    /** JSON 응답 내부를 재귀 탐색하여 nextpage/continuation 토큰을 찾습니다 */
    private fun findContinuationDeep(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val direct = node.optString("nextpage", "")
                    .ifBlank { node.optString("continuation", "") }
                if (direct.isNotBlank() && !direct.startsWith("{")) return direct
                
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val token = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank() && !token.startsWith("{")) return token
                }
                
                val keys = node.keys()
                while (keys.hasNext()) {
                    val result = findContinuationDeep(node.opt(keys.next()))
                    if (result != null) return result
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val result = findContinuationDeep(node.opt(i))
                    if (result != null) return result
                }
            }
        }
        return null
    }


    /** WebP 썸네일 주소를 Glide가 읽을 수 있는 JPG 주소로 변환 */
    fun normalizeThumb(url: String, videoId: String): String {
        if (url.isBlank()) return "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        return url.replace("/vi_webp/", "/vi/").replace(".webp", ".jpg")
    }

    private const val TAG = "YtApi"

    val INVIDIOUS = listOf(
        "https://invidious.nerdvpn.de",         // ✅ 200 (현재 살아있음)
        "https://yewtu.be",                     // ✅ 200 (현재 살아있음)
        "https://invidious.f5.si",              // ⚠️ 530 (다운 상태, 뒤로)
        "https://invidious.privacyredirect.com",
        "https://invidious.jing.rocks",
        "https://iv.datura.network",
        "https://invidious.einfachzocken.eu",
        "https://iv.melmac.space",
        "https://invidious.reallyaweso.me"
    )

    val PIPED = listOf(
        "https://api.piped.private.coffee",     // ⚠️ 봇 차단 가능
        "https://pipedapi.adminforge.de",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.drgns.space",
        "https://pipedapi.ducks.party"
    )

    // ═══════════════════════════════════════════════
    // HTTP (raw)
    // ═══════════════════════════════════════════════
    private suspend fun getRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            // ★ 첫 로딩 단축: 3초/5초로 축소
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "application/json")
            CookieUtil.apply(conn)
            if (conn.responseCode !in 200..299) {
                Log.d(TAG, "HTTP ${conn.responseCode} $url")
                return@withContext null
            }
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (e: Exception) {
            Log.d(TAG, "err: ${e.message?.take(80)}")
            null
        }
    }

    private suspend fun getJsonObject(url: String): JSONObject? {
        val t = getRaw(url) ?: return null
        return try { JSONObject(t) } catch (e: Exception) { null }
    }

    private suspend fun getJsonArray(url: String): JSONArray? {
        val t = getRaw(url) ?: return null
        return try { JSONArray(t) } catch (e: Exception) { null }
    }

    // ═══════════════════════════════════════════════
    // 검색
    // ═══════════════════════════════════════════════
    data class SearchResultPage(
        val videos: List<VideoItem>,
        val continuation: String?
    )

    /** 하위 호환용 (첫 페이지만) */
    suspend fun searchVideos(query: String): List<VideoItem> =
        searchVideosPage(query, null).videos

    /** 페이지네이션 지원 검색 */
    suspend fun searchVideosPage(
        query: String,
        continuation: String? = null
    ): SearchResultPage = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        // ★ 살아있는 서버만 사용
        val livePiped = try { ServerHealthChecker.getAlivePiped(MyApp.instance) } catch (e: Exception) { PIPED }
        val liveInvidious = try { ServerHealthChecker.getAliveInvidious(MyApp.instance) } catch (e: Exception) { INVIDIOUS }

        // ────────────────────────────────────
        // 이어받기 (continuation 기반)
        // ────────────────────────────────────
        if (!continuation.isNullOrBlank()) {
            // Piped nextpage
            if (continuation.startsWith("piped:")) {
                val nextpage = continuation.removePrefix("piped:")
                for (inst in PIPED) {
                    val url = "$inst/nextpage/search?q=$q&filter=videos&nextpage=${URLEncoder.encode(nextpage, "UTF-8")}"
                    val obj = getJsonObject(url) ?: continue
                    try {
                        val items = obj.optJSONArray("items") ?: continue
                        val out = mutableListOf<VideoItem>()
                        for (i in 0 until items.length()) {
                            val v = items.optJSONObject(i) ?: continue
                            val u = v.optString("url", "")
                            val id = u.substringAfter("/watch?v=", "").substringBefore("&")
                            if (id.isBlank()) continue
                            out.add(VideoItem(
                                videoId = id,
                                title = v.optString("title", ""),
                                channel = v.optString("uploaderName", ""),
                                thumbnail = normalizeThumb(v.optString("thumbnail", ""), v.optString("videoId", "")),
                                duration = fmtSec(v.optInt("duration", 0)),
                                viewCount = "",
                                uploadDate = v.optString("uploadedDate", "")
                            ))
                        }
                        val next = obj.optString("nextpage", "").takeIf { it.isNotBlank() }
                            ?.let { "piped:$it" }
                        if (out.isNotEmpty()) {
                            Log.d(TAG, "piped search more: ${out.size}개")
                            return@withContext SearchResultPage(out, next)
                        }
                    } catch (e: Exception) { }
                }
            }

            // Invidious page
            if (continuation.startsWith("inv:")) {
                val page = continuation.removePrefix("inv:").toIntOrNull() ?: 2
                for (inst in INVIDIOUS) {
                    val arr = getJsonArray("$inst/api/v1/search?q=$q&type=video&page=$page") ?: continue
                    try {
                        val out = mutableListOf<VideoItem>()
                        for (i in 0 until arr.length()) {
                            val v = arr.optJSONObject(i) ?: continue
                            val id = v.optString("videoId", "")
                            if (id.isBlank()) continue
                            out.add(VideoItem(
                                videoId = id,
                                title = v.optString("title", ""),
                                channel = v.optString("author", ""),
                                thumbnail = normalizeThumb(v.optString("videoThumbnails", ""), v.optString("videoId", "")),
                                duration = fmtSec(v.optInt("lengthSeconds", 0)),
                                viewCount = fmtViews(v.optLong("viewCount", 0)),
                                uploadDate = ""
                            ))
                        }
                        val next = if (out.size >= 15) "inv:${page + 1}" else null
                        if (out.isNotEmpty()) {
                            Log.d(TAG, "inv search page=$page: ${out.size}개, next=$next")
                            return@withContext SearchResultPage(out, next)
                        }
                    } catch (e: Exception) { }
                }
            }
            return@withContext SearchResultPage(emptyList(), null)
        }

        // ────────────────────────────────────
        // 첫 페이지
        // ────────────────────────────────────

        // Piped 우선
        for (inst in PIPED) {
            val obj = getJsonObject("$inst/search?q=$q&filter=videos") ?: continue
            try {
                val items = obj.optJSONArray("items") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until items.length()) {
                    val v = items.optJSONObject(i) ?: continue
                    val u = v.optString("url", "")
                    val id = u.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = normalizeThumb(v.optString("thumbnail", ""), v.optString("videoId", "")),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = v.optString("uploadedDate", "")
                    ))
                }
                val next = obj.optString("nextpage", "").takeIf { it.isNotBlank() }
                    ?.let { "piped:$it" }
                if (out.isNotEmpty()) {
                    Log.d(TAG, "piped search: ${out.size}개, next=${next?.take(40)}")
                    return@withContext SearchResultPage(out, next)
                }
            } catch (e: Exception) { }
        }

        // Invidious 폴백
        for (inst in INVIDIOUS) {
            val arr = getJsonArray("$inst/api/v1/search?q=$q&type=video") ?: continue
            try {
                val out = mutableListOf<VideoItem>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val id = v.optString("videoId", "")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = normalizeThumb(v.optString("videoThumbnails", ""), v.optString("videoId", "")),
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }
                val next = if (out.size >= 15) "inv:2" else null
                if (out.isNotEmpty()) {
                    Log.d(TAG, "inv search: ${out.size}개, next=$next")
                    return@withContext SearchResultPage(out, next)
                }
            } catch (e: Exception) { }
        }

        SearchResultPage(emptyList(), null)
    }

    
    suspend fun searchChannels(query: String): List<ChannelItem> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")

        for (inst in INVIDIOUS) {
            val arr = getJsonArray("$inst/api/v1/search?q=$q&type=channel") ?: continue
            try {
                val out = mutableListOf<ChannelItem>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val id = v.optString("authorId", "")
                    if (id.isBlank()) continue
                    out.add(ChannelItem(
                        channelId = id,
                        name = v.optString("author", ""),
                        thumbnail = v.optString("authorThumbnails", ""),
                        subscribers = fmtNum(v.optLong("subCount", 0)) + "명"
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        for (inst in PIPED) {
            val obj = getJsonObject("$inst/search?q=$q&filter=channels") ?: continue
            try {
                val items = obj.optJSONArray("items") ?: continue
                val out = mutableListOf<ChannelItem>()
                for (i in 0 until items.length()) {
                    val v = items.optJSONObject(i) ?: continue
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/channel/", "")
                    if (id.isBlank()) continue
                    out.add(ChannelItem(
                        channelId = id,
                        name = v.optString("name", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        subscribers = v.optString("subscribers", "")
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        emptyList()
    }

    // ═══════════════════════════════════════════════
    // 채널 영상
    // ═══════════════════════════════════════════════
    /** ★ 채널명 or ID → 실제 채널 ID */
    suspend fun resolveChannelId(nameOrId: String): String? = withContext(Dispatchers.IO) {
        if (nameOrId.startsWith("UC") && nameOrId.length > 20) return@withContext nameOrId
        val q = URLEncoder.encode(nameOrId, "UTF-8")
        // Piped 검색으로 채널 ID 찾기
        for (inst in PIPED) {
            val obj = getJsonObject("$inst/search?q=$q&filter=channels") ?: continue
            try {
                val items = obj.optJSONArray("items") ?: continue
                if (items.length() > 0) {
                    val first = items.optJSONObject(0) ?: continue
                    val url = first.optString("url", "")
                    val id = url.substringAfter("/channel/", "")
                    if (id.startsWith("UC")) return@withContext id
                }
            } catch (e: Exception) { }
        }
        // Invidious
        for (inst in INVIDIOUS) {
            val arr = getJsonArray("$inst/api/v1/search?q=$q&type=channel") ?: continue
            try {
                if (arr.length() > 0) {
                    val first = arr.optJSONObject(0) ?: continue
                    val id = first.optString("authorId", "")
                    if (id.startsWith("UC")) return@withContext id
                }
            } catch (e: Exception) { }
        }
        null
    }

    suspend fun channelVideos(
        channelId: String,
        continuation: String? = null
    ): ChannelVideosPage = withContext(Dispatchers.IO) {
        val realId = if (channelId.startsWith("UC") && channelId.length > 20) {
            channelId
        } else {
            resolveChannelId(channelId) ?: channelId
        }
        Log.d(TAG, "channelVideos: realId=$realId cont=${continuation?.take(30)}")

        for (inst in INVIDIOUS) {
            val url = if (continuation.isNullOrBlank()) {
                "$inst/api/v1/channels/$realId/videos"
            } else {
                "$inst/api/v1/channels/$realId/videos?continuation=$continuation"
            }
            val obj = getJsonObject(url) ?: continue
            try {
                val videos = obj.optJSONArray("videos") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    out.add(VideoItem(
                        videoId = v.optString("videoId", ""),
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = normalizeThumb(v.optString("videoThumbnails", ""), v.optString("videoId", "")),
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }

                val nextCont = obj.optString("continuation", "")
                    .takeIf { it.isNotBlank() }
                val nextToken = nextCont?.let { "inv:$realId|$it" }
                android.util.Log.d(TAG, "INV 채널 응답: ${out.size}개, raw continuation='${nextCont?.take(40)}' (${nextCont?.length ?: 0}자)")

                if (out.isNotEmpty()) {
                    Log.d(TAG, "inv channel: ${out.size}개, next=${nextToken?.take(60)}")
                    return@withContext ChannelVideosPage(out, nextToken)
                }
            } catch (e: Exception) {
                Log.e(TAG, "inv channel err: ${e.message}", e)
            }
        }

        if (continuation.isNullOrBlank()) {
            for (inst in PIPED) {
                val obj = getJsonObject("$inst/channel/$realId") ?: continue
                try {
                    val rel = obj.optJSONArray("relatedStreams") ?: continue
                    val out = mutableListOf<VideoItem>()
                    for (i in 0 until rel.length()) {
                        val v = rel.getJSONObject(i)
                        val url = v.optString("url", "")
                        val id = url.substringAfter("/watch?v=", "").substringBefore("&")
                        if (id.isBlank()) continue
                        out.add(VideoItem(
                            videoId = id,
                            title = v.optString("title", ""),
                            channel = v.optString("uploaderName", ""),
                            thumbnail = normalizeThumb(v.optString("thumbnail", ""), v.optString("videoId", "")),
                            duration = fmtSec(v.optInt("duration", 0)),
                            viewCount = "",
                            uploadDate = ""
                        ))
                    }
                    if (out.isNotEmpty()) {
                        val nextpage = obj.optString("nextpage", "").takeIf { it.isNotBlank() }
                        Log.d(TAG, "piped channel: ${out.size}개")
                        return@withContext ChannelVideosPage(out, nextpage)
                    }
                } catch (e: Exception) { }
            }
        }

        // ★ [piped_fallback_from_inv] Invidious가 토큰을 안 줬으면 Piped로 우회
        // (Invidious가 성공했고 토큰도 있으면 이 블록은 실행 안 됨)
        Log.d(TAG, "Invidious 토큰 없음 → Piped 채널 시도")
        for (inst in PIPED) {
            val url = if (continuation.isNullOrBlank())
                "$inst/channel/$realId"
            else
                "$inst/nextpage/channel/$realId?nextpage=${URLEncoder.encode(continuation, "UTF-8")}"
            
            val obj = getJsonObject(url) ?: continue
            try {
                val rel = obj.optJSONArray("relatedStreams") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rel.length()) {
                    val v = rel.getJSONObject(i)
                    val u = v.optString("url", "")
                    val id = u.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = v.optString("thumbnail", ""),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) {
                    var nextToken = obj.optString("nextpage", "")
                    if (nextToken.isBlank()) {
                        nextToken = findContinuationDeep(obj) ?: ""
                    }
                    Log.d(TAG, "Piped 채널 성공: ${out.size}개, next=${nextToken.take(40)}")
                    return@withContext ChannelVideosPage(out, nextToken.takeIf { it.isNotBlank() })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Piped 채널 오류: ${e.message}")
            }
        }

        ChannelVideosPage(emptyList(), null)
    }

    // ═══════════════════════════════════════════════
    // 관련 영상
    // ═══════════════════════════════════════════════
    suspend fun related(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        for (inst in INVIDIOUS) {
            val obj = getJsonObject("$inst/api/v1/videos/$videoId") ?: continue
            try {
                val rec = obj.optJSONArray("recommendedVideos") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rec.length()) {
                    val v = rec.getJSONObject(i)
                    out.add(VideoItem(
                        videoId = v.optString("videoId", ""),
                        title = v.optString("title", ""),
                        channel = v.optString("author", ""),
                        thumbnail = normalizeThumb(v.optString("videoThumbnails", ""), v.optString("videoId", "")),
                        duration = fmtSec(v.optInt("lengthSeconds", 0)),
                        viewCount = fmtViews(v.optLong("viewCount", 0)),
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        for (inst in PIPED) {
            val obj = getJsonObject("$inst/streams/$videoId") ?: continue
            try {
                val rel = obj.optJSONArray("relatedStreams") ?: continue
                val out = mutableListOf<VideoItem>()
                for (i in 0 until rel.length()) {
                    val v = rel.getJSONObject(i)
                    val url = v.optString("url", "")
                    val id = url.substringAfter("/watch?v=", "").substringBefore("&")
                    if (id.isBlank()) continue
                    out.add(VideoItem(
                        videoId = id,
                        title = v.optString("title", ""),
                        channel = v.optString("uploaderName", ""),
                        thumbnail = normalizeThumb(v.optString("thumbnail", ""), v.optString("videoId", "")),
                        duration = fmtSec(v.optInt("duration", 0)),
                        viewCount = "",
                        uploadDate = ""
                    ))
                }
                if (out.isNotEmpty()) return@withContext out
            } catch (e: Exception) { }
        }

        emptyList()
    }

    // ═══════════════════════════════════════════════
    // ★ 스트림 추출 (NewPipe 우회)
    // ═══════════════════════════════════════════════
    data class StreamUrls(
        val audioUrl: String?,
        val muxedUrl: String?,
        val videoUrl: String?,
        val title: String,
        val channel: String,
        val source: String
    )

    /** ★ itag → height 매핑 (Invidious가 height 안 줄 때) */
    fun itagToHeight(itag: Int): Int = when (itag) {
        160, 394, 278 -> 144
        133, 395, 242 -> 240
        134, 396, 243 -> 360
        135, 397, 244 -> 480
        136, 398, 247 -> 720
        137, 399, 248 -> 1080
        264, 271 -> 1440
        266, 313 -> 2160
        138 -> 4320
        17 -> 144
        18 -> 360
        22 -> 720
        37 -> 1080
        38 -> 3072
        43 -> 360
        44 -> 480
        45 -> 720
        46 -> 1080
        else -> 0
    }

    suspend fun extractStream(videoId: String): StreamUrls = withContext(Dispatchers.IO) {
        // ★ Invidious 5개 서버 시도 (다운된 서버 회피)
        val quickInvidious = INVIDIOUS.take(3)
        for (inst in quickInvidious) {
            val obj = getJsonObject("$inst/api/v1/videos/$videoId") ?: continue
            try {
                if (obj.optBoolean("liveNow", false)) continue
                val title = obj.optString("title", "")
                val author = obj.optString("author", "")

                var audioUrl: String? = null
                var audioBitrate = 0
                var videoUrl: String? = null
                var videoHeight = 0
                var muxed: String? = null

                val formats = obj.optJSONArray("adaptiveFormats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        // ★ type 필드가 없으면 encoding 필드도 확인
                        val type = f.optString("type",
                            f.optString("encoding", ""))
                        val u = f.optString("url", "")
                        if (u.isBlank()) continue
                        val typeLower = type.lowercase()
                        if (typeLower.startsWith("audio/") ||
                            typeLower.contains("audio")) {
                            val br = f.optInt("bitrate",
                                f.optInt("bitrateAvg", 0))
                            if (br > audioBitrate) {
                                audioBitrate = br
                                audioUrl = u
                            }
                        } else if (typeLower.startsWith("video/") ||
                                   typeLower.contains("video") ||
                                   typeLower.contains("mp4")) {
                            // ★ height → resolution → itag 순서로 시도
                            val itag = f.optString("itag", "").toIntOrNull() ?: 0
                            val h = f.optInt("height", 0).takeIf { it > 0 }
                                ?: f.optInt("resolution", 0).takeIf { it > 0 }
                                ?: f.optString("resolution", "").replace("p", "").toIntOrNull()?.takeIf { it > 0 }
                                ?: itagToHeight(itag)
                            if (h > videoHeight) {
                                videoHeight = h
                                videoUrl = u
                            }
                        }
                    }
                }

                // ★ formatStreams (muxed) — 최고 화질 우선
                val fs = obj.optJSONArray("formatStreams")
                if (fs != null && fs.length() > 0) {
                    var bestMuxedH = 0
                    for (i in 0 until fs.length()) {
                        val f = fs.getJSONObject(i)
                        val u = f.optString("url", "")
                        if (u.isBlank()) continue
                        val itag = f.optString("itag", "").toIntOrNull() ?: 0
                        val h = f.optInt("height", 0).takeIf { it > 0 }
                            ?: f.optInt("resolution", 0).takeIf { it > 0 }
                            ?: itagToHeight(itag)
                        if (h >= bestMuxedH) {
                            bestMuxedH = h
                            muxed = u
                        }
                    }
                }

                if (audioUrl != null || videoUrl != null || muxed != null) {
                    Log.d(TAG, "extractStream inv: a=${audioUrl != null} v=${videoUrl != null} m=${muxed != null}")
                    return@withContext StreamUrls(audioUrl, muxed, videoUrl, title, author, "inv")
                }
            } catch (e: Exception) { }
        }

        // ★ Piped 3개 서버 시도
        val quickPiped = PIPED.take(2)
        for (inst in quickPiped) {
            val obj = getJsonObject("$inst/streams/$videoId") ?: continue
            try {
                val title = obj.optString("title", "")
                val author = obj.optString("uploader", "")

                var audioUrl: String? = null
                var audioBitrate = 0
                var videoUrl: String? = null
                var videoHeight = 0

                val aStreams = obj.optJSONArray("audioStreams")
                if (aStreams != null) {
                    for (i in 0 until aStreams.length()) {
                        val f = aStreams.getJSONObject(i)
                        val br = f.optInt("bitrate", 0)
                        val u = f.optString("url", "")
                        if (u.isNotBlank() && br > audioBitrate) {
                            audioBitrate = br
                            audioUrl = u
                        }
                    }
                }

                var muxed: String? = null
                var bestMuxedH = 0
                val vStreams = obj.optJSONArray("videoStreams")
                if (vStreams != null) {
                    for (i in 0 until vStreams.length()) {
                        val f = vStreams.getJSONObject(i)
                        val h = f.optInt("height", 0)
                        val u = f.optString("url", "")
                        val onlyVideo = f.optBoolean("videoOnly", false)
                        if (u.isBlank()) continue

                        // ★ muxed (video+audio) 최고 화질
                        if (!onlyVideo && h >= bestMuxedH) {
                            bestMuxedH = h
                            muxed = u
                        }
                        // ★ video-only도 저장 (최고 화질)
                        if (h > videoHeight) {
                            videoHeight = h
                            videoUrl = u
                        }
                    }
                }

                if (audioUrl != null || videoUrl != null || muxed != null) {
                    Log.d(TAG, "extractStream piped: a=${audioUrl != null} v=${videoUrl != null} m=${muxed != null}")
                    return@withContext StreamUrls(audioUrl, muxed, videoUrl, title, author, "piped")
                }
            } catch (e: Exception) { }
        }

        Log.e(TAG, "extractStream FAIL: $videoId")
        StreamUrls(null, null, null, "", "", "")
    }

        // ═══════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════
    fun fmtSec(s: Int): String {
        if (s <= 0) return ""
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }

    fun fmtViews(v: Long): String {
        return when {
            v >= 100_000_000 -> "%.1f억회".format(v / 100_000_000.0)
            v >= 10_000 -> "%.1f만회".format(v / 10_000.0)
            v >= 1_000 -> "%.1f천회".format(v / 1_000.0)
            v > 0 -> "${v}회"
            else -> ""
        }
    }

    fun fmtNum(n: Long): String {
        return when {
            n >= 100_000_000 -> "%.1f억".format(n / 100_000_000.0)
            n >= 10_000 -> "%.1f만".format(n / 10_000.0)
            n >= 1_000 -> "%.1f천".format(n / 1_000.0)
            else -> "$n"
        }
    }
}
