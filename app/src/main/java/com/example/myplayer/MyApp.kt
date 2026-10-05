package com.example.myplayer

import android.app.Application
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
        } catch (_: Exception) {}

        // ★ visitorData 미리 획득
        try {
            kotlinx.coroutines.GlobalScope.launch(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                try { YouTubeVisitorFetcher.ensure(applicationContext) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: MyApp
            private set
    }
}
