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

    fun dir(ctx: Context): File = File(ctx.filesDir, "videos").apply { mkdirs() }

    suspend fun download(
        ctx: Context,
        videoId: String,
        url: String,
        audioOnly: Boolean = false,
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
            val ext = if (audioOnly) "m4a" else "mp4"
            val file = File(dir(ctx), "$videoId.$ext")
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

    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val items: List<DownloadEntity>
    )

    /**
     * Download/MyPlayer 폴더에서 앱 저장소로 가져오기
     * 반환: (성공 개수, 스킵 개수, 실패 개수, DB에 넣을 DownloadEntity 리스트)
     */
    suspend fun importFromPublicDownload(
        ctx: Context,
        existingIds: Set<String>
    ): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        var failed = 0
        val entities = mutableListOf<DownloadEntity>()

        try {
            val isQ = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            if (isQ) {
                val collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL
                )
                val projection = arrayOf(
                    android.provider.MediaStore.Downloads._ID,
                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.provider.MediaStore.Downloads.SIZE
                )
                val selection = "${android.provider.MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("%MyPlayer%")

                ctx.contentResolver.query(
                    collection, projection, selection, args, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(
                        android.provider.MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(
                        android.provider.MediaStore.Downloads.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(
                        android.provider.MediaStore.Downloads.SIZE)

                    while (cursor.moveToNext()) {
                        val fileId = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val size = cursor.getLong(sizeCol)

                        val lower = name.lowercase()
                        if (!lower.endsWith(".mp4") && !lower.endsWith(".m4a")) continue

                        val videoId = name.substringBeforeLast('.')
                        if (videoId.isBlank()) continue

                        if (videoId in existingIds) {
                            skipped++
                            continue
                        }

                        // 파일 복사
                        val fileUri = android.content.ContentUris.withAppendedId(collection, fileId)
                        val ext = if (lower.endsWith(".m4a")) "m4a" else "mp4"
                        val dst = File(dir(ctx), "$videoId.$ext")
                        val tmp = File(dir(ctx), "$videoId.$ext.part")

                        try {
                            ctx.contentResolver.openInputStream(fileUri)?.use { input ->
                                FileOutputStream(tmp).use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw Exception("no stream")

                            if (dst.exists()) dst.delete()
                            tmp.renameTo(dst)

                            entities.add(
                                DownloadEntity(
                                    videoId = videoId,
                                    title = videoId,
                                    channel = "",
                                    thumbnail = "",
                                    filePath = dst.absolutePath,
                                    sizeBytes = size,
                                    downloadedAt = System.currentTimeMillis(),
                                    isAudioOnly = ext == "m4a"
                                )
                            )
                            imported++
                        } catch (e: Exception) {
                            Log.e(TAG, "copy fail $name: ${e.message}")
                            failed++
                            try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dirPub = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "MyPlayer"
                )
                if (!dirPub.exists()) {
                    return@withContext ImportResult(0, 0, 0, emptyList())
                }
                dirPub.listFiles()?.forEach { src ->
                    val lower = src.name.lowercase()
                    if (!lower.endsWith(".mp4") && !lower.endsWith(".m4a")) return@forEach
                    val videoId = src.name.substringBeforeLast('.')
                    if (videoId.isBlank()) return@forEach
                    if (videoId in existingIds) { skipped++; return@forEach }

                    val ext = if (lower.endsWith(".m4a")) "m4a" else "mp4"
                    val dst = File(dir(ctx), "$videoId.$ext")
                    try {
                        src.copyTo(dst, overwrite = true)
                        entities.add(
                            DownloadEntity(
                                videoId = videoId,
                                title = videoId,
                                channel = "",
                                thumbnail = "",
                                filePath = dst.absolutePath,
                                sizeBytes = src.length(),
                                downloadedAt = System.currentTimeMillis(),
                                isAudioOnly = ext == "m4a"
                            )
                        )
                        imported++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "import err: ${e.message}", e)
        }

        ImportResult(imported, skipped, failed, entities)
    }

    /** Download/MyPlayer 폴더로 내보내기 (자동/수동) */
    fun exportToPublicDownload(ctx: android.content.Context, src: File): Boolean {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        if (src.extension == "m4a") "audio/mp4" else "video/mp4")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/MyPlayer")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                val uri = ctx.contentResolver.insert(collection, values) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                return true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "MyPlayer"
                )
                dir.mkdirs()
                val dst = File(dir, src.name)
                src.copyTo(dst, overwrite = true)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "export err: ${e.message}", e)
            return false
        }
    }
}
