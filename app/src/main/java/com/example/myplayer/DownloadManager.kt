package com.example.myplayer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppDownloader {

    private const val TAG = "AppDownloader"

    /** 다운로드 폴더 */
    fun dir(ctx: Context): File = File(ctx.filesDir, "videos").apply { mkdirs() }

    /**
     * videoUrl을 로컬 파일로 다운로드
     * @param progress 콜백(0~100)
     * @return 저장된 파일 (실패 시 null)
     */
    suspend fun download(
        ctx: Context,
        videoId: String,
        url: String,
        onProgress: (Int) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code")
                return@withContext null
            }

            val total = conn.contentLengthLong
            val file = File(dir(ctx), "$videoId.mp4")
            val tmp = File(dir(ctx), "$videoId.part")

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var sum = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        sum += read
                        if (total > 0) {
                            val pct = (sum * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            if (file.exists()) file.delete()
            tmp.renameTo(file)
            file
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            null
        }
    }
}
