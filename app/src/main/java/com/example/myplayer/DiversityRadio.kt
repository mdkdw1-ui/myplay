package com.example.myplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 다양성 라디오: 같은 분위기 + 다양한 아티스트/장르/시대
 */
object DiversityRadio {

    // 현재 곡에서 추정한 장르/시대 키워드 풀
    private val GENRE_KEYWORDS = listOf(
        "발라드", "락 발라드", "헤비메탈", "인디 락", "모던 락",
        "80년대 발라드", "90년대 발라드", "80년대 락", "90년대 락",
        "K-POP 댄스", "K-POP 발라드", "OST", "카페 BGM", "드라이브 팝"
    )

    /** 현재 곡의 제목/채널로부터 장르/시대 키워드 추정 */
    fun inferKeywords(title: String, channel: String): List<String> {
        val text = "$title $channel".lowercase()
        val picks = mutableListOf<String>()

        // 1) 장르 직접 감지
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

        // 2) 시대 감지
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

        // 3) 조합 생성
        val result = mutableListOf<String>()
        if (picks.isNotEmpty()) {
            for (g in picks) {
                result.add(if (era != null) "$era $g" else g)
            }
        }
        // 장르 감지 실패 → 기본 풀에서 2~3개 랜덤
        if (result.isEmpty()) {
            val basePool = GENRE_KEYWORDS.shuffled().take(3)
            result.addAll(basePool)
        }
        return result.distinct().take(3)
    }

    /** 여러 소스에서 병렬 수집 → 다양성 필터 → 최종 리스트 */
    suspend fun fetch(
        videoId: String,
        title: String,
        channel: String,
        excludeIds: Set<String>,
        recentArtists: Set<String>,
        max: Int = 10
    ): List<VideoItem> = withContext(Dispatchers.IO) {

        val keywords = inferKeywords(title, channel)

        // 1) 병렬 수집
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
            // 장르/시대 키워드 병렬 검색
            val keywordResults = keywords.map { kw ->
                async {
                    try {
                        withTimeoutOrNull(4000) {
                            YouTubeSearch.search("$kw 플레이리스트")
                        } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                }
            }

            val baseRadio = radio.await()
            val baseArtist = artist.await()
            val keywordLists = keywordResults.map { it.await() }

            listOf(baseRadio, baseArtist) + keywordLists
        }

        // 2) 아티스트 중복 제거 + 다양성 필터
        val seenIds = excludeIds.toMutableSet()
        val artistCount = mutableMapOf<String, Int>()
        val output = mutableListOf<VideoItem>()

        for (list in results) {
            for (v in list) {
                if (v.videoId in seenIds) continue
                if (v.videoId == videoId) continue
                if (v.videoId.isBlank()) continue

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

        // 3) 부족하면 최근 재생 아티스트 제외 조건 완화
        if (output.size < max) {
            for (list in results) {
                for (v in list) {
                    if (v.videoId in seenIds) continue
                    if (v.videoId == videoId) continue
                    if (v.videoId.isBlank()) continue
                    seenIds.add(v.videoId)
                    output.add(v)
                    if (output.size >= max) return@withContext output
                }
            }
        }

        output
    }
}
