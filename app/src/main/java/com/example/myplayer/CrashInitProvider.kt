package com.example.myplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application.onCreate 보다 먼저 실행되어 크래시를 잡습니다.
 * ContentProvider 는 앱 프로세스가 뜨자마자 가장 먼저 호출됩니다.
 */
class CrashInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val content = "=== Crash ${java.util.Date()} ===\n$sw"

                // 1) 내부 파일
                try {
                    context?.let { File(it.filesDir, "crash_log.txt").writeText(content) }
                } catch (_: Exception) {}

                // 2) 공용 Downloads
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "myplayer_crash_log.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val uri = context?.contentResolver?.insert(collection, values)
                        uri?.let {
                            context?.contentResolver?.openOutputStream(it)?.use { out ->
                                out.write(content.toByteArray())
                            }
                            values.clear()
                            values.put(MediaStore.Downloads.IS_PENDING, 0)
                            context?.contentResolver?.update(it, values, null, null)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        File(dir, "myplayer_crash_log.txt").writeText(content)
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {}

            prev?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
