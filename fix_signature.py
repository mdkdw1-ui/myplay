p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# 1) onSkipToNext 시그니처 수정 (session 매개변수 제거)
s = s.replace(
    'override fun onSkipToNext(session: MediaSession) {',
    'override fun onSkipToNext() {'
)

# 2) onSkipToPrevious 시그니처 수정 (session 매개변수 제거)
s = s.replace(
    'override fun onSkipToPrevious(session: MediaSession) {',
    'override fun onSkipToPrevious() {'
)

# 3) 함수 내부에서 'session.player' 대신 'player' 사용
s = s.replace('val player = session.player', 'val player = player')

if s != orig:
    open(p, 'w', encoding='utf-8').write(s)
    print('✅ 시그니처 수정 완료 (onSkipToNext/Previous)')
else:
    print('⚠️ 패턴 매칭 실패 - 수동 확인 필요')
