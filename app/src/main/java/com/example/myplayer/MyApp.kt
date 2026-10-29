package com.example.myplayer

import android.app.Application
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // ★ 크래시 로그 저장
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val f = java.io.File(filesDir, "crash_log.txt")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                f.writeText("=== Crash at ${java.util.Date()} ===\n$sw")
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        try {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("java.net.preferIPv6Addresses", "false")
            // ★ Android는 sun.net.spi.nameservice 지원 안 함
            //   → 시스템 DNS 리졸버 사용 (제거)
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
