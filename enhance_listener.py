import re

p = 'app/src/main/java/com/example/myplayer/PlaybackService.kt'
s = open(p, encoding='utf-8').read()
orig = s

# endListener의 onMediaItemTransition에 이미 큐 업데이트 로직이 있으므로,
# onSkipToNext로 인한 빈 큐 처리를 위해 onPositionDiscontinuity 추가
# 기존 endListener의 onPlaybackStateChanged 앞에 추가

old = '''        override fun onPlaybackStateChanged(playbackState: Int) {'''
new = '''        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // ★ 블루투스 Next/Prev 신호로 인한 위치 이동 감지 ★
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val currentId = newPosition.mediaItem?.mediaId
                if (currentId != null && currentId != newPosition.mediaItem?.mediaId) {
                    // 큐에 다음 곡이 없으면 다음 곡 로드
                    val player = exoPlayer ?: return
                    if (!player.hasNextMediaItem()) {
                        Log.d("PlaybackService", "onPositionDiscontinuity: 큐 끝, 다음 곡 필요")
                        // 서비스 내에서 다음 곡 로드 시도
                        serviceScope.launch {
                            val next = resolveNext()
                            if (next != null) playNext(next)
                        }
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {'''

if old in s and 'onPositionDiscontinuity' not in s:
    s = s.replace(old, new, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print('✅ endListener: onPositionDiscontinuity 추가 완료')
else:
    print('ℹ️ 이미 있거나 패턴 매칭 실패')
