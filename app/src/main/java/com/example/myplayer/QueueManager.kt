package com.example.myplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object QueueManager {

    private const val PREF = "queue_pref"
    private const val KEY = "list"

    fun get(ctx: Context): MutableList<HomeVideo> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val out = mutableListOf<HomeVideo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HomeVideo(
                        videoId = o.optString("videoId"),
                        title = o.optString("title"),
                        channel = o.optString("channel"),
                        thumbnail = o.optString("thumbnail")
                    )
                )
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<HomeVideo>) {
        val arr = JSONArray()
        for (v in list) {
            val o = JSONObject().apply {
                put("videoId", v.videoId)
                put("title", v.title)
                put("channel", v.channel)
                put("thumbnail", v.thumbnail)
            }
            arr.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, video: HomeVideo): Boolean {
        val list = get(ctx)
        if (list.any { it.videoId == video.videoId }) return false
        list.add(video)
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, videoId: String) {
        val list = get(ctx)
        list.removeAll { it.videoId == videoId }
        save(ctx, list)
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
    }

    fun moveToTop(ctx: Context, videoId: String) {
        val list = get(ctx)
        val idx = list.indexOfFirst { it.videoId == videoId }
        if (idx <= 0) return
        val item = list.removeAt(idx)
        list.add(0, item)
        save(ctx, list)
    }

    fun size(ctx: Context): Int = get(ctx).size
}
