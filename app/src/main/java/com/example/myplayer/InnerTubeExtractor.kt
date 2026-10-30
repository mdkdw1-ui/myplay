package com.example.myplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube InnerTube API 직접 호출 (ydpy 방식)
 * - 여러 클라이언트 순환으로 봇 차단 우회
 * - googlevideo.com 직접 URL 반환 (proxy 없음)
 */
object InnerTubeExtractor {

    private const val TAG = "InnerTube"

    private fun flog(msg: String) {
        android.util.Log.d(TAG, msg)
        try {
            val f = java.io.File(MyApp.instance.filesDir, "audio_diag.log")
            if (f.exists() && f.length() > 500_000) f.writeText("")
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            f.appendText("[$ts] [IT] $msg\n")
        } catch (_: Exception) {}
    }
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"

    data class StreamData(
        val audioUrl: String?,
        val audioBitrate: Int,
        val audioItag: Int,
        val muxedUrl: String?,
        val muxedItag: Int,
        val title: String,
        val channel: String,
        val durationMs: Long,
        val debug: String
    ) {
        val hasAny: Boolean
            get() = audioUrl != null || muxedUrl != null
    }

    // ★ ydpy가 성공한 클라이언트들 (순환 시도)
    private data class ClientInfo(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientNameHeader: String,
        val extraContext: JSONObject? = null
    )

    private val CLIENTS = listOf(
        // ★ 0순위: ANDROID_VR (Oculus) - 서명 해독 불필요, 직접 URL 반환 (가장 빠름)
        ClientInfo(
            name = "ANDROID_VR",
            version = "1.62.27",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1) gzip",
            clientNameHeader = "28"
        ),
        // 1순위: MWEB (모바일 웹) - ydpy에서 성공한 방식
        ClientInfo(
            name = "MWEB",
            version = "2.20250101.00.00",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
            clientNameHeader = "2"
        ),
        // 2순위: TVHTML5 (스마트TV) - 봇 차단 우회 강함
        ClientInfo(
            name = "TVHTML5",
            version = "7.20250101.00.00",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Safari/605.1.15",
            clientNameHeader = "7"
        ),
        // (WEB, ANDROID_VR 제거 — LOGIN_REQUIRED 확인)
    )

    /**
     * 여러 클라이언트 순환 시도
     */
    suspend fun extract(videoId: String): StreamData = withContext(Dispatchers.IO) {
        flog("C1 InnerTube.extract IO 진입: $videoId")
        val sb = StringBuilder()
        for ((idx, client) in CLIENTS.withIndex()) {
            sb.append("try client[$idx]: ${client.name}\n")
            flog("C2 tryClient[$idx] 시작: ${client.name}")
            val result = tryClient(videoId, client, sb)
            flog("C3 tryClient[$idx] 복귀: ${client.name} hasAny=${result?.hasAny}")
            if (result != null && result.hasAny) {
                Log.d(TAG, "✅ 성공: ${client.name} (${result.audioUrl != null || result.muxedUrl != null})")
                return@withContext result
            }
        }
        // 모두 실패
        Log.e(TAG, "모든 클라이언트 실패")
        StreamData(
            audioUrl = null, audioBitrate = 0, audioItag = 0,
            muxedUrl = null, muxedItag = 0,
            title = "", channel = "", durationMs = 0,
            debug = sb.toString()
        )
    }

    private fun tryClient(videoId: String, client: ClientInfo, sb: StringBuilder): StreamData? {
        sb.append("--- tryClient ${client.name} ---\n")
        return try {
            val clientContext = JSONObject().apply {
                put("clientName", client.name)
                put("clientVersion", client.version)
                put("hl", "ko")
                put("gl", "KR")
                if (client.name == "MWEB") {
                    put("platform", "MOBILE")
                    put("clientFormFactor", "SMALL_FORM_FACTOR")
                }
                if (client.name == "TVHTML5") {
                    put("platform", "TV")
                }
                client.extraContext?.let { put("clientFormFactor", "UNKNOWN_FORM_FACTOR") }
            }

            val body = JSONObject().apply {
                put("context", JSONObject().apply { put("client", clientContext) })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val useKeyless = client.name == "ANDROID_VR"
            val endpoint = if (useKeyless) PLAYER_ENDPOINT else "$PLAYER_ENDPOINT?key=$API_KEY"
            val conn = URL("$endpoint&prettyPrint=false").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", client.userAgent)
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            conn.setRequestProperty("Origin", "https://www.youtube.com")
            conn.setRequestProperty("X-YouTube-Client-Name", client.clientNameHeader)
            conn.setRequestProperty("X-YouTube-Client-Version", client.version)
            
            // ★ 쿠키 + visitorData (ydpy와 동일)
            try { CookieUtil.apply(conn) } catch (_: Exception) {}
            try {
                val vd = com.example.myplayer.YouTubeVisitorData.load(MyApp.instance)
                if (vd.isNotBlank()) {
                    conn.setRequestProperty("X-Goog-Visitor-Id", vd)
                    sb.append("  visitorData: ${vd.take(20)}\n")
                }
            } catch (_: Exception) {}
            
            conn.doOutput = true
            conn.connectTimeout = 2500
            conn.readTimeout = 4000
            sb.append("  writing request...\n")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            sb.append("  request sent, awaiting response...\n")
            val code = conn.responseCode
            sb.append("  HTTP $code\n")
            if (code !in 200..299) return null

            val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(response)

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status") ?: "?"
            sb.append("  status: $status\n")
            if (status != "OK") {
                sb.append("  reason: ${playability?.optString("reason")?.take(80)}\n")
                return null
            }

            val videoDetails = json.optJSONObject("videoDetails")
            val title = videoDetails?.optString("title") ?: ""
            val channel = videoDetails?.optString("author") ?: ""
            val durationMs = (videoDetails?.optLong("lengthSeconds", 0) ?: 0) * 1000

            val streaming = json.optJSONObject("streamingData")
            if (streaming == null) {
                sb.append("  no streamingData\n")
                return null
            }

            var bestAudioUrl: String? = null
            var bestAudioBitrate = 0
            var bestAudioItag = 0
            var bestMuxedUrl: String? = null
            var bestMuxedItag = 0

            // 1) muxed (formats) - 영상+오디오
            streaming.optJSONArray("formats")?.let { formats ->
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    if (mime.startsWith("audio/")) {
                        val br = f.optInt("bitrate", 0)
                        if (br > bestAudioBitrate) {
                            bestAudioBitrate = br
                            bestAudioUrl = f.optString("url", "")
                            bestAudioItag = f.optInt("itag", 0)
                        }
                    } else if (mime.startsWith("video/")) {
                        bestMuxedUrl = f.optString("url", "")
                        bestMuxedItag = f.optInt("itag", 0)
                        break  // 첫 muxed
                    }
                }
            }

            // 2) adaptiveFormats - 오디오만
            streaming.optJSONArray("adaptiveFormats")?.let { adaptive ->
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    if (mime.startsWith("audio/")) {
                        val br = f.optInt("bitrate", 0)
                        if (br > bestAudioBitrate) {
                            bestAudioBitrate = br
                            bestAudioUrl = f.optString("url", "")
                            bestAudioItag = f.optInt("itag", 0)
                        }
                    }
                }
            }

            sb.append("  audio: itag=$bestAudioItag bitrate=$bestAudioBitrate url=${bestAudioUrl?.take(50)}\n")
            sb.append("  muxed: itag=$bestMuxedItag url=${bestMuxedUrl?.take(50)}\n")

            StreamData(
                audioUrl = bestAudioUrl,
                audioBitrate = bestAudioBitrate,
                audioItag = bestAudioItag,
                muxedUrl = bestMuxedUrl,
                muxedItag = bestMuxedItag,
                title = title,
                channel = channel,
                durationMs = durationMs,
                debug = sb.toString()
            )
        } catch (e: Exception) {
            sb.append("  err: ${e.message?.take(60)}\n")
            null
        }
    }
}
