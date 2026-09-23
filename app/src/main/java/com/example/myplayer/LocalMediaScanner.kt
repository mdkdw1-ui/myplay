package com.example.myplayer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val filePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val folder: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    /** 앨범아트 URI (없으면 null) */
    val albumArtUri: Uri?
        get() = if (albumId > 0L)
            android.content.ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId
            )
        else null
}

object LocalMediaScanner {

    // ★ 캐시 (1분 TTL) — 매번 스캔하지 않음
    @Volatile private var cached: List<LocalMedia>? = null
    @Volatile private var cacheTime: Long = 0L
    private const val CACHE_TTL_MS = 60_000L

    fun invalidateCache() {
        cached = null
        cacheTime = 0L
    }

    suspend fun scan(
        ctx: Context,
        forceRefresh: Boolean = false
    ): List<LocalMedia> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val c = cached
        if (!forceRefresh && c != null && now - cacheTime < CACHE_TTL_MS) {
            return@withContext c
        }
        val result = scanInternal(ctx)
        cached = result
        cacheTime = System.currentTimeMillis()
        result
    }

    private suspend fun scanInternal(ctx: Context): List<LocalMedia> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LocalMedia>()
        try {
            val isQ = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            val folderColumn = if (isQ)
                MediaStore.Audio.Media.RELATIVE_PATH
            else
                MediaStore.Audio.Media.DATA
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                folderColumn,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE
            )
            val collection = if (isQ) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            ctx.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(folderColumn)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: ""
                    val artist = cursor.getString(artistCol) ?: "<unknown>"
                    val album = cursor.getString(albumCol) ?: ""
                    val albumId = cursor.getLong(albumIdCol)
                    val dur = cursor.getLong(durCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: "audio/*"
                    val size = cursor.getLong(sizeCol)

                    val folder = if (isQ) {
                        // RELATIVE_PATH 예: "Music/rock/" → "rock"
                        val rel = path.trimEnd('/')
                        rel.substringAfterLast('/').ifBlank { "내부 저장소" }
                    } else {
                        // DATA 경로에서 상위 폴더명
                        path.substringBeforeLast("/").substringAfterLast("/")
                            .ifBlank { "내부 저장소" }
                    }

                    // ★ Q+ 에서는 정확한 collection URI 사용 (EXTERNAL_CONTENT_URI는 deprecated)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)

                    out.add(
                        LocalMedia(
                            id = id, uri = uri, filePath = path,
                            title = title,
                            artist = artist, album = album, albumId = albumId,
                            durationMs = dur,
                            folder = folder, mimeType = mime, sizeBytes = size
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // ★ 시스템 폴더 + 짧은 효과음 제외
        val systemFolders = setOf(
            "Call", "Calls", "Notifications", "Ringtones", "Alarms",
            "Recordings", "Voice Recorder", "Sounds", "Audio"
        )
        out.filter { m ->
            m.folder !in systemFolders &&
            (m.durationMs == 0L || m.durationMs >= 30_000L)
        }.toMutableList()
    }

    /** MediaStore에서 오디오 삭제 (Android 10+는 RecoverableSecurityException 가능) */
    suspend fun delete(ctx: Context, media: LocalMedia): Boolean = withContext(Dispatchers.IO) {
        try {
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val uri = Uri.withAppendedPath(collection, media.id.toString())
            val rows = ctx.contentResolver.delete(uri, null, null)
            if (rows > 0) invalidateCache()
            rows > 0
        } catch (e: SecurityException) {
            // Android 11+ 앱이 만든 파일 아니면 사용자 확인 필요
            e.printStackTrace()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
