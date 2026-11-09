import re

p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# MediaSessionService에 onStartCommand 오버라이드 추가 (블루투스 신호 직접 수신)
anchor = 'override fun onCreate() {'
if 'override fun onStartCommand' in s:
    print('ℹ️ 이미 onStartCommand 오버라이드가 있습니다.')
else:
    media_button_code = '''
    // ★★★ 블루투스/테슬라 Next/Prev 신호 직접 수신 ★★★
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        try {
            val keyCode = intent?.getIntExtra("android.intent.extra.KEY_EVENT", -1) ?: -1
            // KEYCODE_MEDIA_NEXT (87), KEYCODE_MEDIA_PREVIOUS (88)
            when (keyCode) {
                87 -> {
                    Log.d("PlaybackService", "onStartCommand: KEYCODE_MEDIA_NEXT")
                    val player = exoPlayer
                    if (player != null && player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    } else {
                        // 큐 끝 → 서비스에서 다음 곡 로드
                        serviceScope.launch {
                            val next = resolveNext()
                            if (next != null) playNext(next)
                        }
                    }
                }
                88 -> {
                    Log.d("PlaybackService", "onStartCommand: KEYCODE_MEDIA_PREVIOUS")
                    val player = exoPlayer
                    if (player != null && player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    } else {
                        player?.seekTo(0)
                    }
                }
                else -> {
                    Log.d("PlaybackService", "onStartCommand: keyCode=$keyCode (기타)")
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "onStartCommand err: ${e.message}")
        }
        return super.onStartCommand(intent, flags, startId)
    }

'''
    # onCreate 앞에 삽입
    if anchor in s:
        s = s.replace(anchor, media_button_code + anchor, 1)
        print('✅ onStartCommand 추가 완료 (블루투스 신호 수신)')
    else:
        print('❌ onCreate 앵커를 찾지 못했습니다.')

if s != orig:
    open(p, 'w', encoding='utf-8').write(s)
    print('💾 파일 저장 완료')
