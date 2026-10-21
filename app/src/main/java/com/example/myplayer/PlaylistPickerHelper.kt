package com.example.myplayer

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlaylistPickerHelper {

    /** 다중 선택한 영상들을 플레이리스트로 저장 */
    fun showSaveDialog(
        ctx: Context,
        scope: LifecycleCoroutineScope,
        videos: List<VideoItem>
    ) {
        if (videos.isEmpty()) {
            Toast.makeText(ctx, "선택된 영상 없음", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            val dao = HistoryDatabase.get(ctx).savedPlaylistDao()
            val existing: List<SavedPlaylistEntity> = try {
                withContext(Dispatchers.IO) {
                    dao.getAllPlaylists().first()
                }
            } catch (e: Exception) {
                emptyList<SavedPlaylistEntity>()
            }

            val options = mutableListOf<String>()
            options.add("➕ 새 플레이리스트 만들기")
            existing.forEach { options.add("📂 ${it.name} (${it.itemCount})") }

            AlertDialog.Builder(ctx)
                .setTitle("${videos.size}개 영상 저장")
                .setItems(options.toTypedArray()) { _, which ->
                    if (which == 0) {
                        showNewPlaylistDialog(ctx, scope, videos)
                    } else {
                        val target = existing[which - 1]
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val existingItems = dao.getItems(target.id)
                                val existingIds = existingItems.map { it.videoId }.toSet()
                                var pos = target.itemCount
                                var added = 0
                                videos.forEach { v ->
                                    if (v.videoId in existingIds) {
                                        return@forEach  // ★ 중복 스킵
                                    }
                                    dao.insertItem(
                                        SavedPlaylistItemEntity(
                                            playlistId = target.id,
                                            videoId = v.videoId,
                                            title = v.title,
                                            channel = v.channel,
                                            thumbnail = v.thumbnail,
                                            position = pos++
                                        )
                                    )
                                    added++
                                }
                                dao.updateCount(target.id, pos)
                                if (added == 0) {
                                    // 이미 있음 → 메인 스레드 알림
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(ctx,
                                            "\"${target.name}\"에 이미 있는 곡입니다",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(ctx,
                                            "\"${target.name}\"에 추가됨 (${added}곡)",
                                            Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun showNewPlaylistDialog(
        ctx: Context,
        scope: LifecycleCoroutineScope,
        videos: List<VideoItem>
    ) {
        val input = EditText(ctx).apply {
            hint = "플레이리스트 이름"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(ctx)
            .setTitle("새 플레이리스트")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "이름 필요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                scope.launch {
                    val dao = HistoryDatabase.get(ctx).savedPlaylistDao()
                    val id = withContext(Dispatchers.IO) {
                        val pid = dao.insertPlaylist(
                            SavedPlaylistEntity(
                                name = name,
                                createdAt = System.currentTimeMillis(),
                                itemCount = 0
                            )
                        )
                        videos.forEachIndexed { i, v ->
                            dao.insertItem(
                                SavedPlaylistItemEntity(
                                    playlistId = pid,
                                    videoId = v.videoId,
                                    title = v.title,
                                    channel = v.channel,
                                    thumbnail = v.thumbnail,
                                    position = i
                                )
                            )
                        }
                        dao.updateCount(pid, videos.size)
                        pid
                    }
                    Toast.makeText(ctx, "\"$name\" 저장됨 (${videos.size}개)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
