package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 오디오 모드 특화: 연관 노래 추출
 * - InnerTube next 엔드포인트에서 관련 동영상 추출
 * - 라이브 / 예정 / 짧은 영상(< 30초) 제외
 * - 최고 비트레이트 오디오 스트림 우선
 */
object YouTubeRadio {

    private const val TAG = "YouTubeRadio"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val NEXT = "https://www.youtube.com/youtubei/v1/next"

    /**
     * 현재 비디오 기반 연관 노래 목록
     */
    suspend fun fetchRelated(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoItem>()
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
                put("videoId", videoId)
            }

            val conn = URL("$NEXT?key=$API_KEY&prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code for $videoId")
            if (code !in 200..299) return@withContext emptyList()

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            collect(json, out, videoId)
            Log.d(TAG, "found ${out.size} related (raw)")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        out.distinctBy { it.videoId }.take(20)
    }

    private fun collect(node: Any?, out: MutableList<VideoItem>, excludeId: String) {
        when (node) {
            is JSONObject -> {
                // 1) compactVideoRenderer (관련 영상 표준)
                node.optJSONObject("compactVideoRenderer")?.let { c ->
                    parse(c, excludeId, out)
                }
                // 2) playlistPanelVideoRenderer (Mix)
                node.optJSONObject("playlistPanelVideoRenderer")?.let { p ->
                    parseMix(p, excludeId, out)
                }
                // 3) lockupViewModel (신규)
                node.optJSONObject("lockupViewModel")?.let { l ->
                    parseLockup(l, excludeId, out)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collect(node.opt(keys.next()), out, excludeId)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, excludeId)
        }
    }

    /** ★ 라이브/예정 필터링 */
    private fun isLiveOrUpcoming(obj: JSONObject): Boolean {
        // badges에 LIVE / UPCOMING / 예정 있으면 제외
        val badges = obj.optJSONArray("badges") ?: return false
        for (i in 0 until badges.length()) {
            val b = badges.optJSONObject(i) ?: continue
            val meta = b.optJSONObject("metadataBadgeRenderer") ?: continue
            val label = meta.optString("label").lowercase()
            if (label.contains("live") || label.contains("upcoming") ||
                label.contains("예정") || label.contains("실시간")) {
                return true
            }
        }
        // thumbnailOverlays에 LIVE 표시
        val overlays = obj.optJSONArray("thumbnailOverlays") ?: return false
        for (i in 0 until overlays.length()) {
            val o = overlays.optJSONObject(i) ?: continue
            val timeStatus = o.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
            val style = timeStatus.optString("style")
            if (style == "LIVE" || style == "UPCOMING") return true
        }
        return false
    }

    /** 길이 파싱 (초) */
    private fun parseDurationSec(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private fun parse(c: JSONObject, excludeId: String, out: MutableList<VideoItem>) {
        val videoId = c.optString("videoId").takeIf { it.isNotEmpty() } ?: return
        if (videoId == excludeId) return
        if (isLiveOrUpcoming(c)) return  // ★ 라이브 제외

        val title = extractText(c.optJSONObject("title"))
        val channel = extractText(c.optJSONObject("shortBylineText"))
            .ifBlank { extractText(c.optJSONObject("longBylineText")) }
        val thumb = c.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(c.optJSONObject("lengthText"))

        // ★ 너무 짧은 영상 제외 (< 30초)
        val sec = parseDurationSec(duration)
        if (sec in 1..29) return

        out.add(VideoItem(videoId, title, channel, thumb, duration, "", ""))
    }

    private fun parseMix(p: JSONObject, excludeId: String, out: MutableList<VideoItem>) {
        val videoId = p.optString("videoId").takeIf { it.isNotEmpty() } ?: return
        if (videoId == excludeId) return
        if (isLiveOrUpcoming(p)) return

        val title = extractText(p.optJSONObject("title"))
        val channel = extractText(p.optJSONObject("shortBylineText"))
        val thumb = p.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        val duration = extractText(p.optJSONObject("lengthText"))

        val sec = parseDurationSec(duration)
        if (sec in 1..29) return

        out.add(VideoItem(videoId, title, channel, thumb, duration, "", ""))
    }

    private fun parseLockup(l: JSONObject, excludeId: String, out: MutableList<VideoItem>) {
        val videoId = l.optString("contentId").takeIf { it.isNotEmpty() } ?: return
        if (videoId == excludeId) return

        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content") ?: ""
        val channel = meta?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")?.optJSONObject(0)
            ?.optJSONArray("metadataParts")?.optJSONObject(0)
            ?.optJSONObject("text")?.optString("content") ?: ""
        val thumb = l.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { it.optJSONObject(it.length() - 1)?.optString("url") }
            ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

        out.add(VideoItem(videoId, title, channel, thumb, "", "", ""))
    }

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
    }
}
