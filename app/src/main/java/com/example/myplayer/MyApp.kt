package com.example.myplayer

import android.app.Application

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this

        // ★ IPv4 강제 (IPv6 fallback "Unable to resolve host" 문제 해결)
        try {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: MyApp
            private set
    }
}
