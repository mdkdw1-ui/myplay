package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val duration: String = "",
    val viewCount: String = "",
    val uploadDate: String = ""
)

data class SearchPage(
    val videos: List<VideoItem>,
    val continuation: String?
)

/** 검색 필터 */
data class SearchFilter(
    val sort: SortBy = SortBy.RELEVANCE,
    val uploadDate: UploadDate = UploadDate.ALL,
    val duration: Duration = Duration.ALL
) {
    enum class SortBy(val label: String, val params: String) {
        RELEVANCE("관련성", "EgIQAQ%3D%3D"),
        UPLOAD_DATE("업로드 날짜", "CAI%3D"),
        VIEW_COUNT("조회수", "CAM%3D"),
        RATING("평점", "CAE%3D")
    }

    enum class UploadDate(val label: String, val params: String) {
        ALL("전체", ""),
        HOUR("1시간 이내", "EgIIAQ%3D%3D"),
        TODAY("오늘", "EgIIAg%3D%3D"),
        WEEK("이번 주", "EgIIAw%3D%3D"),
        MONTH("이번 달", "EgIIBA%3D%3D"),
        YEAR("올해", "EgIIBQ%3D%3D")
    }

    enum class Duration(val label: String, val params: String) {
        ALL("전체", ""),
        SHORT("4분 이하", "EgIYAQ%3D%3D"),
        MEDIUM("4~20분", "EgIYAw%3D%3D"),
        LONG("20분 이상", "EgIYAg%3D%3D")
    }
}

object YouTubeSearch {

    private const val TAG = "YouTubeSearch"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search"

    suspend fun search(query: String): List<VideoItem> = searchPage(query).videos

    suspend fun searchPage(query: String, filter: SearchFilter? = null): SearchPage =
        withContext(Dispatchers.IO) {
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
                    put("query", query)
                    // 필터 파라미터 (조합)
                    if (filter != null) {
                        val p = buildFilterParams(filter)
                        if (p.isNotEmpty()) put("params", p)
                    } else {
                        put("params", "EgIQAQ%3D%3D")
                    }
                }
                val response = post(ENDPOINT, body.toString())
                    ?: return@withContext SearchPage(emptyList(), null)
                val json = JSONObject(response)
                collectVideos(json, videos)
                cont = findContinuation(json)
            } catch (e: Exception) {
                Log.e(TAG, "err: ${e.message}", e)
            }
            SearchPage(videos, cont)
        }

    /**
     * YouTube 검색 필터 params는 base64 인코딩된 protobuf 조합.
     * 여기서는 몇 가지 유효한 조합을 미리 정의해 사용.
     */
    private fun buildFilterParams(f: SearchFilter): String {
        val sortKey = f.sort.name
        val dateKey = f.uploadDate.name
        val durKey = f.duration.name

        // 자주 쓰는 조합 매핑
        val combo = "$sortKey|$dateKey|$durKey"
        return when (combo) {
            // 정렬만
            "RELEVANCE|ALL|ALL" -> "EgIQAQ%3D%3D"
            "UPLOAD_DATE|ALL|ALL" -> "CAI%3D"
            "VIEW_COUNT|ALL|ALL" -> "CAM%3D"
            "RATING|ALL|ALL" -> "CAE%3D"
            // 업로드 날짜
            "RELEVANCE|HOUR|ALL" -> "EgIIAQ%3D%3D"
            "RELEVANCE|TODAY|ALL" -> "EgIIAg%3D%3D"
            "RELEVANCE|WEEK|ALL" -> "EgIIAw%3D%3D"
            "RELEVANCE|MONTH|ALL" -> "EgIIBA%3D%3D"
            "RELEVANCE|YEAR|ALL" -> "EgIIBQ%3D%3D"
            // 길이
            "RELEVANCE|ALL|SHORT" -> "EgIYAQ%3D%3D"
            "RELEVANCE|ALL|MEDIUM" -> "EgIYAw%3D%3D"
            "RELEVANCE|ALL|LONG" -> "EgIYAg%3D%3D"
            // 업로드 날짜 + 길이
            "RELEVANCE|WEEK|LONG" -> "EgIIAw%3D%3D"
            // 기본 폴백
            else -> "EgIQAQ%3D%3D"
        }
    }

    suspend fun searchMore(continuation: String): SearchPage = withContext(Dispatchers.IO) {
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
            val response = post(ENDPOINT, body.toString())
                ?: return@withContext SearchPage(emptyList(), null)
            val json = JSONObject(response)
            collectVideos(json, videos)
            cont = findContinuation(json)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        SearchPage(videos, cont)
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

    private fun collectVideos(node: Any?, out: MutableList<VideoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideo(it)?.let { v -> out.add(v) } }
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

    private fun extractText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        return obj.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text") ?: ""
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
