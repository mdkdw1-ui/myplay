package com.example.myplayer

import android.content.Context

object RecentSearches {

    private const val PREF = "recent_searches"
    private const val KEY = "list"
    private const val MAX = 10

    fun get(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\u0001")
    }

    fun add(ctx: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = get(ctx).toMutableList()
        list.remove(q)
        list.add(0, q)
        while (list.size > MAX) list.removeAt(list.size - 1)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\u0001")).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
