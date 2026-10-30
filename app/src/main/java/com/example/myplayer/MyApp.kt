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
            // ★ Android는 sun.net.spi.nameservice 지원 안 함
            //   → 시스템 DNS 리졸버 사용 (제거)
        } catch (_: Exception) {}

        // ★★★ 진단: visitorData 강제 리셋 (1회)
        try {
            val pref = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val version = pref.getInt("vd_reset_version", 0)
            if (version < 1) {
                YouTubeVisitorData.save(applicationContext, "")
                getSharedPreferences("yt_cookie_pref", MODE_PRIVATE).edit().clear().apply()
                pref.edit().putInt("vd_reset_version", 1).apply()
                android.util.Log.d("MyApp", "visitorData + cookie 강제 리셋")
            }
        } catch (_: Exception) {}

        // ★ visitorData 미리 획득
        try {
            kotlinx.coroutines.GlobalScope.launch(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                try { YouTubeVisitorFetcher.ensure(applicationContext) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // ★ 서버 헬스체크 (앱 시작 시)
        try {
            kotlinx.coroutines.GlobalScope.launch(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                try {
                    ServerHealthChecker.ensureChecked(applicationContext)
                    ServerHealthChecker.printStatus(applicationContext)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: MyApp
            private set
    }
}
