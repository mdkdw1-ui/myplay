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

    
    /** 로컬 파일 큐 추가 */
    fun addLocal(ctx: Context, media: LocalMedia) {
        val list = get(ctx)
        if (list.any { it.videoId == "local:${media.id}" }) return
        list.add(
            HomeVideo(
                videoId = "local:${media.id}",
                title = media.title,
                channel = media.artist,
                thumbnail = ""
            )
        )
        save(ctx, list)
    }

    fun size(ctx: Context): Int = get(ctx).size

    fun setCurrent(ctx: Context, videoId: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("current_id", videoId).apply()
    }

    fun getCurrent(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString("current_id", null)

    fun move(ctx: Context, from: Int, to: Int) {
        val list = get(ctx)
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        save(ctx, list)
    }
}
