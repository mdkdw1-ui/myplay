import re

p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# 1) 혹시 모를 깨진 코드 정리
s = re.sub(r'\s*// ★★★ \[수정\] 테슬라/블루투스 Next/Previous 버튼 처리 ★★★.*?override fun onSkipToPrevious.*?\n\s*\}\s*\}\s*\)', '', s, flags=re.S)

# 2) 깨끗한 setCallback 블록 찾아서 교체
old_callback = '''            .setCallback(object : MediaSession.Callback {
                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    // 연결 시 자동 재생 방지
                }
            })'''

new_callback = '''            .setCallback(object : MediaSession.Callback {
                override fun onPostConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ) {
                    // 연결 시 자동 재생 방지
                }

                override fun onSkipToNext(session: MediaSession) {
                    android.util.Log.d("PlaybackService", "onSkipToNext from bluetooth")
                    val player = session.player
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    } else {
                        try {
                            val intent = android.content.Intent("com.example.myplayer.ACTION_SKIP_NEXT")
                            sendBroadcast(intent)
                        } catch (_: Exception) {}
                    }
                }

                override fun onSkipToPrevious(session: MediaSession) {
                    android.util.Log.d("PlaybackService", "onSkipToPrevious from bluetooth")
                    val player = session.player
                    if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player.seekTo(0)
                    }
                }
            })'''

if old_callback in s:
    s = s.replace(old_callback, new_callback, 1)
    print('✅ PlaybackService.kt: setCallback 교체 완료')
else:
    print('⚠️ setCallback 패턴을 찾지 못했습니다. (이미 적용되었거나 꼬임)')

if s != orig:
    open(p, 'w', encoding='utf-8').write(s)
    print('💾 파일 저장 완료')
