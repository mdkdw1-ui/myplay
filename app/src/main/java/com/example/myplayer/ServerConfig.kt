package com.example.myplayer

import android.content.Context

object ServerConfig {
    @Volatile
    private var appContext: Context? = null

    private const val PREFS = "server_config"
    private const val KEY = "base_url"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): String {
        val ctx = appContext ?: return ""
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, "")?.trim()?.removeSuffix("/") ?: ""
    }

    fun set(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, url.trim()).apply()
    }

    fun isConfigured(): Boolean = get().isNotBlank()
}
