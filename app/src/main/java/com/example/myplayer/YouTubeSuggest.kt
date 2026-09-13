package com.example.myplayer

import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeSuggest {

    private const val TAG = "YouTubeSuggest"

    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        if (query.isBlank()) return@withContext out
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "https://suggestqueries-clients6.youtube.com/complete/search" +
                        "?client=firefox&ds=yt&hl=ko&q=$enc"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")

            val code = conn.responseCode
            if (code !in 200..299) return@withContext out

            val resp = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONArray(resp)
            val arr = json.optJSONArray(1) ?: return@withContext out

            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                when (item) {
                    is String -> out.add(item)
                    is JSONArray -> {
                        val s = item.optString(0)
                        if (s.isNotEmpty()) out.add(s)
                    }
                }
            }
            Log.d(TAG, "suggest '$query' -> ${out.size}")
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
        }
        out
    }
}
