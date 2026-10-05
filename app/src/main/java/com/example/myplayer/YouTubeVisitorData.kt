package com.example.myplayer

import android.content.Context
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object YouTubeVisitorData {
    private const val PREF = "yt_visitor"
    private const val KEY_VD = "visitor_data"

    fun load(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_VD, "") ?: ""

    fun save(ctx: Context, v: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_VD, v).apply()
    }

    /** 쿠키의 SAPISID로 SAPISIDHASH 생성 (Authorization 헤더용) */
    fun sapisidHash(cookie: String): String? {
        try {
            // SAPISID=xxx 추출
            val parts = cookie.split("; ").map { it.trim() }
            val sapisid = parts.firstOrNull { it.startsWith("SAPISID=") }
                ?.substringAfter("=") ?: return null
            val origin = "https://www.youtube.com"
            val ts = System.currentTimeMillis() / 1000
            val data = "$ts $sapisid $origin"
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(data.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${ts}_$sha1"
        } catch (e: Exception) {
            return null
        }
    }
}
