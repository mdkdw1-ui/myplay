package com.example.myplayer

import android.content.Context

object ServerConfig {
    private const val PREFS = "server_config"
    private const val KEY = "base_url"
    private const val DEFAULT = "https://mymyplay.duckdns.org"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, DEFAULT)?.takeIf { it.isNotBlank() } ?: DEFAULT
    }

    fun set(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, url.trim()).apply()
    }
}
