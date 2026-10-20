package com.example.myplayer

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 하단 BottomNavigationView 공통 처리
 * - 동영상 / 음악 / 로컬 3탭
 * - 현재 탭 강조 + 다른 탭 선택 시 Activity 전환
 */
object BottomNavHelper {

    const val TAB_VIDEO = "video"
    const val TAB_AUDIO = "audio"
    const val TAB_LOCAL = "local"

    fun setup(activity: AppCompatActivity, current: String) {
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.bottomNav) ?: return

        // ★ 현재 탭 강조
        val menuId = when (current) {
            TAB_VIDEO -> R.id.nav_video
            TAB_AUDIO -> R.id.nav_audio
            TAB_LOCAL -> R.id.nav_local
            else -> R.id.nav_video
        }
        bottomNav.selectedItemId = menuId

        bottomNav.setOnItemSelectedListener { item ->
            val target = when (item.itemId) {
                R.id.nav_video -> TAB_VIDEO
                R.id.nav_audio -> TAB_AUDIO
                R.id.nav_local -> TAB_LOCAL
                else -> return@setOnItemSelectedListener false
            }

            if (target == current) return@setOnItemSelectedListener true

            val intent = when (target) {
                TAB_VIDEO -> Intent(activity, MainActivity::class.java)
                TAB_AUDIO -> Intent(activity, AudioHomeActivity::class.java)
                TAB_LOCAL -> Intent(activity, LocalMediaActivity::class.java)
                else -> return@setOnItemSelectedListener false
            }
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            activity.startActivity(intent)
            activity.finish()
            true
        }
    }
}
