package com.example.myplayer

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        lifecycleScope.launch {
            val list = try {
                HistoryDatabase.get(applicationContext).historyDao().getAll().first()
            } catch (e: Exception) { emptyList() }

            renderStats(list)
        }
    }

    private fun renderStats(list: List<HistoryEntity>) {
        // 1. 총 시청 시간
        val totalMs = list.sumOf { it.positionMs }
        val totalMin = totalMs / 1000 / 60
        val hours = totalMin / 60
        val mins = totalMin % 60
        val timeText = if (hours > 0) "${hours}시간 ${mins}분" else "${mins}분"
        findViewById<TextView>(R.id.tvTotalTime).text = timeText

        // 2. 오늘/이번주/이번달 카운트
        val now = System.currentTimeMillis()
        val startOfDay = getStartOfDay(now)
        val startOfWeek = startOfDay - (6L * 24 * 60 * 60 * 1000)  // 지난 7일
        val startOfMonth = startOfDay - (29L * 24 * 60 * 60 * 1000) // 지난 30일

        val todayCount = list.count { it.watchedAt >= startOfDay }
        val weekCount = list.count { it.watchedAt >= startOfWeek }
        val monthCount = list.count { it.watchedAt >= startOfMonth }

        findViewById<TextView>(R.id.tvTodayCount).text = todayCount.toString()
        findViewById<TextView>(R.id.tvWeekCount).text = weekCount.toString()
        findViewById<TextView>(R.id.tvMonthCount).text = monthCount.toString()

        // 3. 자주 본 채널 Top 5
        val llTopChannels = findViewById<LinearLayout>(R.id.llTopChannels)
        llTopChannels.removeAllViews()

        val channelCounts = list.map { it.channel }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(5)

        if (channelCounts.isEmpty()) {
            llTopChannels.addView(makeEmptyText("기록이 없습니다"))
        } else {
            val max = channelCounts.first().value.toFloat()
            for ((i, entry) in channelCounts.withIndex()) {
                llTopChannels.addView(makeChannelRow(i + 1, entry.key, entry.value, max))
            }
        }

        // 4. 시간대별
        val llTimeSlots = findViewById<LinearLayout>(R.id.llTimeSlots)
        llTimeSlots.removeAllViews()

        val slots = intArrayOf(0, 0, 0, 0)  // 아침, 점심, 저녁, 밤
        for (h in list) {
            val cal = Calendar.getInstance().apply { timeInMillis = h.watchedAt }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            when (hour) {
                in 6..11 -> slots[0]++
                in 12..17 -> slots[1]++
                in 18..23 -> slots[2]++
                else -> slots[3]++
            }
        }

        val labels = arrayOf("🌅 아침 (6-12)", "☀️ 점심 (12-18)", "🌆 저녁 (18-24)", "🌙 밤 (0-6)")
        val maxSlot = slots.maxOrNull()?.toFloat() ?: 1f

        for (i in slots.indices) {
            llTimeSlots.addView(makeTimeSlotRow(labels[i], slots[i], maxSlot))
        }
    }

    private fun getStartOfDay(now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun makeEmptyText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_secondary))
        setPadding(0, 8, 0, 8)
    }

    private fun makeChannelRow(rank: Int, name: String, count: Int, max: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
        }

        // 이름 + 개수
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "$rank. $name"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_primary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "${count}회"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_secondary))
        })
        row.addView(header)

        // 바
        val track = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
            setBackgroundResource(R.drawable.bg_progress_track)
        }

        val barContainer = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
        }
        barContainer.addView(track)

        val fill = View(this).apply {
            setBackgroundResource(R.drawable.bg_progress_fill)
        }
        val percent = (count / max * 100f).toInt().coerceIn(5, 100)
        barContainer.addView(fill, android.widget.FrameLayout.LayoutParams(0, (6 * resources.displayMetrics.density).toInt()))

        fill.post {
            val w = (barContainer.width * percent / 100f).toInt()
            val lp = fill.layoutParams
            lp.width = w
            fill.layoutParams = lp
        }

        row.addView(barContainer)
        return row
    }

    private fun makeTimeSlotRow(label: String, count: Int, max: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "${count}회"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_secondary))
        })
        row.addView(header)

        val barContainer = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
        }
        barContainer.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_progress_track)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        val fill = View(this).apply { setBackgroundResource(R.drawable.bg_progress_fill) }
        val percent = if (max > 0) (count / max * 100f).toInt().coerceIn(2, 100) else 2
        barContainer.addView(fill, android.widget.FrameLayout.LayoutParams(0, (6 * resources.displayMetrics.density).toInt()))

        fill.post {
            val w = (barContainer.width * percent / 100f).toInt()
            val lp = fill.layoutParams
            lp.width = w
            fill.layoutParams = lp
        }

        row.addView(barContainer)
        return row
    }
}
