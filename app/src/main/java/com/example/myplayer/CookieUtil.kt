package com.example.myplayer

import java.net.HttpURLConnection

object CookieUtil {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 모든 HTTP 연결에 쿠키 + UA + Accept-Language 적용 */
    fun apply(conn: HttpURLConnection) {
        try {
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language",
                "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            conn.setRequestProperty("Accept", "*/*")
            val ctx = MyApp.instance.applicationContext
            val cookie = YouTubeCookieManager.load(ctx)
            if (cookie.isNotBlank()) {
                conn.setRequestProperty("Cookie", cookie)
                // ★ SAPISIDHASH (사람 인증)
                try {
                    val hash = YouTubeVisitorData.sapisidHash(cookie)
                    if (hash != null) {
                        conn.setRequestProperty("Authorization", hash)
                    }
                } catch (_: Exception) {}
            }

            // ★ visitorData (봇 차단 회피)
            val vd = YouTubeVisitorData.load(ctx)
            if (vd.isNotBlank()) {
                conn.setRequestProperty("X-Goog-Visitor-Id", vd)
            }
        } catch (_: Exception) {}
    }
}
