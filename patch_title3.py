import re
p='app/src/main/java/com/example/myplayer/ChapterReidentifyService.kt'
s=open(p,encoding='utf-8').read()

# 1) 결과 카드 마커 게이트 추가 (카드가 보일 때만 동작)
anchor='android.util.Log.d("ChapterReidentify", "texts=${texts.size} sample=${texts.take(5)}")'
gate='''
        val cardMarkers = setOf("AI 모드", "전체", "동영상", "이미지")
        if (texts.none { it.trim() in cardMarkers }) return'''
if 'cardMarkers' not in s:
    assert anchor in s, 'texts log anchor not found'
    s=s.replace(anchor, anchor+gate, 1)

# 2) UI 문구 확장 + contains 필터
old_ui='val uiLabels = setOf("홈", "검색", "알림", "활동", "AI 모드", "전체", "동영상", "이미지")'
new_ui='val uiLabels = setOf("홈", "검색", "알림", "활동", "AI 모드", "전체", "동영상", "이미지", "실행 중인 앱", "모두 닫기", "화면에", "노래를", "물어보기", "재생하거나", "흥얼거려", "Termux", "Perplexity", "DeepSeek")'
if '실행 중인 앱' not in s:
    assert old_ui in s, 'uiLabels anchor not found'
    s=s.replace(old_ui, new_ui, 1)

old_f='t !in uiLabels && t.length >= 4'
new_f='t !in uiLabels && t.length >= 4 && uiLabels.none { t.contains(it) }'
if 'uiLabels.none' not in s:
    assert old_f in s, 'filter anchor not found'
    s=s.replace(old_f, new_f, 1)

open(p,'w',encoding='utf-8').write(s)
print('service gate patched')
