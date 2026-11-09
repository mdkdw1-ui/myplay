p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# onGetSession에서 null 반환 방지 (mediaSession 재생성 시도)
old = '''    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession'''

new = '''    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? {
        // ★★★ [수정] mediaSession이 null이면 재생성 ★★★
        if (mediaSession == null) {
            try {
                val player = exoPlayer
                if (player != null) {
                    mediaSession = MediaSession.Builder(this, player)
                        .setSessionActivity(PendingIntent.getActivity(
                            this, 0,
                            Intent(this, PlayerActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        ))
                        .setCallback(object : MediaSession.Callback {
                            override fun onPostConnect(
                                session: MediaSession,
                                controller: MediaSession.ControllerInfo
                            ) {}
                        })
                        .build()
                }
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "onGetSession 재생성 err: ${e.message}")
            }
        }
        return mediaSession
    }'''

if old in s:
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print('✅ onGetSession 강화 완료')
else:
    print('⚠️ 패턴 매칭 실패')
