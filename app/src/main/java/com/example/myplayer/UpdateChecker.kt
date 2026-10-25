package com.example.myplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases API로 업데이트 확인 & APK 다운로드
 * - 저장소: mdkdw1-ui/myplay
 * - 릴리스 태그 형식: v1.0.0 (또는 1.0.0)
 * - APK asset 이름: myplayer-debug.apk (또는 .apk 확장자)
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO_OWNER = "mdkdw1-ui"
    private const val REPO_NAME = "myplay"
    private const val API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val releaseNotes: String,
        val downloadUrl: String,
        val releaseUrl: String,
        val fileName: String,
        val fileSize: Long
    )

    /**
     * 최신 릴리스 확인
     * @return 새 버전 있으면 UpdateInfo, 없으면 null
     */
    suspend fun checkUpdate(ctx: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MyPlayer/" + BuildConfig.VERSION_NAME)
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = conn.responseCode
            Log.d(TAG, "HTTP $code")
            if (code !in 200..299) {
                if (code == 404) {
                    Log.d(TAG, "Release 없음 → 최신 버전으로 처리")
                    return@withContext Result.success(null)
                }
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "err: ${err?.take(200)}")
                return@withContext Result.failure(Exception("HTTP $code: ${err?.take(100)}"))
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            val tagName = json.optString("tag_name", "")
            val name = json.optString("name", tagName)
            val body = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "")

            // 버전 비교 (v1.0.0 or 1.0.0)
            val latestVersion = tagName.removePrefix("v").trim()
            if (latestVersion.isBlank()) {
                return@withContext Result.success(null)
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (!isNewerVersion(currentVersion, latestVersion)) {
                Log.d(TAG, "현재 최신: $currentVersion >= $latestVersion")
                return@withContext Result.success(null)
            }

            // APK asset 찾기
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkName = assetName
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) {
                return@withContext Result.failure(Exception("APK asset이 없습니다"))
            }

            val info = UpdateInfo(
                version = latestVersion,
                versionCode = 0,
                releaseNotes = body.ifBlank { name.ifBlank { "v$latestVersion 릴리스" } },
                downloadUrl = apkUrl,
                releaseUrl = htmlUrl,
                fileName = apkName,
                fileSize = apkSize
            )
            Log.d(TAG, "새 버전 발견: $latestVersion (현재 $currentVersion)")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "err: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** 버전 비교 (1.0.5 > 1.0.4) */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        return try {
            val cur = current.split(".").map { it.toIntOrNull() ?: 0 }
            val lat = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(cur.size, lat.size)
            for (i in 0 until maxLen) {
                val c = cur.getOrElse(i) { 0 }
                val l = lat.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (_: Exception) {
            latest != current
        }
    }

    /**
     * APK 다운로드 → 설치 인텐트
     * @param onProgress 0~100
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // 다운로드 폴더
            val apkDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(apkDir, info.fileName.ifBlank { "myplayer_update.apk" })
            if (apkFile.exists()) apkFile.delete()

            val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "MyPlayer/" + BuildConfig.VERSION_NAME)
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(Exception("HTTP $code"))
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastPct = -1

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "다운로드 완료: ${apkFile.absolutePath} (${apkFile.length() / 1024 / 1024}MB)")
            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "다운로드 err: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun installApk(ctx: Context, apkFile: File) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            // SAI 등 서드파티 설치 프로그램에 명시적 읽기 권한 부여
            try {
                val resolveInfos = ctx.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo.packageName
                    try {
                        ctx.grantUriPermission(
                            pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        Log.d(TAG, "grantUriPermission -> $pkg")
                    } catch (e: Exception) {
                        Log.w(TAG, "grantUriPermission fail ($pkg): ${e.message}")
                    }
                }
                if (resolveInfos.isEmpty()) {
                    Log.w(TAG, "APK install intent - no handler")
                }
            } catch (e: Exception) {
                Log.e(TAG, "queryIntentActivities err: ${e.message}", e)
            }

            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install intent err: ${e.message}", e)
            throw e
        }
    }
}
