p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# mediaSession = MediaSession.Builder(...).build() 뒤에 setActive(true) 추가
old = '''            .build()
        
        player.addListener(notifListener)'''

new = '''            .build()

        // ★★★ [수정] MediaSession을 시스템에 강제 활성화 ★★★
        try {
            mediaSession?.setSessionActivity(sessionActivity)
            // Media3에서 세션 활성화 (시스템에 등록)
            mediaSession?.player?.playWhenReady = player.playWhenReady
            android.util.Log.d("PlaybackService", "MediaSession activated")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "setActive err: ${e.message}")
        }

        player.addListener(notifListener)'''

if old in s:
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print('✅ MediaSession 활성화 코드 추가 완료')
else:
    print('⚠️ 패턴 매칭 실패 - 수동 확인 필요')
    # 주변 컨텍스트 출력
    idx = s.find('.build()')
    if idx > 0:
        print('--- 주변 ---')
        print(s[max(0, idx-100):idx+300])
