package com.example.myplayer

import java.util.regex.Pattern

data class Chapter(
    val startMs: Long,
    var title: String
)

object YouTubeChapters {

    // "00:00 제목" 또는 "0:00 제목" 또는 "1:23:45 제목"
    private val PATTERN = Pattern.compile(
        "^\\s*(?:[-*•·]\\s*)?\\[?((?:\\d{1,3}:)?\\d{1,2}:\\d{2})\\]?\\s*(?:[-–—:·|]\\s*)?(.+)$",
        Pattern.MULTILINE
    )

    fun parse(description: String): List<Chapter> {
        if (description.isBlank()) return emptyList()
        val out = mutableListOf<Chapter>()

        for (line in description.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.length < 5) continue
            val m = PATTERN.matcher(trimmed)
            if (!m.find()) continue

            val timePart = m.group(1)
            val ms = parseTimestamp(timePart) ?: continue
            val title = m.group(2).trim()
                .trimStart('-', '–', '—', ':', '·', '|', ' ')
                .trim()

            if (title.isNotBlank() && ms >= 0) {
                out.add(Chapter(ms, title))
            }
        }

        // 정렬 + 중복 제거 + 최소 2개 이상일 때만 유효
        val sorted = out.distinctBy { it.startMs }.sortedBy { it.startMs }
        return if (sorted.size >= 2) sorted else emptyList()
    }

    private fun parseTimestamp(ts: String): Long? {
        val parts = ts.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> null
        }
    }
}
