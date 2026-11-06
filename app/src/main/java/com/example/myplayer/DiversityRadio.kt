package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 다양성 라디오: 같은 분위기 + 다양한 아티스트/장르/시대
 * + 음악이 아닌 영상(브이로그, 게임 등) 필터링 강화
 */
object DiversityRadio {

    // 현재 곡에서 추정한 장르/시대 키워드 풀
    private val GENRE_KEYWORDS = listOf(
        "발라드", "락 발라드", "헤비메탈", "인디 락", "모던 락",
        "80년대 발라드", "90년대 발라드", "80년대 락", "90년대 락",
        "K-POP 댄스", "K-POP 발라드", "OST", "카페 BGM", "드라이브 팝"
    )

    // ★★★ 음악 영상이 확실한 키워드 (하나라도 있으면 통과)
    private val MUSIC_GOOD_KEYWORDS = listOf(
        "가사", "lyrics", "lyric", "playlist", "플레이리스트", "노래모음", "노래 모음",
        "곡 모음", "메들리", "medley", "cover", "커버", "m/v", "mv", "뮤직비디오",
        "official", "오피셜", "audio", "오디오", "1시간", "hours", "1hour",
        "live", "라이브", "inst", "연주", "piano", "피아노", "guitar", "기타",
        "ost", "soundtrack", "사운드트랙", "remix", "리믹스"
    )

    // ★★★ 음악이 아닐 확률이 높은 키워드 (하나라도 있으면 제외)
    private val MUSIC_BAD_KEYWORDS = listOf(
        "브이로그", "vlog", "일상", "게임", "game", "플레이", "play", "먹방", "mukbang",
        "리뷰", "review", "언박싱", "unboxing", "꿀팁", "정보", "뉴스", "news",
        "예능", "드라마", "영화", "시사", "정치", "스포츠", "축구", "야구",
        "강의", "강연", "공부", "스터디", "요리", "레시피", "recipe", "운동",
        "화장", "뷰티", "beauty", "패션", "fashion", "여행", "travel",
        "조회수", "구독", "좋아요", "알림", "채널", "이야기", "사연", "썰",
        "tv", "방송", "토크", "인터뷰", "interview", "다큐", "documentary"
    )

    /** 음악 영상인지 판별 (제목 + 채널명 기반) */
    fun isMusicVideo(title: String, channel: String): Boolean {
        val text = "$title $channel".lowercase()

        // 1. 음악 제외 키워드가 있으면 즉시 탈락 (단, '라이브'는 예외로 처리)
        if (MUSIC_BAD_KEYWORDS.any { text.contains(it) } && !text.contains("라이브") && !text.contains("live")) {
            // '조회수', '구독' 등이 제목에 포함된 일상 영상들
            if (text.contains("조회수") || text.contains("구독") || text.contains("일상")) return false
        }

        // 2. 명확한 음악 키워드가 있으면 통과
        if (MUSIC_GOOD_KEYWORDS.any { text.contains(it) }) return true

        // 3. 채널명이 '- Topic' 이거나 'Music'을 포함하면 통과
        if (channel.contains("- Topic") || channel.contains("Music", ignoreCase = true)) return true

        // 4. 판단 불가 → 기본적으로 탈락 (엄격 모드)
        return false
    }

    /** 현재 곡의 제목/채널로부터 장르/시대 키워드 추정 */
    fun inferKeywords(title: String, channel: String): List<String> {
        val text = "$title $channel".lowercase()
        val picks = mutableListOf<String>()

        val genreMap = mapOf(
            "발라드" to listOf("발라드", "ballad"),
            "락" to listOf("락", "rock", "메탈", "metal"),
            "댄스" to listOf("댄스", "dance", "EDM", "일렉"),
            "힙합" to listOf("힙합", "hiphop", "rap", "랩"),
            "인디" to listOf("인디", "indie"),
            "OST" to listOf("ost", "사운드트랙"),
            "카페" to listOf("카페", "cafe", "BGM", "릴랙스", "relax")
        )
        for ((genre, keywords) in genreMap) {
            if (keywords.any { text.contains(it) }) {
                picks.add(genre)
            }
        }

        val eraMap = mapOf(
            "80년대" to listOf("80년대", "80s", "1980"),
            "90년대" to listOf("90년대", "90s", "1990"),
            "2000년대" to listOf("2000년대", "2000s"),
            "2020년대" to listOf("2020", "최신")
        )
        var era: String? = null
        for ((e, keywords) in eraMap) {
            if (keywords.any { text.contains(it) }) { era = e; break }
        }

        val result = mutableListOf<String>()
        if (picks.isNotEmpty()) {
            for (g in picks) {
                result.add(if (era != null) "$era $g" else g)
            }
        }
        if (result.isEmpty()) {
            result.addAll(GENRE_KEYWORDS.shuffled().take(3))
        }
        return result.distinct().take(3)
    }

    /** 여러 소스에서 병렬 수집 → 다양성 필터 → 음악 필터 → 최종 리스트 */
    suspend fun fetch(
        videoId: String,
        title: String,
        channel: String,
        excludeIds: Set<String>,
        recentArtists: Set<String>,
        max: Int = 10
    ): List<VideoItem> = withContext(Dispatchers.IO) {

        val keywords = inferKeywords(title, channel)

        val results = coroutineScope {
            val radio = async {
                try {
                    withTimeoutOrNull(4000) {
                        YouTubeRadio.fetchRelated(videoId, title, channel)
                    } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            val artist = async {
                try {
                    withTimeoutOrNull(4000) {
                        YouTubeArtist.fetchSongs(channel, videoId)
                    } ?: emptyList()
                } catch (_: Exception) { emptyList() }
            }
            // ★★★ 키워드 검색 시 "노래모음" 대신 "가사"나 "playlist" 조합 추가
            val keywordResults = keywords.map { kw ->
                async {
                    try {
                        withTimeoutOrNull(4000) {
                            YouTubeSearch.search("$kw 노래 가사 playlist")
                        } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                }
            }

            val baseRadio = radio.await()
            val baseArtist = artist.await()
            val keywordLists = keywordResults.map { it.await() }

            listOf(baseRadio, baseArtist) + keywordLists
        }

        val seenIds = excludeIds.toMutableSet()
        val artistCount = mutableMapOf<String, Int>()
        val output = mutableListOf<VideoItem>()

        for (list in results) {
            for (v in list) {
                if (v.videoId in seenIds) continue
                if (v.videoId == videoId) continue
                if (v.videoId.isBlank()) continue

                // ★★★ 1차 필터: 음악 영상인가?
                if (!isMusicVideo(v.title, v.channel)) continue

                // 최근 재생 아티스트 제외
                if (v.channel in recentArtists) continue

                // 아티스트당 최대 2곡
                val cnt = artistCount.getOrDefault(v.channel, 0)
                if (cnt >= 2) continue

                artistCount[v.channel] = cnt + 1
                seenIds.add(v.videoId)
                output.add(v)
                if (output.size >= max) return@withContext output
            }
        }

        // 부족하면 최근 재생 아티스트 조건 완화
        if (output.size < max) {
            for (list in results) {
                for (v in list) {
                    if (v.videoId in seenIds) continue
                    if (v.videoId == videoId) continue
                    if (v.videoId.isBlank()) continue

                    // ★★★ 2차 필터에서도 음악 영상만 통과
                    if (!isMusicVideo(v.title, v.channel)) continue

                    seenIds.add(v.videoId)
                    output.add(v)
                    if (output.size >= max) return@withContext output
                }
            }
        }

        output
    }
}
