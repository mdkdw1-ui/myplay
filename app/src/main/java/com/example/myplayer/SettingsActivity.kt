package com.example.myplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }

        findViewById<View>(R.id.cardYtLogin).setOnClickListener {
            startActivity(Intent(this, YouTubeLoginActivity::class.java))
        }

        findViewById<View>(R.id.cardYtClear).setOnClickListener {
            YouTubeCookieManager.clear(this)
            Toast.makeText(this, "YouTube 쿠키 삭제됨", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        // ★★★ 업데이트 확인
        try {
            findViewById<View>(R.id.cardUpdate)?.setOnClickListener {
                checkForUpdate()
            }
        } catch (_: Exception) {}

        // 현재 버전 표시
        try {
            findViewById<android.widget.TextView>(R.id.tvAppVersion)?.text =
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        } catch (_: Exception) {}

        refreshStatus()
    }

    private fun checkForUpdate() {
        val btn = findViewById<View>(R.id.cardUpdate)
        btn?.isEnabled = false
        Toast.makeText(this, "업데이트 확인 중...", Toast.LENGTH_SHORT).show()

        androidx.lifecycle.lifecycleScope.launch {
            try {
                val result = UpdateChecker.checkUpdate(applicationContext)
                btn?.isEnabled = true

                result.onSuccess { info ->
                    if (info == null) {
                        androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("최신 버전")
                            .setMessage("현재 최신 버전입니다.\n\n현재 버전: v${BuildConfig.VERSION_NAME}")
                            .setPositiveButton("확인", null)
                            .show()
                    } else {
                        showUpdateDialog(info)
                    }
                }.onFailure { e ->
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("업데이트 확인 실패")
                        .setMessage("${e.message}\n\n네트워크를 확인하거나 나중에 다시 시도하세요.")
                        .setPositiveButton("확인", null)
                        .show()
                }
            } catch (e: Exception) {
                btn?.isEnabled = true
                Toast.makeText(this@SettingsActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val sizeMb = if (info.fileSize > 0) " (${info.fileSize / 1024 / 1024}MB)" else ""
        val message = buildString {
            appendLine("현재 버전: v${BuildConfig.VERSION_NAME}")
            appendLine("새 버전: v${info.version}$sizeMb")
            appendLine()
            appendLine("📋 변경 사항:")
            appendLine(info.releaseNotes.take(500))
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎉 새 버전이 있습니다")
            .setMessage(message)
            .setPositiveButton("📥 다운로드") { _, _ ->
                startDownload(info)
            }
            .setNeutralButton("🌐 GitHub에서 보기") { _, _ ->
                try {
                    startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(info.releaseUrl)
                    ))
                } catch (_: Exception) {}
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun startDownload(info: UpdateChecker.UpdateInfo) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("다운로드 중")
            setMessage("v${info.version}")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        androidx.lifecycle.lifecycleScope.launch {
            try {
                val result = UpdateChecker.downloadAndInstall(
                    applicationContext, info
                ) { pct ->
                    runOnUiThread { progressDialog.progress = pct }
                }
                progressDialog.dismiss()

                result.onSuccess { apkFile ->
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("설치")
                        .setMessage("다운로드 완료 (${apkFile.length() / 1024 / 1024}MB)\n\n지금 설치하시겠습니까?\n(설정에서 '출처를 알 수 없는 앱' 허용 필요)")
                        .setPositiveButton("설치") { _, _ ->
                            try {
                                UpdateChecker.installApk(this@SettingsActivity, apkFile)
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "설치 실패: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("나중에", null)
                        .show()
                }.onFailure { e ->
                    Toast.makeText(this@SettingsActivity, "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@SettingsActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.tvYtStatus) ?: return
        if (YouTubeCookieManager.hasCookie(this)) {
            tv.text = "✅ 로그인됨 (쿠키 저장됨)"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "❌ 로그인 안 됨 (봇 차단 가능성)"
            tv.setTextColor(0xFFFF2D55.toInt())
        }
    }
}
