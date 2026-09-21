package com.example.myplayer

import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnCc: MaterialButton
    private lateinit var btnTranscript: MaterialButton
    private lateinit var btnQueue: MaterialButton
    private lateinit var btnBookmark: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnLock: MaterialButton
    private lateinit var btnMore: MaterialButton
    private lateinit var btnLiveSub: MaterialButton
    private lateinit var btnAudioMode: MaterialButton
    private lateinit var lockOverlay: View
    private var isLocked = false
    private var pipAspect: Float = 16f / 9f
    private lateinit var infoScroll: View
    private lateinit var videoContainer: View
    private lateinit var summaryCard: View
    private lateinit var tvSummary: TextView
    private lateinit var tvSummaryBadge: TextView
    private lateinit var seekOverlayLeft: View
    private lateinit var seekOverlayRight: View
    private lateinit var autoNextOverlay: View
    private lateinit var tvNextCountdown: TextView
    private lateinit var swAutoNext: SwitchMaterial
    private lateinit var swSponsorBlock: SwitchMaterial
    private var sbEnabled: Boolean = true
    private var sbCategories: List<String> = SponsorBlock.DEFAULT_CATEGORIES
    private var sponsorSegments: List<SkipSegment> = emptyList()
    private var sbCheckJob: Job? = null
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private var isFullscreen = false
    private var liveSubtitleActive = false
    private var mediaProjection: MediaProjection? = null
    private var liveCaptureManager: AudioCaptureManager? = null
    private var liveGroqManager: GroqSttManager? = null
    private lateinit var liveSubtitleOverlay: View
    private lateinit var tvLiveSubtitle: TextView
    private val liveBuilder = StringBuilder()
    private var lastLiveText = ""
    private var currentSpeed = 1.0f

    private var subtitleTracks: List<SubtitleTrack> = emptyList()
    private var currentQualities: List<VideoQuality> = emptyList()
    private var currentAudioUrl: String? = null
    private var currentVideoBestUrl: String? = null
    private var currentStreamUrl: String? = null

    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""

    private var autoNextEnabled: Boolean = false
    private var countdownJob: kotlinx.coroutines.Job? = null
    private var previewShown = false
    private var previewJob: kotlinx.coroutines.Job? = null

    private val hideControlsRunnable = Runnable {
        findViewById<View>(R.id.controlScroll)?.visibility = View.GONE
    }

    private val pref by lazy { getSharedPreferences("subtitle_prefs", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private val gestureHideRunnable = Runnable {
        findViewById<View>(R.id.gestureBar)?.visibility = View.GONE
    }

    // ★ 재생 상태 리스너
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onVideoEnded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // ★ 화면 항상 켜짐 (기본 ON)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        liveSubtitleOverlay = findViewById(R.id.liveSubtitleOverlay)
        tvLiveSubtitle = findViewById(R.id.tvLiveSubtitle)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnCc = findViewById(R.id.btnCc)
        btnTranscript = findViewById(R.id.btnTranscript)
        btnQueue = findViewById(R.id.btnQueue)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnShare = findViewById(R.id.btnShare)
        btnLock = findViewById(R.id.btnLock)
        btnMore = findViewById(R.id.btnMore)
        btnLiveSub = findViewById(R.id.btnLiveSub)
        btnAudioMode = findViewById(R.id.btnAudioMode)
        val btnAb = findViewById<MaterialButton>(R.id.btnAbRepeat)
        lockOverlay = findViewById(R.id.lockOverlay)
        btnDownload = findViewById(R.id.btnDownload)
        infoScroll = findViewById(R.id.infoScroll)
        videoContainer = findViewById(R.id.videoContainer)
        summaryCard = findViewById(R.id.summaryCard)
        tvSummary = findViewById(R.id.tvSummary)
        tvSummaryBadge = findViewById(R.id.tvSummaryBadge)
        seekOverlayLeft = findViewById(R.id.seekOverlayLeft)
        seekOverlayRight = findViewById(R.id.seekOverlayRight)
        autoNextOverlay = findViewById(R.id.autoNextOverlay)
        tvNextCountdown = findViewById(R.id.tvNextCountdown)
        swAutoNext = findViewById(R.id.swAutoNext)

        autoNextEnabled = pref.getBoolean("auto_next", false)
        swAutoNext.isChecked = autoNextEnabled
        swAutoNext.setOnCheckedChangeListener { _, checked ->
            autoNextEnabled = checked
            pref.edit().putBoolean("auto_next", checked).apply()
        }

        swSponsorBlock = findViewById(R.id.swSponsorBlock)
        sbEnabled = pref.getBoolean("sb_enabled", true)
        val savedCats = pref.getString("sb_categories", null)
        sbCategories = if (savedCats.isNullOrBlank())
            SponsorBlock.DEFAULT_CATEGORIES
        else savedCats.split(",").filter { it.isNotBlank() }

        swSponsorBlock.isChecked = sbEnabled
        swSponsorBlock.setOnCheckedChangeListener { _, checked ->
            sbEnabled = checked
            pref.edit().putBoolean("sb_enabled", checked).apply()
            if (checked && currentVideoId.isNotBlank()) {
                loadSponsorSegments(currentVideoId)
            } else if (!checked) {
                sponsorSegments = emptyList()
            }
        }
        // 스위치 또는 라벨 어디든 탭/롱프레스 → 카테고리 선택
        swSponsorBlock.setOnLongClickListener {
            showSbCategoryDialog()
            true
        }
        findViewById<View>(R.id.tvSbLabel).setOnClickListener {
            showSbCategoryDialog()
        }
        findViewById<View>(R.id.tvSbLabel).setOnLongClickListener {
            showSbCategoryDialog()
            true
        }

        findViewById<MaterialButton>(R.id.btnCancelAutoNext).setOnClickListener {
            cancelAutoNext()
        }

        progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@PlayerActivity, R.color.spinner)
            )
            // ★ 크기 40dp (기본 48dp)
            layoutParams = ViewGroup.LayoutParams(
                (40 * resources.displayMetrics.density).toInt(),
                (40 * resources.displayMetrics.density).toInt()
            )
        }
        (playerView.parent as? ViewGroup)?.addView(progress)

        // ★ 저장된 재생 속도 복원
        currentSpeed = pref.getFloat("saved_speed", 1.0f)
        btnSpeed.text = "${currentSpeed}x"

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        applySubtitleStyle()
        setupGestures()
        setupSubtitleDrag()

        val videoUri = intent.getStringExtra("VIDEO_URI")
        val videoId = intent.getStringExtra("VIDEO_ID")
        // ★ 방어: 빈 ID로 진입 시 즉시 종료
        if (videoId.isNullOrBlank() || videoId.startsWith("local:")) {
            android.widget.Toast.makeText(this,
                "이 항목은 영상 모드가 없습니다",
                android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val vTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val vChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val vThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        currentVideoId = videoId ?: ""
        currentTitle = vTitle
        currentChannel = vChannel
        currentThumb = vThumb

        (findViewById<TextView>(R.id.tvTitle)).text = vTitle
        (findViewById<TextView>(R.id.tvChannel)).text = vChannel
        (findViewById<TextView>(R.id.tvDescription)).text = "불러오는 중..."

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            mediaController?.addListener(playerListener)   // ★ 리스너 등록
            playerView.player = mediaController

            when {
                videoUri != null -> playDirectUrl(videoUri)
                videoId != null -> checkResumeAndPlay(videoId, vTitle, vChannel, vThumb)
                else -> Toast.makeText(this, "재생할 영상이 없습니다", Toast.LENGTH_SHORT).show()
            }

            if (!videoId.isNullOrBlank()) refreshBookmarkState(videoId)
        }, MoreExecutors.directExecutor())

        showControls()

        updateLiveSubButton()

        btnSpeed.setOnClickListener { showSpeedDialog() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPip).apply {
            setOnClickListener { enterPipMode() }
            setOnLongClickListener {
                showPipSizeDialog()
                true
            }
        }
        btnCc.setOnClickListener { showSubtitleDialog() }
        btnTranscript.setOnClickListener { openTranscript() }
        btnQueue.setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnShare.setOnClickListener { showShareDialog() }
        findViewById<View>(R.id.tvChannel).setOnClickListener { openChannelFromPlayer() }
        btnLock.setOnClickListener { toggleLock() }
        btnMore.setOnClickListener { showMoreMenu() }
        btnLiveSub.setOnClickListener { toggleLiveSubtitle() }
        btnAudioMode.setOnClickListener { switchToAudioMode() }

        // ★ 하단 고정 컨트롤바
        setupBottomControls()
        btnAb.setOnClickListener { cycleAbRepeat() }
        lockOverlay.setOnClickListener { toggleLock() }
        btnDownload.setOnClickListener { startDownload() }
    }

    // ========== 🔗 타임스탬프 인식 ==========
    private val tsPattern = Pattern.compile("(?<![\\d:])(?:\\d{1,2}:)?\\d{1,2}:\\d{2}(?![\\d:])")

    private fun makeTimestampsClickable(textView: TextView, text: String) {
        if (text.isBlank()) {
            textView.text = text
            return
        }
        val spannable = SpannableString(text)
        val matcher = tsPattern.matcher(text)
        val accent = ContextCompat.getColor(this, R.color.accent)

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val ts = text.substring(start, end)
            val ms = parseTimestamp(ts)
            if (ms <= 0) continue

            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        mediaController?.seekTo(ms)
                        Toast.makeText(
                            this@PlayerActivity,
                            "$ts 로 이동",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = accent
                        ds.isUnderlineText = false
                        ds.isFakeBoldText = true
                    }
                },
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.text = spannable
    }

    private fun parseTimestamp(ts: String): Long {
        val parts = ts.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> 0L
        }
    }

    // ========== ▶ 자동 다음 동영상 ==========
    private fun onVideoEnded() {
        if (!autoNextEnabled) return
        // 카운트다운 시작
        cancelAutoNext()
        autoNextOverlay.visibility = View.VISIBLE
        tvNextCountdown.text = "5"

        countdownJob = lifecycleScope.launch {
            for (i in 5 downTo 1) {
                tvNextCountdown.text = i.toString()
                kotlinx.coroutines.delay(1000)
            }
            autoNextOverlay.visibility = View.GONE
            playNextRelated()
        }
    }

    private fun cancelAutoNext() {
        countdownJob?.cancel()
        countdownJob = null
        autoNextOverlay.visibility = View.GONE
    }

    /** 음악 아닌 영상(말 많은 것) 필터 */
    private fun isMusicLike(item: com.example.myplayer.VideoItem): Boolean {
        // 1) 제목 키워드 필터
        // 제목 키워드 필터
        val badKeywords = listOf(
            "뉴스", "속보", "인터뷰", "강연", "토크", "팟캐스트", "podcast",
            "ep.", "회차", "라이브", "생방송", "예능", "드라마", "시사",
            "뉴스룸", "속보", "긴급", "특집", "다큐", "설명", "강의",
            "한국사", "역사", "과학", "다큐멘터리", "웨비나", "세미나"
        )
        if (badKeywords.any { item.title.contains(it, ignoreCase = true) }) return false
        // 너무 짧은 영상 (쇼츠/클립) 제외
        // (VideoItem에 durationMs가 없으면 스킵)
        return true
    }

    private fun playNextRelated() {
        // ★ 큐에 다음 영상이 있으면 큐 우선
        val queue = QueueManager.get(this)
        val nextInQueue = queue.firstOrNull { it.videoId != currentVideoId }
        if (nextInQueue != null) {
            QueueManager.remove(this, nextInQueue.videoId)
            currentVideoId = nextInQueue.videoId
            currentTitle = nextInQueue.title
            currentChannel = nextInQueue.channel
            currentThumb = nextInQueue.thumbnail
            extractAndPlay(nextInQueue.videoId, nextInQueue.title, nextInQueue.channel, nextInQueue.thumbnail, 0L)
            return
        }

        val vid = currentVideoId
        if (vid.isBlank()) return

        lifecycleScope.launch {
            // 1) next 엔드포인트
            var list = YouTubeRelated.fetch(vid)
            // 2) 폴백: 제목 키워드 검색
            if (list.isEmpty()) {
                val kw = currentTitle.split(" ")
                    .filter { it.isNotBlank() }.take(4).joinToString(" ")
                if (kw.isNotEmpty()) {
                    list = YouTubeSearch.search(kw).filter { it.videoId != vid }
                }
            }
            // ★ 음악 아닌 영상(말 많은 것) 필터
            val musicOnly = list.filter { isMusicLike(it) }
            val next = (musicOnly.ifEmpty { list }).firstOrNull() ?: return@launch

            // ★ 같은 Activity에서 다음 영상 재생
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail

            extractAndPlay(next.videoId, next.title, next.channel, next.thumbnail, 0L)
        }
    }

    // ========== 제스처 ==========
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = playerView.width
                if (width <= 0) return false
                val x = e.x
                when {
                    x < width / 3f -> seekBy(-10_000L, true)
                    x > width * 2 / 3f -> seekBy(10_000L, false)
                    else -> togglePlayPause()
                }
                return true
            }
        })

        var downY = 0f
        var startVol = 0
        var startBright = 0f
        var isVolume = false
        var gestureActive = false
        val threshold = 40f

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            // ★ 컨트롤 자동 숨김 재시작
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                showControls()
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBright = window.attributes.screenBrightness.let {
                        if (it < 0) 0.5f else it
                    }
                    isVolume = event.x < playerView.width / 2f
                    gestureActive = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downY - event.y // 위로 = +
                    if (!gestureActive && Math.abs(dy) > threshold) {
                        gestureActive = true
                    }
                    if (gestureActive) {
                        val ratio = dy / playerView.height
                        if (isVolume) {
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val newVol = (startVol + ratio * max).toInt().coerceIn(0, max)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            val pct = (newVol * 100f / max).toInt()
                            showGestureFeedback(if (newVol == 0) "🔇 음소거" else "🔊 $pct%")
                        } else {
                            val newBright = (startBright + ratio).coerceIn(0.05f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = newBright }
                            showGestureFeedback("☀️ ${(newBright * 100).toInt()}%")
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureActive) {
                        gestureActive = false
                        mainHandler.postDelayed(gestureHideRunnable, 400)
                    }
                }
            }
            false
        }
    }

    private fun showGestureFeedback(text: String) {
        // 새 HUD
        val bar = findViewById<View>(R.id.gestureBar)
        val icon = findViewById<TextView>(R.id.tvGestureIcon)
        val pct = findViewById<TextView>(R.id.tvGesturePct)
        val fill = findViewById<View>(R.id.gestureBarFill)

        val isVolume = text.contains("🔊") || text.contains("🔇")
        icon.text = if (text.contains("🔇")) "🔇" else if (isVolume) "🔊" else "☀️"

        // 퍼센트 추출
        val num = Regex("(\\d+)%").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 50
        pct.text = "$num%"

        // 바 높이
        fill.post {
            val parent = fill.parent as View
            val h = (parent.height * num / 100f).toInt()
            val lp = fill.layoutParams
            lp.height = h
            fill.layoutParams = lp
        }

        bar.visibility = View.VISIBLE
        mainHandler.removeCallbacks(gestureHideRunnable)
    }

    private fun seekBy(deltaMs: Long, isLeft: Boolean) {
        val mc = mediaController ?: return
        val dur = mc.duration
        if (dur <= 0) return
        val newPos = (mc.currentPosition + deltaMs).coerceIn(0L, dur)
        mc.seekTo(newPos)
        val overlay = if (isLeft) seekOverlayLeft else seekOverlayRight
        overlay.visibility = View.VISIBLE
        mainHandler.postDelayed({ overlay.visibility = View.GONE }, 500)
    }

    private fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    // ========== 공유 ==========
    private fun showShareDialog() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "공유할 수 없는 영상입니다", Toast.LENGTH_SHORT).show()
            return
        }
        val youtubeUrl = "https://www.youtube.com/watch?v=$currentVideoId"
        val items = arrayOf(
            "📱 공유하기",
            "▶ YouTube 앱에서 열기",
            "🔗 URL 복사",
            "🌐 브라우저에서 열기"
        )
        AlertDialog.Builder(this).setTitle("공유")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> shareViaIntent(youtubeUrl)
                    1 -> openYouTubeApp(youtubeUrl)
                    2 -> copyToClipboard(youtubeUrl)
                    3 -> openBrowser(youtubeUrl)
                }
            }.show()
    }

    private fun shareViaIntent(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
            putExtra(Intent.EXTRA_TEXT, "$currentTitle\n$url")
        }
        startActivity(Intent.createChooser(intent, "공유"))
    }

    private fun openYouTubeApp(url: String) {
        val ytAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$currentVideoId"))
        try { startActivity(ytAppIntent) } catch (e: ActivityNotFoundException) { openBrowser(url) }
    }

    private fun copyToClipboard(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YouTube URL", url))
        Toast.makeText(this, "URL 복사됨", Toast.LENGTH_SHORT).show()
    }

    private fun openBrowser(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { Toast.makeText(this, "브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
    }

    // ========== 북마크 ==========
    private fun refreshBookmarkState(videoId: String) {
        if (videoId.isBlank()) return
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                try {
                    HistoryDatabase.get(applicationContext).bookmarkDao().isBookmarked(videoId)
                } catch (e: Exception) { false }
            }
            btnBookmark.text = if (exists) "★" else "☆"
        }
    }

    private fun toggleBookmark() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "저장할 수 없는 영상입니다", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val db = HistoryDatabase.get(applicationContext)
                val exists = withContext(Dispatchers.IO) {
                    db.bookmarkDao().isBookmarked(currentVideoId)
                }
                if (exists) {
                    withContext(Dispatchers.IO) { db.bookmarkDao().delete(currentVideoId) }
                    btnBookmark.text = "☆"
                    Toast.makeText(this@PlayerActivity, "북마크 해제", Toast.LENGTH_SHORT).show()
                } else {
                    withContext(Dispatchers.IO) {
                        db.bookmarkDao().insert(
                            BookmarkEntity(
                                videoId = currentVideoId, title = currentTitle,
                                channel = currentChannel, thumbnail = currentThumb,
                                savedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    btnBookmark.text = "★"
                    Toast.makeText(this@PlayerActivity, "북마크 저장", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== 이어보기 ==========
    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun checkResumeAndPlay(videoId: String, title: String, channel: String, thumb: String) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    HistoryDatabase.get(applicationContext).historyDao().getById(videoId)
                } catch (e: Exception) { null }
            }
            val pos = saved?.positionMs ?: 0L
            val dur = saved?.durationMs ?: 0L
            val canResume = pos > 30_000L && (dur == 0L || dur - pos > 30_000L)

            if (canResume) showResumeDialog(videoId, title, channel, thumb, pos, dur)
            else extractAndPlay(videoId, title, channel, thumb, 0L)
        }
    }

    private fun showResumeDialog(
        videoId: String, title: String, channel: String, thumb: String,
        posMs: Long, durMs: Long
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_resume)
        dialog.setCancelable(false)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.findViewById<TextView>(R.id.tvResumeTime).text = formatTime(posMs)
        if (durMs > 0) {
            val percent = ((posMs * 100) / durMs).toInt().coerceIn(0, 100)
            val fill = dialog.findViewById<View>(R.id.vProgressFill)
            fill.post {
                val parent = fill.parent as View
                val w = (parent.width * percent / 100f).toInt()
                val lp = fill.layoutParams
                lp.width = w
                fill.layoutParams = lp
            }
        }
        dialog.findViewById<MaterialButton>(R.id.btnResume).setOnClickListener {
            dialog.dismiss()
            extractAndPlay(videoId, title, channel, thumb, posMs)
        }
        dialog.findViewById<MaterialButton>(R.id.btnStartOver).setOnClickListener {
            dialog.dismiss()
            extractAndPlay(videoId, title, channel, thumb, 0L)
        }
        dialog.show()
    }

    private fun savePosition() {
        val mc = mediaController ?: return
        if (currentVideoId.isBlank()) return
        val pos = mc.currentPosition
        val dur = mc.duration
        if (pos <= 0) return
        val durationMs = if (dur > 0) dur else 0L
        val appCtx = applicationContext
        val vid = currentVideoId
        GlobalScope.launch(Dispatchers.IO) {
            try {
                HistoryDatabase.get(appCtx).historyDao().updatePosition(vid, pos, durationMs)
            } catch (e: Exception) { }
        }
    }

    // ========== AI 요약 ==========
    private fun buildSummary(videoId: String, title: String, description: String) {
        summaryCard.visibility = View.VISIBLE
        tvSummaryBadge.text = "AI 요약 중..."
        tvSummary.text = "잠시만 기다려주세요..."
        lifecycleScope.launch {
            var transcript = ""
            val preferred = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
                ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
                ?: subtitleTracks.firstOrNull()
            if (preferred != null) transcript = YouTubeTranscript.fetchText(preferred.url)
            val source = if (transcript.isNotBlank()) {
                tvSummaryBadge.text = if (preferred?.isAutoGenerated == true) "AI 요약 · 자동 자막" else "AI 요약 · 자막"
                transcript
            } else if (description.isNotBlank()) {
                tvSummaryBadge.text = "AI 요약 · 설명"
                description
            } else {
                tvSummaryBadge.text = "AI 요약"
                ""
            }
            if (source.isBlank()) {
                tvSummary.text = "요약할 내용이 없습니다."
                return@launch
            }
            val summary = GeminiSummary.summarize(title, source)
            tvSummary.text = if (summary.isBlank()) "요약을 생성할 수 없습니다." else summary
        }
    }

    // ========== 자막 ==========
    private fun applySubtitleStyle() {
        val sizeSp = pref.getFloat("size_sp", 16f)
        val textColor = pref.getInt("text_color", Color.WHITE)
        val bgColor = pref.getInt("bg_color", 0xB3000000.toInt())
        val edgeType = pref.getInt("edge", CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW)
        val style = CaptionStyleCompat(textColor, bgColor, Color.TRANSPARENT, edgeType, Color.BLACK, null)
        playerView.subtitleView?.setStyle(style)
        playerView.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        playerView.subtitleView?.setBottomPaddingFraction(0.08f)
    }

    private fun showSubtitleStyleDialog() {
        val items = arrayOf("크기", "글자 색상", "배경 색상", "테두리/그림자", "기본값으로 초기화")
        AlertDialog.Builder(this).setTitle("자막 스타일")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showSizeDialog()
                    1 -> showColorDialog("text_color", "글자 색상")
                    2 -> showColorDialog("bg_color", "배경 색상")
                    3 -> showEdgeDialog()
                    4 -> { pref.edit().clear().apply(); applySubtitleStyle() }
                }
            }.show()
    }

    private fun showSizeDialog() {
        val sizes = listOf(12f, 14f, 16f, 18f, 20f, 22f, 24f, 28f)
        AlertDialog.Builder(this).setTitle("자막 크기")
            .setItems(sizes.map { "${it.toInt()}sp" }.toTypedArray()) { _, i ->
                pref.edit().putFloat("size_sp", sizes[i]).apply()
                applySubtitleStyle()
            }.show()
    }

    private fun showColorDialog(key: String, title: String) {
        val colors = listOf(
            "흰색" to Color.WHITE, "노랑" to Color.YELLOW, "시안" to Color.CYAN,
            "연두" to Color.GREEN, "주황" to 0xFFFFA500.toInt(), "분홍" to 0xFFFFC0CB.toInt(),
            "검정" to Color.BLACK, "반투명 검정" to 0xB3000000.toInt(),
            "반투명 회색" to 0x80888888.toInt(), "반투명 흰색" to 0x80FFFFFF.toInt(),
            "없음(투명)" to Color.TRANSPARENT
        )
        AlertDialog.Builder(this).setTitle(title)
            .setItems(colors.map { it.first }.toTypedArray()) { _, i ->
                pref.edit().putInt(key, colors[i].second).apply()
                applySubtitleStyle()
            }.show()
    }

    private fun showEdgeDialog() {
        val edges = listOf(
            "없음" to CaptionStyleCompat.EDGE_TYPE_NONE,
            "외곽선" to CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            "그림자" to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        )
        AlertDialog.Builder(this).setTitle("테두리/그림자")
            .setItems(edges.map { it.first }.toTypedArray()) { _, i ->
                pref.edit().putInt("edge", edges[i].second).apply()
                applySubtitleStyle()
            }.show()
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog.Builder(this).setTitle("재생 속도")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                currentSpeed = speeds[i]
                mediaController?.setPlaybackSpeed(currentSpeed)
                btnSpeed.text = "${currentSpeed}x"
                // ★ 저장 (다음 영상에도 적용)
                pref.edit().putFloat("saved_speed", currentSpeed).apply()
            }.show()
    }

    private fun playDirectUrl(url: String) {
        currentStreamUrl = url
        mediaController?.setMediaItem(MediaItem.fromUri(url))
        mediaController?.prepare()
        mediaController?.playWhenReady = true
        mediaController?.setPlaybackSpeed(currentSpeed)
        (findViewById<TextView>(R.id.tvDescription)).text = "(URL 직접 재생)"
    }

    private fun extractAndPlay(
        videoId: String, title: String, channel: String, thumb: String, startPosMs: Long = 0L
    ) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = YouTubeStream.extract(videoId)
            progress.visibility = View.GONE

            if (!result.hasAny) {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("재생 실패")
                    .setMessage(result.debug)
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }

            val finalTitle = result.title.ifEmpty { title }
            val finalChannel = result.channelName.ifEmpty { channel }

            currentTitle = finalTitle
            currentChannel = finalChannel
            currentThumb = thumb

            (findViewById<TextView>(R.id.tvTitle)).text = finalTitle
            (findViewById<TextView>(R.id.tvChannel)).text = finalChannel

            val descText = if (result.description.isNotBlank()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Html.fromHtml(result.description, Html.FROM_HTML_MODE_LEGACY).toString()
                    } else {
                        @Suppress("DEPRECATION")
                        Html.fromHtml(result.description).toString()
                    }
                } catch (e: Exception) {
                    result.description.replace(Regex("<[^>]+>"), " ")
                }.replace(Regex("\\s+"), " ").trim()
            } else "(설명 없음)"

            // ★ 타임스탬프 클릭 가능하게
            makeTimestampsClickable(findViewById(R.id.tvDescription), descText)

            subtitleTracks = result.subtitles
            currentQualities = result.qualities
            currentAudioUrl = result.audioUrlBest
            currentVideoBestUrl = result.videoUrl
            currentQualities = result.qualities
            currentAudioUrl = result.audioUrlBest
            currentVideoBestUrl = result.videoUrl
            val streamUrl = result.muxedUrl ?: result.videoUrl ?: result.audioUrl

            // ★ 자동 번역 없이 원본 자막만 (429 방지)
            val autoSub = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
                ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
                ?: subtitleTracks.firstOrNull()
            applyStreamWithSubtitle(streamUrl, autoSub, null, startPosMs)
            updateCcButton(autoSub != null)
            loadSponsorSegments(videoId)
            startPreviewWatcher()
            summaryCard.visibility = View.VISIBLE
            tvSummaryBadge.text = "AI 요약 · 탭해서 생성"
            tvSummary.text = "탭하면 Gemini가 자막을 요약합니다."
            summaryCard.setOnClickListener {
                summaryCard.setOnClickListener(null)
                buildSummary(videoId, finalTitle, result.description)
            }
            saveHistory(videoId, finalTitle, finalChannel, thumb, startPosMs)
        }
    }

    private fun updateCcButton(active: Boolean) { btnCc.alpha = if (active) 1.0f else 0.5f }

    private fun applyStreamWithSubtitle(url: String?, sub: SubtitleTrack?, targetLang: String?, startPosMs: Long) {
        if (url.isNullOrBlank()) return
        currentStreamUrl = url
        val builder = MediaItem.Builder().setUri(url)
        if (sub != null) {
            val rawUrl = if (targetLang != null) buildTranslatedUrl(sub.url, targetLang) else sub.url
            val vttUrl = ensureVttFormat(rawUrl)

            // ★ 자막 URL 캐시 (429 방지)
            val cacheKey = "sub_cache_${currentVideoId}_${sub.languageCode}_${targetLang ?: "orig"}"
            val cached = pref.getString(cacheKey, null)
            val finalVttUrl = if (cached != null) cached else {
                pref.edit().putString(cacheKey, vttUrl).apply()
                vttUrl
            }
            // (실제로는 URL만 캐시, 파일 캐시는 별도)
            val label = if (targetLang != null) "${sub.displayName} → 한국어" else sub.displayName
            val configs = mutableListOf<MediaItem.SubtitleConfiguration>()

            if (targetLang != null) {
                configs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(vttUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(targetLang)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
                val origUrl = ensureVttFormat(sub.url)
                configs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(origUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(sub.languageCode)
                        .setLabel("${sub.displayName} (원본)")
                        .build()
                )
            } else {
                configs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(vttUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(sub.languageCode)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }

            builder.setSubtitleConfigurations(configs)
        }
        // ★ 재생 중이면 setMediaItem 대신 자막만 다시 적용
        // (Media3는 setMediaItem 시 버퍼 리셋되므로)
        val mc = mediaController
        if (mc != null && mc.isPlaying && startPosMs > 0) {
            // 재생 중 자막만 변경 시도
            try {
                mc.setMediaItem(builder.build(), startPosMs)
                mc.prepare()
                mc.playWhenReady = true
            } catch (e: Exception) {
                // 실패 시 처음부터
                mc.setMediaItem(builder.build(), 0L)
                mc.prepare()
                mc.playWhenReady = true
            }
        } else {
            mc?.setMediaItem(builder.build(), startPosMs)
            mc?.prepare()
            mc?.playWhenReady = true
        }
        mc?.setPlaybackSpeed(currentSpeed)
        // applySubtitleStyle 재호출 제거 (매번 실행 X)
    }

    private fun ensureVttFormat(url: String): String =
        if (url.contains("fmt=")) url.replace(Regex("fmt=[a-zA-Z0-9]+"), "fmt=vtt")
        else if (url.contains("?")) "$url&fmt=vtt" else "$url?fmt=vtt"

    private fun buildTranslatedUrl(originalUrl: String, targetLang: String): String =
        if (originalUrl.contains("tlang=")) originalUrl.replace(Regex("tlang=[a-zA-Z\\-]+"), "tlang=$targetLang")
        else if (originalUrl.contains("?")) "$originalUrl&tlang=$targetLang"
        else "$originalUrl?tlang=$targetLang"

    private fun showSubtitleDialog() {
        if (subtitleTracks.isEmpty()) {
            AlertDialog.Builder(this).setTitle("자막")
                .setMessage("이 영상엔 자막이 없습니다\n\n" +
                        "• 트레일러/음악 영상은 YouTube가 자동 자막을 안 만들 수 있습니다\n" +
                        "• 잠시 후 다시 시도해보세요")
                .setPositiveButton("확인", null)
                .setNeutralButton("자막 스타일") { _, _ -> showSubtitleStyleDialog() }
                .show()
            return
        }
        val labels = mutableListOf<String>()
        val callbacks = mutableListOf<() -> Unit>()
        labels.add("자막 끄기")
        callbacks.add {
            applyStreamWithSubtitle(currentStreamUrl, null, null, mediaController?.currentPosition ?: 0L)
            updateCcButton(false)
        }
        for (s in subtitleTracks) {
            val auto = if (s.isAutoGenerated) " · 자동" else ""
            val name = "${s.displayName}$auto"
            val pos = mediaController?.currentPosition ?: 0L
            labels.add(name)
            callbacks.add { applyStreamWithSubtitle(currentStreamUrl, s, null, pos); updateCcButton(true) }
            // ★ 번역 옵션 (한국어만)
            if (!s.languageCode.startsWith("ko")) {
                labels.add("$name → 🇰🇷 한국어 (⚠️ YouTube 버그로 자주 실패)")
                callbacks.add { applyStreamWithSubtitle(currentStreamUrl, s, "ko", pos); updateCcButton(true) }
            }
            // ★ 영어 번역도 추가
            if (!s.languageCode.startsWith("en")) {
                labels.add("$name → 🇺🇸 English")
                callbacks.add { applyStreamWithSubtitle(currentStreamUrl, s, "en", pos); updateCcButton(true) }
            }
            // ★ 일본어 번역
            if (!s.languageCode.startsWith("ja")) {
                labels.add("$name → 🇯🇵 日本語")
                callbacks.add { applyStreamWithSubtitle(currentStreamUrl, s, "ja", pos); updateCcButton(true) }
            }
        }
        labels.add("⚙️ 자막 스타일")
        callbacks.add { showSubtitleStyleDialog() }

        // ★ 모든 비한국어 자막에 AI 번역 옵션
        val nonKorean = subtitleTracks.filter { !it.languageCode.startsWith("ko") }
            .distinctBy { it.languageCode }
        for (sub in nonKorean) {
            val langLabel = sub.displayName.ifBlank { sub.languageCode }
            labels.add("🤖 [AI 번역] $langLabel → 한국어")
            callbacks.add { translateAndApply(sub) }
        }

        // ★ AI 번역 옵션 (영어 자막 있으면)

        AlertDialog.Builder(this).setTitle("자막 선택")
            .setItems(labels.toTypedArray()) { _, i -> callbacks[i].invoke() }
            .show()
    }

    private suspend fun saveHistory(
        videoId: String, title: String, channel: String, thumb: String, startPosMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        try {
            HistoryDatabase.get(applicationContext).historyDao().insert(
                HistoryEntity(
                    videoId = videoId, title = title, channel = channel, thumbnail = thumb,
                    watchedAt = System.currentTimeMillis(),
                    positionMs = startPosMs, durationMs = 0L
                )
            )
        } catch (e: Exception) { e.printStackTrace() }
    }


    // ========== 📥 다운로드 ==========
    private var isDownloading = false
    private var subtitleOffsetX = 0f
    private var subtitleOffsetY = 0f
    private var subtitleDragActive = false
    private var abStart: Long = -1L
    private var abEnd: Long = -1L
    private var abJob: kotlinx.coroutines.Job? = null

    private fun startDownload() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "다운로드할 수 없는 영상입니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (isDownloading) {
            Toast.makeText(this, "이미 다운로드 중입니다", Toast.LENGTH_SHORT).show()
            return
        }

        val streamUrl = currentStreamUrl
        if (streamUrl.isNullOrBlank()) {
            Toast.makeText(this, "스트림이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val exists = try {
                HistoryDatabase.get(applicationContext).downloadDao().isDownloaded(currentVideoId)
            } catch (e: Exception) { false }

            if (exists) {
                Toast.makeText(this@PlayerActivity, "이미 다운로드됨", Toast.LENGTH_SHORT).show()
                return@launch
            }

            isDownloading = true
            btnDownload.text = "⏳ 0%"

            val progressDialog = android.app.ProgressDialog(this@PlayerActivity).apply {
                setTitle("다운로드 중")
                setMessage(currentTitle)
                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setCancelable(false)
                show()
            }

            val file = AppDownloader.download(
                applicationContext,
                currentVideoId,
                streamUrl
            ) { pct ->
                runOnUiThread {
                    progressDialog.progress = pct
                    btnDownload.text = "⏳ $pct%"
                }
            }

            progressDialog.dismiss()
            isDownloading = false
            btnDownload.text = "📥"

            if (file != null) {
                val size = file.length()
                HistoryDatabase.get(applicationContext).downloadDao().insert(
                    DownloadEntity(
                        videoId = currentVideoId,
                        title = currentTitle,
                        channel = currentChannel,
                        thumbnail = currentThumb,
                        filePath = file.absolutePath,
                        sizeBytes = size,
                        downloadedAt = System.currentTimeMillis()
                    )
                )
                Toast.makeText(
                    this@PlayerActivity,
                    "다운로드 완료 (${size / 1024 / 1024}MB)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@PlayerActivity, "다운로드 실패", Toast.LENGTH_LONG).show()
            }
        }
    }


    // ========== 📝 자막 검색 ==========
    private fun openTranscript() {
        if (subtitleTracks.isEmpty()) {
            Toast.makeText(this, "이 영상엔 자막이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val preferred = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
            ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
            ?: subtitleTracks.firstOrNull()
        if (preferred == null) {
            Toast.makeText(this, "자막 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TranscriptActivity::class.java).apply {
            putExtra("SUBTITLE_URL", preferred.url)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("CURRENT_MS", mediaController?.currentPosition ?: 0L)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_TRANSCRIPT)
    }

    companion object {
        private const val REQ_TRANSCRIPT = 1001
        private const val REQ_LIVE_MP = 7100
    }


    // ========== 🚫 SponsorBlock ==========
    private fun loadSponsorSegments(videoId: String) {
        sbCheckJob?.cancel()
        previewJob?.cancel()
        abJob?.cancel()
        sponsorSegments = emptyList()
        if (!sbEnabled || videoId.isBlank()) return

        lifecycleScope.launch {
            sponsorSegments = SponsorBlock.fetch(videoId, sbCategories)
            if (sponsorSegments.isNotEmpty()) {
                Toast.makeText(
                    this@PlayerActivity,
                    "스폰서 구간 ${sponsorSegments.size}개 감지",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        sbCheckJob = lifecycleScope.launch {
            while (isActive) {
                checkAndSkip()
                delay(1000)
            }
        }
    }

    private fun checkAndSkip() {
        if (!sbEnabled) return
        if (sponsorSegments.isEmpty()) return  // ★ 스킵할 게 없으면 스킵
        val pos = mediaController?.currentPosition ?: return
        for (seg in sponsorSegments) {
            if (pos >= seg.startMs && pos < seg.endMs - 200) {
                mediaController?.seekTo(seg.endMs)
                Toast.makeText(
                    this@PlayerActivity,
                    "⏭ ${seg.categoryLabel} 건너뜀",
                    Toast.LENGTH_SHORT
                ).show()
                break
            }
        }
    }

    private fun showSbCategoryDialog() {
        val labels = SponsorBlock.ALL_CATEGORIES.map { SponsorBlock.labelOf(it) }.toTypedArray()
        val checked = SponsorBlock.ALL_CATEGORIES.map { it in sbCategories }.toBooleanArray()
        val tempSet = sbCategories.toMutableSet()

        AlertDialog.Builder(this)
            .setTitle("건너뛸 카테고리")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val cat = SponsorBlock.ALL_CATEGORIES[which]
                if (isChecked) tempSet.add(cat) else tempSet.remove(cat)
            }
            .setPositiveButton("적용") { _, _ ->
                sbCategories = tempSet.toList()
                pref.edit().putString("sb_categories", sbCategories.joinToString(",")).apply()
                if (currentVideoId.isNotBlank() && sbEnabled) {
                    loadSponsorSegments(currentVideoId)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }


    // ========== 🎞 화질 선택 ==========
    private fun showQualityDialog() {
        if (currentQualities.isEmpty()) {
            Toast.makeText(this, "화질 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val urls = mutableListOf<VideoQuality?>()

        // 자동 (최고 화질)
        labels.add("자동 (최고 화질)")
        urls.add(null)

        for (q in currentQualities) {
            labels.add(q.label)
            urls.add(q)
        }

        AlertDialog.Builder(this)
            .setTitle("화질 선택")
            .setItems(labels.toTypedArray()) { _, i ->
                applyQuality(urls[i])
            }
            .show()
    }

    private fun applyQuality(quality: VideoQuality?) {
        val pos = mediaController?.currentPosition ?: 0L
        val sub = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
            ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
            ?: subtitleTracks.firstOrNull()

        val targetUrl = quality?.url ?: currentVideoBestUrl ?: currentStreamUrl
        if (targetUrl.isNullOrBlank()) {
            Toast.makeText(this, "URL 없음", Toast.LENGTH_SHORT).show()
            return
        }

        currentStreamUrl = targetUrl
        applyStreamWithSubtitle(targetUrl, sub, null, pos)

        val label = quality?.label ?: "자동"
        Toast.makeText(this, "화질: $label", Toast.LENGTH_SHORT).show()
    }


    // ========== 채널 페이지 이동 ==========
    private fun openChannelFromPlayer() {
        if (currentChannel.isBlank()) {
            Toast.makeText(this, "채널 정보 없음", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val results = YouTubeChannels.search(currentChannel)
            val match = results.firstOrNull { it.name == currentChannel }
                ?: results.firstOrNull { it.name.contains(currentChannel.take(8)) }
            if (match == null) {
                Toast.makeText(this@PlayerActivity, "채널을 찾을 수 없음", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(Intent(this@PlayerActivity, ChannelActivity::class.java).apply {
                putExtra("CHANNEL_ID", match.channelId)
                putExtra("CHANNEL_NAME", match.name)
            })
        }
    }


    // ========== ✏️ 영상 메모 ==========
    private fun addNote() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "메모할 수 없는 영상", Toast.LENGTH_SHORT).show()
            return
        }
        val pos = mediaController?.currentPosition ?: 0L
        val input = android.widget.EditText(this).apply {
            hint = "메모 내용"
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ ${formatTime(pos)}에 메모")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        HistoryDatabase.get(applicationContext).videoNoteDao().insert(
                            VideoNoteEntity(
                                videoId = currentVideoId,
                                videoTitle = currentTitle,
                                timestampMs = pos,
                                note = text,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        Toast.makeText(this@PlayerActivity, "메모 저장", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@PlayerActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showNoteList() {
        if (currentVideoId.isBlank()) return
        lifecycleScope.launch {
            val notes = try {
                HistoryDatabase.get(applicationContext).videoNoteDao()
                    .getForVideo(currentVideoId)
                    .first()
            } catch (e: Exception) { emptyList() }

            if (notes.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "메모 없음", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val labels = notes.map { "[${formatTime(it.timestampMs)}] ${it.note}" }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("📒 메모 (탭=이동, 롱프레스=삭제)")
                .setItems(labels) { _, i ->
                    mediaController?.seekTo(notes[i].timestampMs)
                    Toast.makeText(this@PlayerActivity, "${formatTime(notes[i].timestampMs)}로 이동", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("전체 삭제") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            HistoryDatabase.get(applicationContext).videoNoteDao()
                                .deleteForVideo(currentVideoId)
                            Toast.makeText(this@PlayerActivity, "삭제됨", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) { }
                    }
                }
                .show()
        }
    }


    private fun toggleAutoPip() {
        val on = pref.getBoolean("auto_pip", false)
        pref.edit().putBoolean("auto_pip", !on).apply()
        Toast.makeText(
            this,
            if (!on) "홈 버튼 시 자동 PiP ON" else "자동 PiP OFF",
            Toast.LENGTH_SHORT
        ).show()
    }


    // ========== 📑 챕터 ==========
    private fun renderChapters(description: String) {
        val box = findViewById<View>(R.id.chapterBox)
        val list = findViewById<android.widget.LinearLayout>(R.id.chapterList)
        list.removeAllViews()

        val chapters = YouTubeChapters.parse(description)
        if (chapters.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE

        for (c in chapters) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            row.addView(android.widget.TextView(this).apply {
                text = formatTime(c.startMs)
                textSize = 12f
                setTextColor(0xFFFF2D55.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (60 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })

            row.addView(android.widget.TextView(this).apply {
                text = c.title
                textSize = 13f
                setTextColor(0xFFF5F5F7.toInt())
                maxLines = 2
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })

            row.setOnClickListener {
                mediaController?.seekTo(c.startMs)
                Toast.makeText(this@PlayerActivity, "${formatTime(c.startMs)}로 이동", Toast.LENGTH_SHORT).show()
            }
            list.addView(row)
        }
    }


    // ========== ⏭ 다음 영상 미리보기 ==========
    private fun startPreviewWatcher() {
        previewShown = false
        previewJob?.cancel()
        abJob?.cancel()
        previewJob = lifecycleScope.launch {
            while (isActive) {
                val mc = mediaController ?: continue
                val dur = mc.duration
                val pos = mc.currentPosition
                if (dur > 0 && !previewShown && dur - pos in 1..10000 && pos > 5000) {
                    previewShown = true
                    val queue = QueueManager.get(this@PlayerActivity)
                    val queueNext = queue.firstOrNull { it.videoId != currentVideoId }
                    val next = queueNext ?: run {
                        val rel = YouTubeRelated.fetch(currentVideoId)
                        rel.firstOrNull()?.let {
                            HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                        }
                    }
                    next?.let {
                        runOnUiThread {
                            Toast.makeText(
                                this@PlayerActivity,
                                "⏭ 다음: ${it.title.take(30)}...",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                delay(1000)
            }
        }
    }


    private fun switchToAudioMode() {
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "오디오 모드로 전환 불가", Toast.LENGTH_SHORT).show()
            return
        }
        QueueManager.clear(this)

        val subUrl = subtitleTracks
            .firstOrNull { it.languageCode.startsWith("ko") }?.url
            ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }?.url
            ?: subtitleTracks.firstOrNull()?.url
            ?: ""

        startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("VIDEO_ID", currentVideoId)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("VIDEO_CHANNEL", currentChannel)
            putExtra("VIDEO_THUMB", currentThumb)
            putExtra("FROM_PLAYLIST", false)
            putExtra("REUSE_STREAM_URL", currentStreamUrl ?: "")
            putExtra("REUSE_SUBTITLE_URL", subUrl)
        })
        finish()
    }


    // ========== A-B 반복 ==========
    private fun cycleAbRepeat() {
        val mc = mediaController ?: return
        val pos = mc.currentPosition
        val btn = findViewById<MaterialButton>(R.id.btnAbRepeat)

        when {
            abStart < 0 -> {
                abStart = pos
                abEnd = -1
                btn.text = "A · -"
                Toast.makeText(this, "A: ${formatTime(pos)}", Toast.LENGTH_SHORT).show()
            }
            abEnd < 0 -> {
                if (pos <= abStart + 1000) {
                    Toast.makeText(this, "B는 A보다 뒤여야 함", Toast.LENGTH_SHORT).show()
                    return
                }
                abEnd = pos
                btn.text = "A·B ON"
                Toast.makeText(this, "B: ${formatTime(pos)} · 반복 시작", Toast.LENGTH_SHORT).show()
                startAbLoop()
            }
            else -> {
                // 해제
                abStart = -1
                abEnd = -1
                abJob?.cancel()
                btn.text = "A-B"
                Toast.makeText(this, "A-B 해제", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startAbLoop() {
        abJob?.cancel()
        abJob = lifecycleScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && abStart >= 0 && abEnd > 0) {
                    if (mc.currentPosition >= abEnd) {
                        mc.seekTo(abStart)
                    }
                } else {
                    // ★ A-B 없으면 job 종료
                    return@launch
                }
                delay(500)
            }
        }
    }


    // ========== 👆 자막 드래그 ==========
    private fun setupSubtitleDrag() {
        val sv = playerView.subtitleView ?: return
        var downX = 0f
        var downY = 0f
        var startTransX = 0f
        var startTransY = 0f
        var dragging = false
        var longPressReady = false

        // ★ 힌트 박스 (드래그 가능 표시)
        val dragHint = findViewById<View>(R.id.dragHintBox)

        val longPressRunnable = Runnable {
            longPressReady = true
            dragHint.visibility = View.VISIBLE
            // 자막 뷰 강조 (테두리)
            sv.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x33000000)
                setStroke(
                    (3 * resources.displayMetrics.density).toInt(),
                    0xFFFF2D55.toInt()
                )
                cornerRadius = 8 * resources.displayMetrics.density
            }
            sv.setPadding(16, 8, 16, 8)
        }

        sv.isClickable = true
        sv.isFocusable = true
        sv.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTransX = sv.translationX
                    startTransY = sv.translationY
                    dragging = false
                    longPressReady = false
                    // ★ 2초로 늘림
                    sv.postDelayed(longPressRunnable, 2000)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val moved = Math.abs(dx) > 15 || Math.abs(dy) > 15

                    if (!longPressReady && moved) {
                        sv.removeCallbacks(longPressRunnable)
                        dragHint.visibility = View.GONE
                        clearSubtitleBorder(sv)
                        return@setOnTouchListener true
                    }

                    if (longPressReady) {
                        dragging = true
                        sv.translationX = startTransX + dx
                        sv.translationY = startTransY + dy
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    sv.removeCallbacks(longPressRunnable)
                    dragHint.visibility = View.GONE
                    clearSubtitleBorder(sv)
                    if (dragging) {
                        subtitleOffsetX = sv.translationX
                        subtitleOffsetY = sv.translationY
                        pref.edit()
                            .putFloat("offset_x", subtitleOffsetX)
                            .putFloat("offset_y", subtitleOffsetY)
                            .apply()
                        Toast.makeText(
                            this@PlayerActivity,
                            "자막 위치 저장됨",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dragging = false
                    longPressReady = false
                    true
                }
                else -> false
            }
        }
    }

    private fun clearSubtitleBorder(sv: View) {
        sv.background = null
        sv.setPadding(0, 0, 0, 0)
    }


    private fun resetSubtitlePosition() {
        pref.edit().remove("offset_x").remove("offset_y").apply()
        subtitleOffsetX = 0f
        subtitleOffsetY = 0f
        playerView.subtitleView?.translationX = 0f
        playerView.subtitleView?.translationY = 0f
        Toast.makeText(this, "자막 위치 초기화", Toast.LENGTH_SHORT).show()
    }


    // ========== 🤖 AI 번역 자막 ==========
    private var translating = false

    private fun translateAndApply(sub: SubtitleTrack) {
        if (translating) {
            Toast.makeText(this, "이미 번역 중입니다", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentVideoId.isBlank()) return

        translating = true
        val langLabel = sub.displayName.ifBlank { sub.languageCode }
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("🤖 AI 번역 중")
            setMessage("$langLabel 자막을 한국어로 번역하고 있습니다...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val vttFile = SubtitleTranslator.translateToVtt(
                    applicationContext,
                    currentVideoId,
                    sub.url,
                    "ko",
                    sub.languageCode   // ★ 원문 언어 전달 (캐시용)
                )
                progressDialog.dismiss()
                translating = false

                if (vttFile == null || !vttFile.exists()) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "번역 실패. 잠시 후 다시 시도하세요",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 로컬 VTT 재생
                val pos = mediaController?.currentPosition ?: 0L
                val builder = MediaItem.Builder().setUri(currentStreamUrl ?: return@launch)
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(
                            android.net.Uri.fromFile(vttFile)
                        )
                            .setMimeType(MimeTypes.TEXT_VTT)
                            .setLanguage("ko")
                            .setLabel("AI 번역 (한국어)")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
                mediaController?.setMediaItem(builder.build(), pos)
                mediaController?.prepare()
                mediaController?.playWhenReady = true

                Toast.makeText(
                    this@PlayerActivity,
                    "✅ AI 번역 자막 적용",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                translating = false
                Toast.makeText(
                    this@PlayerActivity,
                    "오류: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    private fun toggleTexTra() {
        val appPref = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val current = appPref.getBoolean("use_textra", false)
        appPref.edit().putBoolean("use_textra", !current).apply()
        Toast.makeText(
            this,
            if (!current) "🇯🇵 TexTra ON (느림·고품질)\n일본어 → 한국어에만 적용" else "Groq (빠름)",
            Toast.LENGTH_LONG
        ).show()
    }


    // ========== 🎙 실시간 자막 (Groq Whisper) ==========
    private fun toggleLiveSubtitle() {
        if (liveSubtitleActive) {
            stopLiveSubtitle()
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Android 10+ 필요", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            Toast.makeText(this, "GROQ_API_KEY 없음", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_LIVE_MP)
    }

    private fun startLiveCapture() {
        val mp = mediaProjection ?: return
        if (liveGroqManager == null) liveGroqManager = GroqSttManager(BuildConfig.GROQ_API_KEY)
        liveGroqManager?.reset()
        liveSubtitleActive = true
        updateLiveSubButton()
        liveSubtitleOverlay.visibility = View.VISIBLE
        liveBuilder.setLength(0)
        tvLiveSubtitle.text = ""
        lastLiveText = ""
        liveCaptureManager = AudioCaptureManager(this) { chunk ->
            liveGroqManager?.transcribeChunk(chunk, "ko",
                onResult = { text ->
                    val newText = if (lastLiveText.isNotEmpty() && text.startsWith(lastLiveText))
                        text.removePrefix(lastLiveText).trim() else text
                    if (newText.isNotBlank()) {
                        lastLiveText = text
                        liveBuilder.append(newText).append(" ")
                        tvLiveSubtitle.text = liveBuilder.toString().trim()
                    }
                },
                onError = { }
            )
        }
        if (liveCaptureManager?.start(mp) != true) {
            Toast.makeText(this, "캡처 실패", Toast.LENGTH_SHORT).show()
            stopLiveSubtitle()
            return
        }
        Toast.makeText(this, "🎙 실시간 자막 시작", Toast.LENGTH_SHORT).show()
    }

    private fun stopLiveSubtitle() {
        liveSubtitleActive = false
        updateLiveSubButton()
        liveCaptureManager?.stop()
        liveCaptureManager = null
        try { mediaProjection?.stop() } catch (e: Exception) { }
        mediaProjection = null
        liveSubtitleOverlay.visibility = View.GONE
    }


    private fun updateLiveSubButton() {
        try {
            val color = if (liveSubtitleActive) 0xFFFF2D55.toInt() else 0x33FFFFFF.toInt()
            btnLiveSub.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            btnLiveSub.setTextColor(0xFFFFFFFF.toInt())
            btnLiveSub.text = if (liveSubtitleActive) "🎙 ON" else "🎙"
        } catch (e: Exception) { }
    }


    private fun showControls() {
        // 잠금 상태면 복원 안 함
        if (isLocked) return
        val scroll = findViewById<View>(R.id.controlScroll)
        val bar = findViewById<View>(R.id.controlBar)
        scroll?.visibility = View.VISIBLE
        bar?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 180_000)
    }

    private fun toggleFullscreen() {
        val controller = window.insetsController ?: return
        if (!isFullscreen) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            infoScroll.visibility = View.GONE
            val lp = videoContainer.layoutParams
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            videoContainer.layoutParams = lp
            isFullscreen = true
        } else {
            controller.show(WindowInsets.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            infoScroll.visibility = View.VISIBLE
            val lp = videoContainer.layoutParams
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            videoContainer.layoutParams = lp
            isFullscreen = false
        }
    }


    // ========== 🔒 잠금 모드 ==========

    // ========== 🔁 반복 재생 ==========
    private fun toggleRepeat() {
        val mc = mediaController ?: return
        val next = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        mc.repeatMode = next
        Toast.makeText(
            this,
            if (next == Player.REPEAT_MODE_ONE) "🔁 같은 영상 반복 ON" else "반복 OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ========== 💡 화면 항상 켜짐 ==========
    private fun toggleKeepScreenOn() {
        val on = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "화면 자동 꺼짐 방지 OFF", Toast.LENGTH_SHORT).show()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "화면 자동 꺼짐 방지 ON", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== ⋯ 더보기 메뉴 ==========
    private fun showMoreMenu() {
        val items = arrayOf(
            "✏️ 현재 시점에 메모",
            "📒 메모 목록",
            "🎞 화질 선택",
            "🔁 반복 재생 (같은 영상)",
            "💡 화면 항상 켜짐",
            "📤 공유",
            "📥 다운로드",
            "📝 자막 검색",
            "📋 재생 대기열",
            "📐 PIP 크기",
            "🖼 홈 누르면 자동 PiP",
            "⚙️ 자막 스타일",
            if (liveSubtitleActive) "🔴 실시간 자막 끄기" else "🟢 실시간 자막 켜기"
        )
        AlertDialog.Builder(this)
            .setTitle("더보기")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> addNote()
                    1 -> showNoteList()
                    2 -> showQualityDialog()
                    3 -> toggleRepeat()
                    4 -> toggleKeepScreenOn()
                    5 -> showShareDialog()
                    6 -> startDownload()
                    7 -> openTranscript()
                    8 -> startActivity(Intent(this, QueueActivity::class.java))
                    9 -> showPipSizeDialog()
                    10 -> toggleAutoPip()
                    11 -> showSubtitleStyleDialog()
                    12 -> toggleLiveSubtitle()
                }
            }
            .show()
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            // 모든 버튼 숨김
            findViewById<View>(R.id.controlBar).visibility = View.GONE
            lockOverlay.visibility = View.VISIBLE
            Toast.makeText(this, "잠금됨", Toast.LENGTH_SHORT).show()
        } else {
            findViewById<View>(R.id.controlBar).visibility = View.VISIBLE
            lockOverlay.visibility = View.GONE
            Toast.makeText(this, "잠금 해제", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 📐 PIP 크기 선택 ==========
    private fun showPipSizeDialog() {
        val items = arrayOf(
            "16:9 가로 (기본)",
            "4:3 클래식",
            "1:1 정사각",
            "9:16 세로 (쇼츠)"
        )
        val ratios = arrayOf(16f / 9f, 4f / 3f, 1f, 9f / 16f)

        AlertDialog.Builder(this)
            .setTitle("PIP 크기")
            .setItems(items) { _, i ->
                pipAspect = ratios[i]
                Toast.makeText(this, "PIP 비율: ${items[i]}", Toast.LENGTH_SHORT).show()
                // PIP 중이면 즉시 반영
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(
                            (pipAspect * 1000).toInt(), 1000
                        ))
                        .build()
                    setPictureInPictureParams(params)
                }
            }
            .show()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational((pipAspect * 1000).toInt(), 1000))
                .build()
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        findViewById<View>(R.id.controlBar).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        infoScroll.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }


    // ========== PIP 자동 진입 (홈 버튼) ==========
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ★ 기본 활성화 (설정 없이도 자동 PiP)
        val autoPip = pref.getBoolean("auto_pip", true)
        if (!autoPip) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mediaController?.isPlaying == true && !isInPictureInPictureMode) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational((pipAspect * 1000).toInt(), 1000))
                    .build()
                setPictureInPictureParams(params)
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        if (liveSubtitleActive) stopLiveSubtitle()
        sbCheckJob?.cancel()
        previewJob?.cancel()
        abJob?.cancel()
        savePosition()
        cancelAutoNext()
        if (!isInPictureInPictureMode) mediaController?.pause()
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LIVE_MP) {
            if (resultCode == RESULT_OK && data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
                startLiveCapture()
            } else {
                // ★ 권한 취소 → 상태 OFF 유지
                liveSubtitleActive = false
                updateLiveSubButton()
                Toast.makeText(this, "화면 캡처 권한 취소됨", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQ_TRANSCRIPT && resultCode == RESULT_OK) {
            val seekMs = data?.getLongExtra("SEEK_MS", -1L) ?: -1L
            if (seekMs >= 0) {
                mediaController?.seekTo(seekMs)
                Toast.makeText(this, "이동: ${formatTime(seekMs)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sbCheckJob?.cancel()
        previewJob?.cancel()
        abJob?.cancel()
        mediaController?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }


    /** 하단 고정 컨트롤바 (이전/5초뒤/재생/5초앞/다음) */
    private fun setupBottomControls() {
        val btnPrev = findViewById<android.widget.ImageButton>(R.id.btnPrevTrack)
        val btnBack5 = findViewById<android.widget.ImageButton>(R.id.btnBack5)
        val btnPlayPause = findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
        val btnFwd5 = findViewById<android.widget.ImageButton>(R.id.btnFwd5)
        val btnNext = findViewById<android.widget.ImageButton>(R.id.btnNextTrack)

        btnPrev.setOnClickListener {
            val p = playerView.player ?: return@setOnClickListener
            // 이전 곡: 큐에서 현재 위치 -1
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            if (curIdx > 0) {
                val prev = queue[curIdx - 1]
                currentVideoId = prev.videoId
                extractAndPlay(prev.videoId, prev.title, prev.channel, prev.thumbnail, 0L)
            } else {
                p.seekTo(0)   // 큐 처음이면 처음으로
            }
        }

        btnBack5.setOnClickListener {
            val p = playerView.player ?: return@setOnClickListener
            p.seekTo((p.currentPosition - 5_000).coerceAtLeast(0))
        }

        btnPlayPause.setOnClickListener {
            val p = playerView.player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
        }

        btnFwd5.setOnClickListener {
            val p = playerView.player ?: return@setOnClickListener
            val dur = p.duration
            val newPos = p.currentPosition + 5_000
            p.seekTo(if (dur > 0) newPos.coerceAtMost(dur) else newPos)
        }

        btnNext.setOnClickListener {
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            if (curIdx >= 0 && curIdx < queue.size - 1) {
                val next = queue[curIdx + 1]
                currentVideoId = next.videoId
                extractAndPlay(next.videoId, next.title, next.channel, next.thumbnail, 0L)
            } else {
                // 큐 끝: 유튜브 radio/artist 자동 확장
                playNextRelated()
            }
        }

        // playerView 탭 → 컨트롤 재표시
        playerView.setOnClickListener { showControls() }
    }


}
