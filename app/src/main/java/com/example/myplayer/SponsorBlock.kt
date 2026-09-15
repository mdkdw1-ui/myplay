package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
    val actionType: String = "skip"
) {
    val categoryLabel: String
        get() = when (category) {
            "sponsor" -> "스폰서"
            "selfpromo" -> "자기홍보"
            "interaction" -> "구독요청"
            "intro" -> "인트로"
            "outro" -> "아웃트로"
            "preview" -> "미리보기"
            "music_offtopic" -> "비음악"
            "filler" -> "잡담"
            else -> category
        }
}

object SponsorBlock {

    private const val TAG = "SponsorBlock"
    private const val API = "https://sponsor.ajay.app/api/skipSegments"

    val DEFAULT_CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro")

    val ALL_CATEGORIES = listOf(
        "sponsor", "selfpromo", "interaction", "intro",
        "outro", "preview", "music_offtopic", "filler"
    )

    fun labelOf(cat: String): String = when (cat) {
        "sponsor" -> "스폰서"
        "selfpromo" -> "자기홍보"
        "interaction" -> "구독요청"
        "intro" -> "인트로"
        "outro" -> "아웃트로"
        "preview" -> "미리보기/리캡"
        "music_offtopic" -> "비음악"
        "filler" -> "잡담"
        else -> cat
    }

    suspend fun fetch(videoId: String, categories: List<String>): List<SkipSegment> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<SkipSegment>()
            try {
                val catJson = JSONArray(categories).toString()
                val encoded = URLEncoder.encode(catJson, "UTF-8")
                val url = URL("$API?videoID=$videoId&categories=$encoded")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "MyPlayer/1.0")
                conn.setRequestProperty("Accept", "application/json")

                val code = conn.responseCode
                Log.d(TAG, "HTTP $code for $videoId")
                if (code !in 200..299) {
                    // 404 = 세그먼트 없음 (정상)
                    return@withContext emptyList()
                }

                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val arr = JSONArray(response)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val seg = obj.optJSONArray("segment") ?: continue
                    val startMs = (seg.optDouble(0, 0.0) * 1000).toLong()
                    val endMs = (seg.optDouble(1, 0.0) * 1000).toLong()
                    val cat = obj.optString("category")
                    val action = obj.optString("actionType", "skip")
                    if (endMs > startMs) {
                        out.add(SkipSegment(startMs, endMs, cat, action))
                    }
                }
                Log.d(TAG, "found ${out.size} segments")
            } catch (e: Exception) {
                Log.e(TAG, "err: ${e.message}", e)
            }
            out.sortedBy { it.startMs }
        }
}
