package com.example.myplayer

import android.content.Context

object YouTubeCookieManager {
    private const val PREF = "yt_cookie_pref"
    private const val KEY_COOKIE = "cookie"

    fun save(ctx: Context, cookie: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun load(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun hasCookie(ctx: Context): Boolean = load(ctx).isNotBlank()
}
