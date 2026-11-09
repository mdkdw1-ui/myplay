p = 'app/src/main/java/com/example/myplayer/AudioPlayerActivity.kt'
s = open(p, encoding='utf-8').read()

if 'ACTION_SKIP_NEXT' in s:
    print('ℹ️ 이미 브로드캐스트 수신기가 있습니다.')
else:
    old = '        PlaybackService.autoNextDisabled = true'
    new = '''        PlaybackService.autoNextDisabled = true

        try {
            val skipReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    if (intent?.action == "com.example.myplayer.ACTION_SKIP_NEXT") {
                        diag("broadcast: ACTION_SKIP_NEXT 수신")
                        playNextManual()
                    }
                }
            }
            val filter = android.content.IntentFilter("com.example.myplayer.ACTION_SKIP_NEXT")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(skipReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(skipReceiver, filter)
            }
        } catch (e: Exception) {
            diag("broadcast 수신기 등록 err: ${e.message}")
        }'''

    if old in s:
        s = s.replace(old, new, 1)
        open(p, 'w', encoding='utf-8').write(s)
        print('✅ AudioPlayerActivity: 브로드캐스트 수신기 추가 완료')
    else:
        print('⚠️ 패턴 매칭 실패')
