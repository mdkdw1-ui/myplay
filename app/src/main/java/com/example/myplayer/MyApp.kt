package com.example.myplayer

import android.app.Application
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // ★ 크래시 로그 저장 (내부 + 공용 Downloads 양쪽)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val content = "=== Crash at ${java.util.Date()} ===\n$sw"

                // 1) 앱 내부 저장소
                try {
                    java.io.File(filesDir, "crash_log.txt").writeText(content)
                } catch (_: Exception) {}

                // 2) 공용 Downloads 폴더 (파일 관리자에서 확인 가능)
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "myplayer_crash_log.txt")
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS)
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val collection = android.provider.MediaStore.Downloads.getContentUri(
                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                        )
                        val uri = contentResolver.insert(collection, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(content.toByteArray())
                            }
                            values.clear()
                            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        dir.mkdirs()
                        java.io.File(dir, "myplayer_crash_log.txt").writeText(content)
                    }
                } catch (_: Exception) {}
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
