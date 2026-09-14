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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnCc: MaterialButton
    private lateinit var btnBookmark: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var infoScroll: View
    private lateinit var videoContainer: View
    private lateinit var summaryCard: View
    private lateinit var tvSummary: TextView
    private lateinit var tvSummaryBadge: TextView
    private lateinit var seekOverlayLeft: View
    private lateinit var seekOverlayRight: View
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    private var isFullscreen = false
    private var currentSpeed = 1.0f

    private var subtitleTracks: List<SubtitleTrack> = emptyList()
    private var currentStreamUrl: String? = null

    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""

    private val pref by lazy { getSharedPreferences("subtitle_prefs", Context.MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnCc = findViewById(R.id.btnCc)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnShare = findViewById(R.id.btnShare)
        infoScroll = findViewById(R.id.infoScroll)
        videoContainer = findViewById(R.id.videoContainer)
        summaryCard = findViewById(R.id.summaryCard)
        tvSummary = findViewById(R.id.tvSummary)
        tvSummaryBadge = findViewById(R.id.tvSummaryBadge)
        seekOverlayLeft = findViewById(R.id.seekOverlayLeft)
        seekOverlayRight = findViewById(R.id.seekOverlayRight)

        progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@PlayerActivity, R.color.spinner)
            )
        }
        (playerView.parent as? ViewGroup)?.addView(progress)

        applySubtitleStyle()
        setupGestures()

        val videoUri = intent.getStringExtra("VIDEO_URI")
        val videoId = intent.getStringExtra("VIDEO_ID")
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
            playerView.player = mediaController

            when {
                videoUri != null -> playDirectUrl(videoUri)
                videoId != null -> checkResumeAndPlay(videoId, vTitle, vChannel, vThumb)
                else -> Toast.makeText(this, "재생할 영상이 없습니다", Toast.LENGTH_SHORT).show()
            }

            if (!videoId.isNullOrBlank()) refreshBookmarkState(videoId)
        }, MoreExecutors.directExecutor())

        btnSpeed.setOnClickListener { showSpeedDialog() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { toggleFullscreen() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPipMode() }
        btnCc.setOnClickListener { showSubtitleDialog() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnShare.setOnClickListener { showShareDialog() }
    }

    // ========== 제스처: 더블탭 시크 ==========
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = playerView.width
                if (width <= 0) return false
                val x = e.x

                when {
                    // 왼쪽 1/3 → 10초 뒤로
                    x < width / 3f -> seekBy(-10_000L, true)
                    // 오른쪽 1/3 → 10초 앞으로
                    x > width * 2 / 3f -> seekBy(10_000L, false)
                    // 가운데 → 재생/일시정지
                    else -> togglePlayPause()
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun seekBy(deltaMs: Long, isLeft: Boolean) {
        val mc = mediaController ?: return
        val dur = mc.duration
        if (dur <= 0) return

        val newPos = (mc.currentPosition + deltaMs).coerceIn(0L, dur)
        mc.seekTo(newPos)

        // 오버레이 표시 (0.5초)
        val overlay = if (isLeft) seekOverlayLeft else seekOverlayRight
        overlay.visibility = View.VISIBLE
        mainHandler.postDelayed({
            overlay.visibility = View.GONE
        }, 500)
    }

    private fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    // ========== 📤 공유 ==========
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

        AlertDialog.Builder(this)
            .setTitle("공유")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> shareViaIntent(youtubeUrl)
                    1 -> openYouTubeApp(youtubeUrl)
                    2 -> copyToClipboard(youtubeUrl)
                    3 -> openBrowser(youtubeUrl)
                }
            }
            .show()
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
        // YouTube 앱으로 시도
        val ytAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$currentVideoId"))
        try {
            startActivity(ytAppIntent)
        } catch (e: ActivityNotFoundException) {
            // 앱 없으면 브라우저로
            openBrowser(url)
        }
    }

    private fun copyToClipboard(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YouTube URL", url))
        Toast.makeText(this, "URL 복사됨", Toast.LENGTH_SHORT).show()
    }

    private fun openBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== ⭐ 북마크 ==========
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
                                videoId = currentVideoId,
                                title = currentTitle,
                                channel = currentChannel,
                                thumbnail = currentThumb,
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

    // ========== ⏱ 이어보기 ==========
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

            if (canResume) {
                showResumeDialog(videoId, title, channel, thumb, pos, dur)
            } else {
                extractAndPlay(videoId, title, channel, thumb, 0L)
            }
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
            (findViewById<TextView>(R.id.tvDescription)).text = descText

            subtitleTracks = result.subtitles
            val streamUrl = result.muxedUrl ?: result.videoUrl ?: result.audioUrl

            val autoSub = subtitleTracks.firstOrNull { it.languageCode.startsWith("ko") }
                ?: subtitleTracks.firstOrNull { it.languageCode.startsWith("en") }
                ?: subtitleTracks.firstOrNull()

            applyStreamWithSubtitle(streamUrl, autoSub, null, startPosMs)
            updateCcButton(autoSub != null)
            buildSummary(videoId, finalTitle, result.description)
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
            val label = if (targetLang != null) "${sub.displayName} → 한국어" else sub.displayName
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(vttUrl))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(targetLang ?: sub.languageCode)
                        .setLabel(label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        mediaController?.setMediaItem(builder.build(), startPosMs)
        mediaController?.prepare()
        mediaController?.playWhenReady = true
        mediaController?.setPlaybackSpeed(currentSpeed)
        playerView.post { applySubtitleStyle() }
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
                .setMessage("이 영상엔 자막이 없습니다")
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
            if (!s.languageCode.startsWith("ko")) {
                labels.add("$name → 한국어")
                callbacks.add { applyStreamWithSubtitle(currentStreamUrl, s, "ko", pos); updateCcButton(true) }
            }
        }
        labels.add("⚙️ 자막 스타일")
        callbacks.add { showSubtitleStyleDialog() }
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

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            setPictureInPictureParams(params)
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        findViewById<View>(R.id.controlBar).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        infoScroll.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        if (!isInPictureInPictureMode) mediaController?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaController.releaseFuture(controllerFuture)
    }
}
