import re

p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# 1) 방금 넣은 onSkipToNext / onSkipToPrevious 블록 제거
s = re.sub(
    r'\s*override fun onSkipToNext\(\) \{.*?override fun onSkipToPrevious\(\) \{.*?\n\s*\}\s*\n\s*\}',
    '',
    s, flags=re.S
)

if s != orig:
    open(p, 'w', encoding='utf-8').write(s)
    print('✅ 잘못된 onSkipToNext/Previous 코드 제거 완료')
else:
    print('ℹ️ 제거할 코드가 없습니다.')
