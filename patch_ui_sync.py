p = 'app/src/main/java/com/example/myplayer/AudioPlayerActivity.kt'
s = open(p, encoding='utf-8').read()
orig = s

# updateUI 함수 호출을 10초마다 강제로 실행 (백그라운드에서)
if 'forceUiSync' in s:
    print('ℹ️ 이미 forceUiSync가 있습니다.')
else:
    # startPositionAutoSave 근처에 추가
    anchor = 'private fun startPositionAutoSave() {'
    force_ui = '''
    // ★★★ 10초마다 UI 강제 동기화 (제목 안 바뀌는 문제 해결) ★★★
    private fun forceUiSync() {
        bgScope.launch {
            while (isActive) {
                try {
                    val mc = mediaController
                    if (mc != null) {
                        val newId = mc.currentMediaItem?.mediaId ?: ""
                        val meta = mc.currentMediaItem?.mediaMetadata
                        val newTitle = meta?.title?.toString() ?: ""
                        val newChannel = meta?.artist?.toString() ?: ""
                        if (newTitle.isNotBlank() && newTitle != currentTitle) {
                            currentTitle = newTitle
                            currentChannel = newChannel
                            runOnUiThread {
                                tvTitle.text = newTitle
                                tvChannel.text = newChannel
                                diag("forceUiSync: $newTitle")
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(10_000)  // 10초마다
            }
        }
    }

'''
    if anchor in s:
        s = s.replace(anchor, force_ui + anchor, 1)
        # onCreate에서 forceUiSync 호출
        s = s.replace(
            '        PlaybackService.autoNextDisabled = true',
            '        PlaybackService.autoNextDisabled = true\n        forceUiSync()',
            1
        )
        print('✅ forceUiSync 추가 및 호출 완료')
    else:
        print('❌ 앵커를 찾지 못했습니다.')

if s != orig:
    open(p, 'w', encoding='utf-8').write(s)
    print('💾 파일 저장 완료')
