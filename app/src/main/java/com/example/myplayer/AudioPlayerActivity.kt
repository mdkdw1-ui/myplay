package com.example.myplayer

import android.content.ComponentName
import android.view.View
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.net.HttpURLConnection
import java.net.URL

class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null
    private var updateJob: Job? = null
    private var pulseAnimator: android.animation.ValueAnimator? = null
    private var isDragging = false
    private val bgScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentVideoId: String = ""
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentThumb: String = ""
    private var currentArtist: String = ""
    private var currentSubtitleUrl: String = ""
    private var sameArtistMode: Boolean = false
    private var loadingNext = false
    private var audioLiveActive = false
    private var liveSubtitleActive = false
    private var sbCategories: List<String> = SponsorBlock.DEFAULT_CATEGORIES
    private var sbEnabled: Boolean = true
    private var sponsorSegments: List<SkipSegment> = emptyList()
    private var previewShown = false
    private var abEnd: Long = -1L
    private var abStart: Long = -1L
    private var abJob: kotlinx.coroutines.Job? = null
    private var previewJob: kotlinx.coroutines.Job? = null
    private var sbCheckJob: kotlinx.coroutines.Job? = null
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var liveCaptureManager: AudioCaptureManager? = null
    private var liveGroqManager: GroqSttManager? = null
    private val liveBuilder = StringBuilder()
    private var lastLiveText = ""
    private var reuseStreamUrl: String = ""
    private var bufferingStartMs: Long = 0L
    private var bufferingWatchJob: kotlinx.coroutines.Job? = null
    private var extractInProgress: Boolean = false
    private var playNextFailCount: Int = 0
    private var prefetchJob: kotlinx.coroutines.Job? = null
    private val prefetchedStreams = mutableMapOf<String, YouTubeStream.StreamResult>()
    private var lastRelatedCache: List<VideoItem> = emptyList()
    private var lastRelatedCacheTime: Long = 0L
    private var lastPlayNextFailMs: Long = 0L
    private var lastMediaSetMs: Long = 0L
    private var idleRepeatCount: Int = 0
    private var lastIdleMs: Long = 0L
    private var lastSkipMs: Long = 0L
    private val failedIds = mutableSetOf<String>()
    private var reuseSubtitleUrl: String = ""

    private val audioHistory = mutableListOf<AudioHistoryItem>()
    private var historyIndex = -1
    private var isPlayingFromHistory = false

    data class AudioHistoryItem(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnail: String
    )

    private var pref: android.content.SharedPreferences? = null
    private var localOnlyMode: Boolean = false

    private lateinit var ivArt: ImageView
    private lateinit var ivBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvChannel: TextView
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnLiveSub: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio)

        pref = getSharedPreferences("audio_prefs", MODE_PRIVATE)

        try {
            val keepOn = pref?.getBoolean("keep_screen_on_audio", true) ?: true
            if (keepOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Exception) {}

        try {
            pref?.edit()?.putBoolean("auto_next", true)?.apply()
        } catch (_: Exception) {}

        ivArt = findViewById(R.id.ivArt)
        ivBackground = findViewById(R.id.ivBackground)
        tvTitle = findViewById(R.id.tvTitle)
        tvChannel = findViewById(R.id.tvChannel)
        tvPos = findViewById(R.id.tvPos)
        tvDur = findViewById(R.id.tvDur)
        seekBar = findViewById(R.id.seekBar)
        seekBar.max = 1000
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnRepeat = findViewById(R.id.btnRepeat)
        btnLiveSub = findViewById(R.id.btnLiveSub)

        currentVideoId = intent.getStringExtra("VIDEO_ID") ?: ""
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist) QueueManager.clear(this)

        reuseStreamUrl = intent.getStringExtra("REUSE_STREAM_URL") ?: ""
        reuseSubtitleUrl = intent.getStringExtra("REUSE_SUBTITLE_URL") ?: ""

        updateUI()

        sameArtistMode = pref?.getBoolean("same_artist_mode", false) ?: false
        localOnlyMode = pref?.getBoolean("local_only_mode", false) ?: false
        val swLocalOnly = findViewById<SwitchMaterial>(R.id.swLocalOnly)
        swLocalOnly.isChecked = localOnlyMode
        swLocalOnly.setOnCheckedChangeListener { _, checked ->
            localOnlyMode = checked
            pref?.edit()?.putBoolean("local_only_mode", checked)?.apply()
            Toast.makeText(
                this,
                if (checked) "🎧 로컬 큐만 재생" else "🌐 유튜브 연관곡 사용",
                Toast.LENGTH_SHORT
            ).show()
        }

        val swArtist = findViewById<SwitchMaterial>(R.id.swSameArtist)
        swArtist.isChecked = sameArtistMode
        swArtist.setOnCheckedChangeListener { _, checked ->
            sameArtistMode = checked
            pref?.edit()?.putBoolean("same_artist_mode", checked)?.apply()
        }

        acquireWakeLock()

        updateAudioLiveSubButton()

        findViewById<android.widget.TextView>(R.id.tvTitle)?.setOnLongClickListener {
            showDiagLog()
            true
        }

        findViewById<android.widget.TextView>(R.id.tvChannel)?.setOnLongClickListener {
            showSubscribeDialog()
            true
        }

        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            attachListeners()
            attachPlayerListener()
            startUpdateLoop()
            attachEqualizer()
            if (currentVideoId.isNotEmpty() && currentVideoId !in failedIds) {
                // ★ 다중 조건으로 이미 재생 중인지 확인
                val mc = mediaController
                val prefVideoId = pref?.getString("current_video_id", null)
                val mcState = mc?.playbackState ?: -1
                val isPlayingState = mcState == Player.STATE_READY ||
                    mcState == Player.STATE_BUFFERING

                val isMediaItemCountOk = (mc?.mediaItemCount ?: 0) > 0
                val alreadyPlaying = mc != null && (
                    // 조건 1: ExoPlayer가 같은 곡
                    mc.currentMediaItem?.mediaId == currentVideoId ||
                    // 조건 2: prefs에 같은 곡이고 재생 상태
                    (prefVideoId == currentVideoId && isPlayingState) ||
                    // 조건 3: 재생 중인 아이템 있음
                    (isMediaItemCountOk && (mc.isPlaying || mc.playWhenReady)) ||
                    // ★ 조건 4: prefs에 같은 곡이고 ExoPlayer 큐에 아이템 있으면 skip
                    (prefVideoId == currentVideoId && isMediaItemCountOk) ||
                    // ★ 조건 5: prefs에 같은 곡이고 IDLE이면 곧 재생됨 → skip
                    (prefVideoId == currentVideoId && mcState == Player.STATE_IDLE && isMediaItemCountOk)
                )

                diag("onCreate check: mcState=$mcState prefVideoId=$prefVideoId " +
                     "mcMediaId=${mc?.currentMediaItem?.mediaId} currentVideoId=$currentVideoId " +
                     "alreadyPlaying=$alreadyPlaying")

                if (alreadyPlaying) {
                    diag("onCreate: 이미 재생 중 → loadAudio skip (큐 보존)")
                    updateUI()
                    // prefetch 재예약
                    try { prefetchNext() } catch (_: Exception) {}
                } else {
                    diag("onCreate: loadAudio 호출")
                    loadAudio(currentVideoId, isInitial = true)
                }
            } else {
                diag("onCreate skip failed: $currentVideoId")
            }
        }, MoreExecutors.directExecutor())

        // ★ 배터리 최적화 제외 요청 (첫 실행 시)
        try { checkBatteryOptimization() } catch (_: Exception) {}
    }

    private fun checkBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val asked = pref?.getBoolean("battery_opt_asked", false) ?: false
        if (asked) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔋 배터리 최적화 제외")
            .setMessage(
                "화면이 꺼져도 음악이 계속 재생되려면\n" +
                "배터리 최적화에서 이 앱을 제외해야 합니다.\n\n" +
                "설정으로 이동할까요?"
            )
            .setPositiveButton("설정 열기") { _, _ ->
                pref?.edit()?.putBoolean("battery_opt_asked", true)?.apply()
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Exception) {
                        Toast.makeText(this, "설정을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("나중에") { _, _ ->
                pref?.edit()?.putBoolean("battery_opt_asked", true)?.apply()
            }
            .show()
    }

    private val diagLines = java.util.ArrayDeque<String>()
    private fun diag(msg: String) {
        android.util.Log.d("AudioPlayer", msg)
        try {
            val f = java.io.File(filesDir, "audio_diag.log")
            if (f.exists() && f.length() > 500_000) {
                f.writeText("")
            }
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {}
        try {
            diagLines.addLast(msg)
            while (diagLines.size > 10) diagLines.removeFirst()
            val text = diagLines.joinToString("\n")
            runOnUiThread {
                try {
                    val tv = findViewById<android.widget.TextView>(R.id.tvChannel)
                    if (tv != null) {
                        tv.text = text
                        tv.setTextColor(0xFFFFEB3B.toInt())
                        tv.textSize = 9f
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun showDiagLog() {
        try {
            val f = java.io.File(filesDir, "audio_diag.log")
            val content = if (f.exists()) f.readText() else "(로그 없음)"
            val lines = content.lines().takeLast(100).joinToString("\n")

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("진단 로그 (마지막 100줄)")
                .setMessage(lines)
                .setPositiveButton("닫기", null)
                .setNegativeButton("📋 복사") { _, _ ->
                    try {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("audio_diag", content)
                        )
                        android.widget.Toast.makeText(this,
                            "복사됨 (전체 로그)", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e2: Exception) {
                        android.widget.Toast.makeText(this,
                            "복사 실패: ${e2.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("🗑 지우기") { _, _ -> f.delete() }
                .show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "로그 읽기 실패: ${e.message}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyPlayer::AudioPlayback").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) { }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
    }

    private fun prefetchNext() {
        prefetchJob?.cancel()
        prefetchJob = bgScope.launch {
            try {
                if (extractInProgress) {
                    diag("prefetch: extract 진행 중 → skip")
                    return@launch
                }

                diag("prefetch: 즉시 시작")

                // ★ 1순위: 큐의 다음 곡
                val queue = QueueManager.get(this@AudioPlayerActivity)
                val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
                diag("prefetch: queue=${queue.size} curIdx=$curIdx current=$currentVideoId")

                val next: HomeVideo? = if (curIdx >= 0 && curIdx < queue.size - 1) {
                    // 실패 곡 건너뛰기
                    var nextIdx = curIdx + 1
                    while (nextIdx < queue.size && queue[nextIdx].videoId in failedIds) {
                        nextIdx++
                    }
                    if (nextIdx < queue.size) {
                        val q = queue[nextIdx]
                        diag("prefetch: 큐 다음 곡 → ${q.videoId} (${q.title.take(30)})")
                        q
                    } else null
                } else null

                // ★ 2순위: 큐 끝 → 유튜브 radio/artist
                val target: HomeVideo? = next ?: run {
                    diag("prefetch: 큐 끝 → 유튜브 병렬 검색")
                    val artistName = currentArtist.ifBlank { currentChannel }
                    val radioDeferred = bgScope.async(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel)
                            } ?: emptyList()
                        } catch (_: Exception) { emptyList() }
                    }
                    val artistDeferred = bgScope.async(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                if (artistName.isNotBlank())
                                    YouTubeArtist.fetchSongs(artistName, currentVideoId)
                                else emptyList<VideoItem>()
                            } ?: emptyList()
                        } catch (_: Exception) { emptyList() }
                    }
                    val radioList = radioDeferred.await()
                    val artistList = artistDeferred.await()
                    val combined = (radioList + artistList)
                        .distinctBy { it.videoId }
                        .filter { it.videoId != currentVideoId && it.videoId !in failedIds }
                    combined.firstOrNull()?.let {
                        HomeVideo(it.videoId, it.title, it.channel, it.thumbnail)
                    }
                }

                if (target == null) {
                    diag("prefetch: 다음 곡 없음")
                    return@launch
                }

                if (prefetchedStreams.containsKey(target.videoId)) {
                    diag("prefetch: 이미 캐시됨 → skip")
                    // ★ 이미 캐시면 ExoPlayer에 addMediaItem
                    addToExoQueue(target, prefetchedStreams[target.videoId]!!)
                    return@launch
                }

                // ★ 스트림 추출
                val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    YouTubeStream.extractAudioOnly(target.videoId)
                } ?: return@launch

                if (result.hasAny) {
                    prefetchedStreams[target.videoId] = result
                    diag("prefetch 완료: ${target.videoId} (${target.title.take(30)})")
                    // ★ ExoPlayer 큐에 추가
                    addToExoQueue(target, result)
                }
            } catch (e: Exception) {
                diag("prefetch err: ${e.message}")
            }
        }
    }

    /** ★ ExoPlayer 큐에 다음 곡 추가 (순서 보장) */
    private fun addToExoQueue(target: HomeVideo, result: YouTubeStream.StreamResult) {
        try {
            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl
            if (url.isNullOrBlank()) {
                diag("addToExoQueue: URL 없음 (${target.videoId})")
                return
            }

            // ★ 이미 ExoPlayer 큐에 있으면 skip
            val mc = mediaController ?: return
            for (i in 0 until mc.mediaItemCount) {
                if (mc.getMediaItemAt(i).mediaId == target.videoId) {
                    diag("addToExoQueue: 이미 있음 (${target.videoId})")
                    return
                }
            }

            val artist = currentArtist.ifBlank { currentChannel }
            val metadata = MediaMetadata.Builder()
                .setTitle(target.title)
                .setArtist(artist)
                .setAlbumTitle(target.channel)
                .setArtworkUri(android.net.Uri.parse(target.thumbnail))
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(target.videoId)
                .setMediaMetadata(metadata)
                .build()

            runOnUiThread {
                try {
                    val mc2 = mediaController ?: return@runOnUiThread
                    mc2.addMediaItem(mediaItem)
                    diag("ExoPlayer addMediaItem: ${target.videoId} (itemCount=${mc2.mediaItemCount})")
                } catch (e: Exception) {
                    diag("addMediaItem err: ${e.message}")
                }
            }
        } catch (e: Exception) {
            diag("addToExoQueue err: ${e.message}")
        }
    }

    private fun attachEqualizer() {
        val session = PlaybackService.exoPlayer?.audioSessionId ?: 0
        if (session != 0) {
            EqualizerManager.attach(session)
            val savedGain = pref?.getInt("loudness_gain_mb", 0) ?: 0
            LoudnessManager.attach(session, savedGain)
        }
        try {
            val mc = mediaController
            if (mc != null) {
                mc.trackSelectionParameters = mc.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
                diag("video 트랙 비활성화 재적용")
            }
        } catch (e: Exception) {
            diag("track 재적용 err: ${e.message}")
        }
    }

    private fun updateUI() {
        val isLocal = currentVideoId.startsWith("local:") || currentVideoId.isBlank()
        findViewById<View>(R.id.btnVideoMode)?.visibility = if (isLocal) View.GONE else View.VISIBLE
        tvTitle.text = currentTitle
        tvChannel.text = currentChannel
        if (currentThumb.isNotEmpty()) {
            Glide.with(this).load(currentThumb).into(ivArt)
            Glide.with(this).load(currentThumb).into(ivBackground)
        }
        extractArtistAsync()
    }

    private fun extractArtistAsync() {
        val vid = currentVideoId
        val t = currentTitle
        val c = currentChannel
        bgScope.launch {
            try {
                val result = ArtistExtractor.extract(vid, t, c)
                if (vid == currentVideoId) {
                    currentArtist = result
                    runOnUiThread { refreshMediaMetadata() }
                }
            } catch (e: Exception) { }
        }
    }

    private fun refreshMediaMetadata() {
        if (currentVideoId.startsWith("local:")) return
        val mc = mediaController ?: return
        val item = mc.currentMediaItem ?: return
        val url = item.localConfiguration?.uri?.toString() ?: return

        val artist = currentArtist.ifBlank { currentChannel }
        val metadata = MediaMetadata.Builder()
            .setTitle(currentTitle)
            .setArtist(artist)
            .setAlbumTitle(currentChannel)
            .setArtworkUri(android.net.Uri.parse(currentThumb))
            .build()

        val newItem = MediaItem.Builder()
            .setUri(url)
            .setMediaId(currentVideoId)
            .setMediaMetadata(metadata)
            .build()

        val idx = mc.currentMediaItemIndex
        if (idx >= 0 && idx < mc.mediaItemCount) {
            mc.replaceMediaItem(idx, newItem)
        } else {
            val pos = mc.currentPosition
            val wasPlaying = mc.isPlaying
            mc.setMediaItem(newItem, pos)
            mc.prepare()
            if (wasPlaying) mc.play()
        }
    }

    private fun trySkipToNextInQueue(failedVideoId: String) {
        try {
            failedIds.add(failedVideoId)
            diag("failedIds += $failedVideoId (총 ${failedIds.size}개)")

            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == failedVideoId }
            diag("trySkip: queue=${queue.size} curIdx=$curIdx failed=$failedVideoId")

            if (queue.isEmpty()) {
                diag("trySkip: 큐 비었음 → playNextRelatedBg")
                playNextRelatedBg()
                return
            }
            // ★ 큐에서 제거하지 않음 (순서 유지)

            if (curIdx < 0) {
                val first = queue.firstOrNull { it.videoId !in failedIds }
                if (first != null) {
                    currentVideoId = first.videoId
                    currentTitle = first.title
                    currentChannel = first.channel
                    currentThumb = first.thumbnail
                    runOnUiThread { updateUI() }
                    loadAudio(first.videoId, isInitial = false)
                }
                return
            }
            var nextIdx = curIdx + 1
            while (nextIdx < queue.size && queue[nextIdx].videoId in failedIds) {
                nextIdx++
            }
            if (nextIdx < queue.size) {
                val next = queue[nextIdx]
                currentVideoId = next.videoId
                currentTitle = next.title
                currentChannel = next.channel
                currentThumb = next.thumbnail
                QueueManager.setCurrent(this, next.videoId)
                runOnUiThread { updateUI() }
                loadAudio(next.videoId, isInitial = false)
            } else {
                diag("trySkip: 큐 마지막 곡")
            }
        } catch (e: Exception) {
            diag("trySkip err: ${e.message}")
        }
    }

    private fun loadAudio(videoId: String, isInitial: Boolean = false) {
        // ★ 큐 위치 동기화 (prefetch/playNext가 정확한 curIdx 계산하도록)
        try {
            QueueManager.setCurrent(this, videoId)
            diag("loadAudio setCurrent: $videoId")
        } catch (_: Exception) {}

        // ★ YouTube 라이브/뉴스 필터 (로컬 제외)
        if (!videoId.startsWith("local:")) {
            val lower = currentTitle.lowercase()
            // ★ 필터 최소화 (오탐 방지)
            val badTitle = listOf("뉴스", "속보", "개표", "선거").any { lower.contains(it) }
                || lower.startsWith("라이브")     // 제목이 "라이브"로 시작
                || lower.startsWith("생방송")
                || Regex("\\btv\\b").containsMatchIn(lower)   // TV 방송만
            if (badTitle) {
                diag("loadAudio skip (라이브/뉴스): ${currentTitle.take(40)}")
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity,
                        "라이브/뉴스 제외 → 다음 곡", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                bgScope.launch { delay(300); trySkipToNextInQueue(videoId) }
                return
            }
        }

        if (videoId.startsWith("local:download:")) {
            val fileUri = intent.getStringExtra("FILE_URI")
            diag("loadAudio local:download fileUri=$fileUri")
            if (!fileUri.isNullOrBlank()) {
                bgScope.launch {
                    try {
                        val p = android.net.Uri.parse(fileUri).path
                        if (p != null) {
                            val f = java.io.File(p)
                            diag("local file exists=${f.exists()} size=${f.length() / 1024 / 1024}MB")
                        }
                    } catch (_: Exception) {}
                }

                try {
                    val mi = MediaItem.fromUri(fileUri)
                    mediaController?.setMediaItem(mi)
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                    diag("local:download 재생 시작")
                } catch (e: Exception) {
                    diag("local:download err: ${e.message}")
                }
            } else {
                diag("local:download fileUri null!")
            }
            return
        }
        if (videoId.startsWith("local:")) {
            bgScope.launch {
                try {
                    val queue = QueueManager.get(this@AudioPlayerActivity)
                    val localQueue = queue.filter { it.videoId.startsWith("local:") }
                    diag("loadAudio($videoId): queue=${queue.size} localQueue=${localQueue.size}")
                    if (localQueue.size >= 1) {
                        val scan = LocalMediaScanner.scan(this@AudioPlayerActivity, forceRefresh = false)
                        val items = mutableListOf<MediaItem>()
                        var startIdx = 0
                        localQueue.forEachIndexed { idx, item ->
                            val localId = item.videoId.removePrefix("local:").toLongOrNull()
                            val found = scan.firstOrNull { it.id == localId }
                            if (found != null) {
                                val playbackUri = if (found.filePath.isNotBlank() &&
                                    java.io.File(found.filePath).exists()) {
                                    android.net.Uri.fromFile(java.io.File(found.filePath))
                                } else {
                                    found.uri
                                }
                                val meta = androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.channel)
                                    .build()
                                val mi = MediaItem.Builder()
                                    .setUri(playbackUri.toString())
                                    .setMediaId(item.videoId)
                                    .setMediaMetadata(meta)
                                    .build()
                                items.add(mi)
                                if (item.videoId == videoId) startIdx = items.size - 1
                            }
                        }
                        diag("loadAudio: items=${items.size} startIdx=$startIdx")
                        if (items.isNotEmpty()) {
                            runOnUiThread {
                                mediaController?.setMediaItems(items, startIdx, 0L)
                                mediaController?.prepare()
                                mediaController?.playWhenReady = true
                                diag("setMediaItems 호출: ${items.size}개, start=$startIdx")
                            }
                            return@launch
                        }
                    }
                    val uri = intent.getStringExtra("LOCAL_URI")
                    if (uri != null) {
                        val mi = MediaItem.fromUri(uri)
                        runOnUiThread {
                            mediaController?.setMediaItem(mi)
                            mediaController?.prepare()
                            mediaController?.playWhenReady = true
                        }
                    }
                } catch (e: Exception) { }
            }
            return
        }
        bgScope.launch {
            diag("loadAudio($videoId) 시작")
            if (isInitial && reuseStreamUrl.isNotBlank() && videoId == currentVideoId) {
                currentSubtitleUrl = reuseSubtitleUrl
                val artist = currentArtist.ifBlank { currentChannel }
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(artist)
                    .setAlbumTitle(currentChannel)
                    .setArtworkUri(android.net.Uri.parse(currentThumb))
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(reuseStreamUrl)
                    .setMediaId(videoId)
                    .setMediaMetadata(metadata)
                    .build()
                runOnUiThread {
                    mediaController?.setMediaItem(mediaItem)
                    mediaController?.prepare()
                    try {
                        val mc = mediaController
                        if (mc != null) {
                            mc.trackSelectionParameters = mc.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                                .build()
                            diag("video 트랙 비활성화 완료 (reuse)")
                        }
                    } catch (e: Exception) {
                        diag("track disable reuse err: ${e.message}")
                    }
                    mediaController?.playWhenReady = true
                }
                addToHistory(videoId, currentTitle, currentChannel, currentThumb)
                reuseStreamUrl = ""
                reuseSubtitleUrl = ""
                delay(500)
                attachEqualizer()
                prefetchNext()
                loadingNext = false
                return@launch
            }

            if (isInitial) {
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "오디오 추출 중...", Toast.LENGTH_SHORT).show()
                }
            }

            // ★ extract 전에 ExoPlayer 완전히 정지 (transition 방지)
            runOnUiThread {
                try {
                    mediaController?.stop()
                    mediaController?.clearMediaItems()
                    diag("extract 전 ExoPlayer 정지")
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(50)

            extractInProgress = true
            val prefetched = prefetchedStreams.remove(videoId)
            val result = if (prefetched != null) {
                diag("prefetch hit: $videoId")
                prefetched
            } else {
                try {
                    YouTubeStream.extractAudioOnly(videoId)
                } finally {
                    extractInProgress = false
                }
            }
            extractInProgress = false
            diag("extractAudioOnly($videoId)")
            diag("  debug=${result.debug.take(200)}")

            if (result.isLive) {
                android.util.Log.d("AudioPlayer", "skip LIVE: $videoId")
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "라이브는 오디오 모드 제외", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                bgScope.launch { delay(500); playNextRelatedBg() }
                return@launch
            }

            val url = result.audioUrlBest
                ?: result.audioUrl
                ?: result.muxedUrl
                ?: result.videoUrl

            val fallbackUrl = result.muxedUrl ?: result.videoUrl ?: result.audioUrlBest

            if (url.isNullOrBlank() && fallbackUrl.isNullOrBlank()) {
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity,
                        "이 곡 실패 → 다음 곡", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                trySkipToNextInQueue(videoId)
                return@launch
            }

            val useUrl = url ?: fallbackUrl!!

            currentSubtitleUrl = result.subtitles
                .firstOrNull { it.languageCode.startsWith("ko") }?.url
                ?: result.subtitles.firstOrNull { it.languageCode.startsWith("en") }?.url
                ?: result.subtitles.firstOrNull()?.url
                ?: ""

            val artist = currentArtist.ifBlank { currentChannel }
            val metadata = MediaMetadata.Builder()
                .setTitle(currentTitle)
                .setArtist(artist)
                .setAlbumTitle(currentChannel)
                .setArtworkUri(android.net.Uri.parse(currentThumb))
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .setMediaId(videoId)
                .setMediaMetadata(metadata)
                .build()

            pref?.edit()
                ?.putString("current_video_id", videoId)
                ?.putString("current_title", currentTitle)
                ?.putString("current_channel", currentChannel)
                ?.putString("current_thumbnail", currentThumb)
                ?.putString("current_artist", currentArtist)
                ?.putString("current_subtitle_url", currentSubtitleUrl)
                ?.apply()

            lastMediaSetMs = System.currentTimeMillis()
            runOnUiThread {
                // ★ ExoPlayer 큐 완전히 비우기 (transition 잔재 방지)
                try {
                    val mc = mediaController
                    if (mc != null) {
                        // setMediaItem 전에 반드시 clear
                        mc.stop()
                        mc.clearMediaItems()
                        // 확실히 반영되도록 잠깐 대기 X (동기라 OK)
                        diag("ExoPlayer 큐 비움 (itemCount=${mc.mediaItemCount})")
                    }
                } catch (e: Exception) {
                    diag("clear err: ${e.message}")
                }
                // ★ setMediaItems(단일)로 큐 확실히 리셋
                mediaController?.setMediaItems(listOf(mediaItem), 0, 0L)
                mediaController?.prepare()
                try {
                    val mc = mediaController
                    if (mc != null) {
                        mc.trackSelectionParameters = mc.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                        diag("video 트랙 비활성화 완료 (itemCount=${mc.mediaItemCount})")
                    }
                } catch (e: Exception) {
                    diag("track disable err: ${e.message}")
                }
                mediaController?.playWhenReady = true
                updateUI()
            }

            addToHistory(videoId, currentTitle, currentChannel, currentThumb)
            failedIds.remove(videoId)

            delay(500)
            attachEqualizer()
            loadingNext = false
        }
    }

    private fun showSubscribeDialog() {
        if (currentChannel.isBlank()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(currentChannel)
            .setMessage("이 채널을 구독할까요?")
            .setPositiveButton("구독") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val dao = HistoryDatabase.get(applicationContext).subscriptionDao()
                        val exists = dao.isSubscribed(currentChannel)
                        if (exists) {
                            Toast.makeText(this@AudioPlayerActivity, "이미 구독 중", Toast.LENGTH_SHORT).show()
                        } else {
                            dao.insert(
                                SubscriptionEntity(
                                    channelId = currentChannel,
                                    name = currentChannel,
                                    avatar = "",
                                    subscribers = "",
                                    subscribedAt = System.currentTimeMillis()
                                )
                            )
                            Toast.makeText(this@AudioPlayerActivity, "구독됨: $currentChannel", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@AudioPlayerActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun attachPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startAnimation() else stopAnimation()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                runOnUiThread {
                    tvTitle.text = "[$stateName] $currentTitle"
                }
                val mc = mediaController
                diag("state=$stateName idx=${mc?.currentMediaItemIndex}/${mc?.mediaItemCount} " +
                     "next=${mc?.hasNextMediaItem()} cur=$currentVideoId")

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (bufferingStartMs == 0L) {
                            bufferingStartMs = System.currentTimeMillis()
                            startBufferingWatch()
                        }
                    }
                    Player.STATE_READY -> {
                        bufferingStartMs = 0L
                        bufferingWatchJob?.cancel()
                        bufferingWatchJob = null
                        idleRepeatCount = 0
                    }
                    Player.STATE_IDLE -> {
                        if (extractInProgress) {
                            diag("IDLE (extract 진행 중, 무시)")
                            return
                        }
                        val sinceSet = System.currentTimeMillis() - lastMediaSetMs
                        if (lastMediaSetMs > 0 && sinceSet < 3000) {
                            diag("IDLE (settle ${sinceSet}ms, 무시)")
                            return
                        }
                        val now = System.currentTimeMillis()
                        val gap = if (lastIdleMs > 0) now - lastIdleMs else Long.MAX_VALUE
                        val isPlayingNow = try { mediaController?.isPlaying == true } catch (_: Exception) { false }
                        if (isPlayingNow) {
                            diag("IDLE (playing=true, ignore)")
                            lastIdleMs = now
                            idleRepeatCount = 0
                        } else {
                            if (gap < 3000) idleRepeatCount++ else idleRepeatCount = 1
                            lastIdleMs = now
                            diag("IDLE count=$idleRepeatCount (gap=${gap}ms)")
                            try {
                                mediaController?.prepare()
                                mediaController?.play()
                            } catch (_: Exception) {}
                            if (idleRepeatCount >= 3) {
                                idleRepeatCount = 0
                                if (now - lastSkipMs < 5000) {
                                    diag("skip cooldown (${now - lastSkipMs}ms)")
                                } else {
                                    lastSkipMs = now
                                    diag("IDLE 3x -> next")
                                    trySkipToNextInQueue(currentVideoId)
                                }
                            }
                        }
                    }
                }

                if (playbackState == Player.STATE_ENDED) {
                    if (!loadingNext) {
                        loadingNext = true
                        bgScope.launch {
                            delay(300)
                            playNextRelatedBg()
                        }
                    }
                }
            }

            private fun startBufferingWatch() {
                bufferingWatchJob?.cancel()
                bufferingWatchJob = bgScope.launch {
                    try {
                        delay(15_000)
                        if (bufferingStartMs > 0 &&
                            System.currentTimeMillis() - bufferingStartMs >= 15_000) {
                            diag("BUFFERING 15초 초과 → 다음 곡")
                            trySkipToNextInQueue(currentVideoId)
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                try {
                    val curItem = mediaController?.currentMediaItem
                    val curUrl = curItem?.localConfiguration?.uri?.toString() ?: "(null)"
                    diag("❌ ERR ${error.errorCodeName}: ${error.message?.take(80)}")
                    diag("   url=${curUrl.take(80)}")
                } catch (_: Exception) {}
                val mc = mediaController
                if (mc != null && mc.hasNextMediaItem()) {
                    mc.seekToNextMediaItem()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newId = mediaItem?.mediaId
                if (newId.isNullOrBlank()) {
                    diag("transition 무시 (빈 mediaId)")
                    return
                }
                if (newId == currentVideoId) return
                diag("transition to $newId reason=$reason")

                // ★ ExoPlayer 자동 진행 (reason=3=MEDIA_ITEM_TRANSITION_REASON_AUTO)
                //   → 이제 정상 흐름, 무시하지 말고 상태만 갱신
                lastMediaSetMs = System.currentTimeMillis()
                loadingNext = false
                currentVideoId = newId
                currentTitle = mediaItem.mediaMetadata.title?.toString() ?: currentTitle
                currentChannel = mediaItem.mediaMetadata.artist?.toString() ?: currentChannel
                currentThumb = mediaItem.mediaMetadata.artworkUri?.toString() ?: currentThumb
                currentArtist = ""
                currentSubtitleUrl = pref?.getString("current_subtitle_url", "") ?: ""

                QueueManager.setCurrent(this@AudioPlayerActivity, newId)

                if (newId.startsWith("local:")) {
                    pref?.edit()
                        ?.putString("current_video_id", newId)
                        ?.putString("current_title", currentTitle)
                        ?.putString("current_channel", currentChannel)
                        ?.apply()
                }

                runOnUiThread {
                    updateUI()
                    updateLyricsButtonLabel()
                }
                // ★ 새 곡 시작 시 prefetch 재예약
                try { prefetchNext() } catch (_: Exception) {}
            }
        })
    }

    private fun playNextRelatedBg() {
        // ★★ ExoPlayer 큐에 다음 곡 있으면 skip (자동 진행)
        val mcCheck = mediaController
        if (mcCheck != null && mcCheck.hasNextMediaItem()) {
            diag("playNext: ExoPlayer 큐 있음 → skip (자동 진행)")
            loadingNext = false
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastPlayNextFailMs > 60_000) {
            playNextFailCount = 0
        }
        if (playNextFailCount >= 3) {
            diag("playNext: 60초 내 3회 실패 → 완전 정지")
            runOnUiThread {
                try {
                    mediaController?.stop()
                    mediaController?.clearMediaItems()
                } catch (_: Exception) {}
            }
            return
        }

        val mc = mediaController
        diag("playNextRelatedBg: mc=${mc != null} next=${mc?.hasNextMediaItem()} " +
             "idx=${mc?.currentMediaItemIndex}/${mc?.mediaItemCount}")
        if (mc != null && mc.hasNextMediaItem()) {
            loadingNext = false
            return
        }

        // ★ 최근 3초 내 이미 처리했으면 skip (transition 중복 방지)
        val sinceLast = System.currentTimeMillis() - lastMediaSetMs
        if (lastMediaSetMs > 0 && sinceLast < 3000) {
            diag("playNext skip: transition 직후 ($sinceLast ms)")
            loadingNext = false
            return
        }

        // ★★ 큐 우선 확인 (플레이리스트 순서 유지)
        val queue = QueueManager.get(this)
        val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
        if (curIdx >= 0 && curIdx < queue.size - 1) {
            // ★ 실패/재생중 곡 건너뛰기
            var nextIdx = curIdx + 1
            while (nextIdx < queue.size && queue[nextIdx].videoId in failedIds) {
                nextIdx++
            }
            if (nextIdx >= queue.size) {
                diag("playNext: 큐 다음 곡 없음 (모두 실패)")
                // 폴백으로 진행 (아래 코드)
            } else {
                // ★ 큐에서 제거하지 않음 (순서 유지)
                val nextFromQueue = queue[nextIdx]
                diag("playNext: 큐 다음 곡 → ${nextFromQueue.videoId} (${nextFromQueue.title.take(30)})")
                currentVideoId = nextFromQueue.videoId
                currentTitle = nextFromQueue.title
                currentChannel = nextFromQueue.channel
                currentThumb = nextFromQueue.thumbnail
                QueueManager.setCurrent(this, nextFromQueue.videoId)
                runOnUiThread { updateUI() }
                loadAudio(nextFromQueue.videoId, isInitial = false)
                return
            }
        }

        // ★★ 큐가 있으면 prefetch 캐시 사용 안 함 (순서 엄수)
        val queue2 = QueueManager.get(this)
        val curIdx2 = queue2.indexOfFirst { it.videoId == currentVideoId }
        val hasNextInQueue = curIdx2 >= 0 && curIdx2 < queue2.size - 1

        if (!hasNextInQueue) {
            // 큐 끝났을 때만 prefetch 캐시 사용
            val prefetchEntry = prefetchedStreams.entries.firstOrNull { it.key != currentVideoId }
            if (prefetchEntry != null) {
                val (prefetchId, _) = prefetchEntry
                diag("playNext: prefetch 캐시 히트 → $prefetchId (큐 끝)")
                prefetchedStreams.remove(prefetchId)
                bgScope.launch {
                    val related = try {
                        kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            YouTubeSearch.search(prefetchId)
                        } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    val meta = related.firstOrNull { it.videoId == prefetchId }
                    currentVideoId = prefetchId
                    currentTitle = meta?.title ?: "다음 곡"
                    currentChannel = meta?.channel ?: ""
                    currentThumb = meta?.thumbnail ?: ""
                    runOnUiThread { updateUI() }
                    loadAudio(prefetchId, isInitial = false)
                }
                return
            }
        }

        if (!isPlayingFromHistory && historyIndex >= 0 && historyIndex < audioHistory.size - 1) {
            historyIndex++
            val next = audioHistory[historyIndex]
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            runOnUiThread { updateUI() }
            loadAudio(next.videoId, isInitial = false)
            return
        }
        isPlayingFromHistory = false

        if (localOnlyMode) {
            loadingNext = false
            runOnUiThread {
                Toast.makeText(this@AudioPlayerActivity, "로컬 큐 끝", Toast.LENGTH_SHORT).show()
            }
            return
        }

        bgScope.launch {
            val disliked = pref?.getStringSet("disliked_ids", emptySet()) ?: emptySet()

            var related = emptyList<VideoItem>()

            // ★ 3-소스 병렬 (radio / artist / search 동시 실행)
            val artistName = currentArtist.ifBlank { currentChannel }
            // ★ 검색 키워드: 아티스트 우선, 뉴스/야구 등 제외
            val badKw = listOf("뉴스", "야구", "경기", "역전", "쓰리런", "불펜",
                               "특집", "광고", "라이브", "생방송", "중국", "일본",
                               "예능", "드라마", "시사", "정치", "속보", "사건",
                               "사고", "화재", "날씨", "주가", "부동산")
            val keyword = if (artistName.isNotBlank() && artistName.length >= 2) {
                artistName
            } else {
                currentTitle.split(" ")
                    .filter { it.isNotBlank() && it.length >= 2 }
                    .filter { w -> badKw.none { w.contains(it, true) } }
                    .take(3).joinToString(" ")
            }

            val radioDeferred = bgScope.async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(3_000) {
                        if (sameArtistMode) {
                            YouTubeArtist.fetchSongs(artistName, currentVideoId)
                        } else {
                            YouTubeRadio.fetchRelated(currentVideoId, currentTitle, currentChannel)
                        }.filter { it.videoId !in disliked }
                         .filter { it.videoId != currentVideoId }
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }
            val artistDeferred = bgScope.async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        if (artistName.isNotBlank())
                            YouTubeArtist.fetchSongs(artistName, currentVideoId)
                        else emptyList<VideoItem>()
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }
            val searchDeferred = bgScope.async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        if (keyword.isNotBlank())
                            YouTubeSearch.search(keyword)
                        else emptyList<VideoItem>()
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }

            // ★ 먼저 완료되는 것부터 채택 (순서: radio → artist → search)
            val radioResult = radioDeferred.await()
            diag("playNext radio: ${radioResult.size} 개")
            if (radioResult.isNotEmpty()) {
                related = radioResult.filter { it.videoId !in disliked }.filter { it.videoId != currentVideoId }
            } else {
                val artistResult = artistDeferred.await()
                diag("playNext artist: ${artistResult.size} 개")
                if (artistResult.isNotEmpty()) {
                    related = artistResult.filter { it.videoId !in disliked }.filter { it.videoId != currentVideoId }
                } else {
                    val searchResult = searchDeferred.await()
                    diag("playNext search: ${searchResult.size} 개")
                    related = searchResult.filter { it.videoId !in disliked }.filter { it.videoId != currentVideoId }
                }
            }
            // ★ 다음 곡 후보 캐시 (수동 seek 대비)
            if (related.isNotEmpty()) {
                lastRelatedCache = related
                lastRelatedCacheTime = System.currentTimeMillis()
            }

            if (related.isEmpty()) {
                diag("playNext: 유튜브 소스 실패 → 이력 폴백 시도")
                try {
                    val history = HistoryDatabase.get(applicationContext)
                        .historyDao().getAllOnce()
                    val baseFilter = history
                        .filter { it.videoId != currentVideoId }
                        .filter { it.videoId !in failedIds }
                        .filter { it.videoId !in disliked }

                    // ★ 1순위: 같은 아티스트/채널
                    val sameArtist = baseFilter.filter {
                        (currentArtist.isNotBlank() && it.channel.contains(currentArtist, true)) ||
                        (currentChannel.isNotBlank() && it.channel == currentChannel)
                    }.sortedByDescending { it.watchedAt }

                    // ★ 2순위: 한글 제목
                    val korean = baseFilter.filter { it.title.any { c -> c in '가'..'힣' } }
                        .sortedByDescending { it.watchedAt }

                    val pick = sameArtist.firstOrNull()
                        ?: korean.firstOrNull()
                        ?: baseFilter.sortedByDescending { it.watchedAt }.firstOrNull()

                    if (pick != null) {
                        diag("playNext: 이력 폴백 → ${pick.videoId} (${pick.title.take(30)})")
                        currentVideoId = pick.videoId
                        currentTitle = pick.title
                        currentChannel = pick.channel
                        currentThumb = pick.thumbnail
                        runOnUiThread { updateUI() }
                        loadAudio(pick.videoId, isInitial = false)
                        return@launch
                    }
                } catch (e: Exception) {
                    diag("playNext 이력 폴백 err: ${e.message}")
                }

                loadingNext = false
                playNextFailCount++
                lastPlayNextFailMs = System.currentTimeMillis()
                diag("playNext: 모든 방법 실패 (failCount=$playNextFailCount)")
                runOnUiThread {
                    try {
                        mediaController?.stop()
                        mediaController?.clearMediaItems()
                    } catch (_: Exception) {}
                }
                return@launch
            }

            val next = related.first()
            currentVideoId = next.videoId
            currentTitle = next.title
            currentChannel = next.channel
            currentThumb = next.thumbnail
            playNextFailCount = 0
            runOnUiThread { updateUI() }
            loadAudio(next.videoId, isInitial = false)
        }
    }

    private fun playNextManual() {
        val mc = mediaController
        if (mc != null && mc.hasNextMediaItem()) {
            mc.seekToNextMediaItem()
            return
        }
        if (loadingNext) return
        loadingNext = true
        bgScope.launch { playNextRelatedBg() }
    }

    private fun addToHistory(videoId: String, title: String, channel: String, thumb: String) {
        audioHistory.removeAll { it.videoId == videoId }
        audioHistory.add(AudioHistoryItem(videoId, title, channel, thumb))
        while (audioHistory.size > 50) audioHistory.removeAt(0)
        historyIndex = audioHistory.size - 1
    }

    private fun playPrevious() {
        val mc = mediaController
        if (mc != null && mc.hasPreviousMediaItem()) {
            mc.seekToPreviousMediaItem()
            return
        }
        if (historyIndex > 0) {
            historyIndex--
            val prev = audioHistory[historyIndex]
            isPlayingFromHistory = true
            currentVideoId = prev.videoId
            currentTitle = prev.title
            currentChannel = prev.channel
            currentThumb = prev.thumbnail
            updateUI()
            loadAudio(prev.videoId, isInitial = false)
        } else {
            mc?.seekTo(0)
        }
    }

    private fun dislikeCurrent() {
        if (currentVideoId.isBlank()) return
        val disliked = (pref?.getStringSet("disliked_ids", mutableSetOf()) ?: mutableSetOf()).toMutableSet()
        disliked.add(currentVideoId)
        pref?.edit()?.putStringSet("disliked_ids", disliked)?.apply()
        Toast.makeText(this, "다음부터 제외", Toast.LENGTH_SHORT).show()
        if (!loadingNext) {
            loadingNext = true
            bgScope.launch { delay(500); playNextRelatedBg() }
        }
    }

    private fun openLyrics() {
        if (currentSubtitleUrl.isBlank()) {
            Toast.makeText(this, "이 곡은 자막/가사가 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, LyricsActivity::class.java).apply {
            putExtra("VIDEO_ID", currentVideoId)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("VIDEO_CHANNEL", currentChannel)
            putExtra("VIDEO_ARTIST", currentArtist.ifBlank { currentChannel })
            putExtra("SUBTITLE_URL", currentSubtitleUrl)
        })
    }

    private fun showEqDialog() {
        if (PlaybackService.exoPlayer == null) return
        val session = PlaybackService.exoPlayer?.audioSessionId ?: 0
        if (session == 0 || !EqualizerManager.attach(session)) {
            Toast.makeText(this, "EQ 초기화 실패", Toast.LENGTH_SHORT).show()
            return
        }

        val bands = EqualizerManager.bandCount()
        val (minL, maxL) = EqualizerManager.levelRange()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val presetRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val presets = arrayOf("평탄", "저음", "고음", "V자")
        for (i in presets.indices) {
            val b = com.google.android.material.button.MaterialButton(this).apply {
                text = presets[i]
                textSize = 11f
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                setOnClickListener { EqualizerManager.applyPreset(i) }
            }
            presetRow.addView(b)
        }
        container.addView(presetRow)

        for (i in 0 until bands) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            }
            val hz = EqualizerManager.centerFreqHz(i)
            val label = android.widget.TextView(this).apply {
                text = if (hz > 0) "${hz}Hz" else "B$i"
                textSize = 11f
                setTextColor(0xFF8E8E93.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (60 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(label)

            val sb = SeekBar(this).apply {
                max = maxL - minL
                progress = EqualizerManager.getLevel(i) - minL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) EqualizerManager.setLevel(i, p + minL)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(sb)
            container.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("🎛 이퀄라이저")
            .setView(container)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun showLoudnessDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        val label = android.widget.TextView(this).apply {
            text = "음량 부스트: ${LoudnessManager.getTargetGain() / 100}dB"
            textSize = 14f
            setTextColor(0xFFF5F5F7.toInt())
        }
        container.addView(label)

        val seek = android.widget.SeekBar(this).apply {
            max = 1500
            progress = LoudnessManager.getTargetGain()
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                LoudnessManager.setTargetGain(p)
                label.text = "음량 부스트: ${p / 100}dB"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                pref?.edit()?.putInt("loudness_gain_mb", s?.progress ?: 0)?.apply()
            }
        })
        container.addView(seek)

        android.app.AlertDialog.Builder(this)
            .setTitle("🔊 음량 부스트 (0 ~ +15dB)")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                pref?.edit()?.putInt("loudness_gain_mb", seek.progress)?.apply()
            }
            .setNeutralButton("초기화") { _, _ ->
                LoudnessManager.setTargetGain(0)
                pref?.edit()?.putInt("loudness_gain_mb", 0)?.apply()
            }
            .show()
    }

    private fun toggleAudioLiveSubtitle() {
        startActivity(Intent(this, LiveSubtitleActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        diag("onNewIntent 호출")

        // 새 곡 정보
        val newVideoId = intent.getStringExtra("VIDEO_ID") ?: return
        val newTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val newChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val newThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        if (newVideoId == currentVideoId) {
            diag("onNewIntent: 같은 곡 → skip")
            return
        }

        diag("onNewIntent: 새 곡 → $newVideoId")
        currentVideoId = newVideoId
        currentTitle = newTitle
        currentChannel = newChannel
        currentThumb = newThumb

        // FROM_PLAYLIST면 큐 유지, 아니면 큐 클리어
        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist) {
            QueueManager.clear(this)
        }

        updateUI()
        loadAudio(newVideoId, isInitial = true)
    }

    override fun onResume() {
        super.onResume()
        audioLiveActive = false
        updateAudioLiveSubButton()
        diag("onResume: 재생 상태 유지 (video 트랙 건드리지 않음)")
    }

    override fun onStop() {
        super.onStop()
        sbCheckJob?.cancel()
        previewJob?.cancel()
        abJob?.cancel()
        cancelAutoNext()
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        // ★ 화면 꺼짐 다중 체크 (AOD/제조사 대응)
        val screenOn = try {
            @Suppress("DEPRECATION")
            val interactive = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
                pm.isInteractive
            } else {
                pm.isScreenOn
            }
            val isScreenOn = try { pm.isScreenOn } catch (_: Exception) { interactive }
            val isInteractive = try { pm.isInteractive } catch (_: Exception) { interactive }
            val displayOff = try {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state != android.view.Display.STATE_ON
            } catch (_: Exception) { false }
            diag("onStop screen check: interactive=$isInteractive, isScreenOn=$isScreenOn, displayOff=$displayOff")
            isInteractive && isScreenOn && !displayOff
        } catch (_: Exception) { true }

        val bgEnabled = pref?.getBoolean("keep_bg_playback", true) ?: true
        if (!bgEnabled) {
            diag("onStop: 백그라운드 재생 OFF → pause")
            try { mediaController?.pause() } catch (_: Exception) {}
        } else if (!screenOn) {
            diag("onStop: 화면 꺼짐 → 오디오 재생 유지")
            try { acquireWakeLock() } catch (_: Exception) {}
        } else {
            diag("onStop: 오디오 모드 → 재생 유지")
            try { acquireWakeLock() } catch (_: Exception) {}
        }
    }

    private fun updateAudioLiveSubButton() {
        try {
            if (audioLiveActive) {
                btnLiveSub.setTextColor(0xFFFF2D55.toInt())
                btnLiveSub.text = "🎙 ON"
            } else {
                btnLiveSub.setTextColor(0xFF8E8E93.toInt())
                btnLiveSub.text = "🎙"
            }
        } catch (e: Exception) { }
    }

    private fun openBatterySettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            Toast.makeText(this, "Android 6.0 이상 필요", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "✅ 이미 배터리 최적화 제외됨", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "설정을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleBackgroundPlayback() {
        val on = pref?.getBoolean("keep_bg_playback", true) ?: true
        val next = !on
        pref?.edit()?.putBoolean("keep_bg_playback", next)?.apply()
        Toast.makeText(
            this,
            if (next) "🔋 백그라운드 재생 ON\n(화면 꺼져도 다음 곡 재생)" else "🔋 백그라운드 재생 OFF\n(화면 꺼지면 정지)",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleKeepScreenOn() {
        val on = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "🔴 화면 꺼짐 허용", Toast.LENGTH_SHORT).show()
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "🟢 화면 켜짐 유지 ON", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLyricsButtonLabel() {
        try {
            val btn = findViewById<MaterialButton>(R.id.btnLyrics) ?: return
            btn.text = if (currentSubtitleUrl.isNotBlank()) "📝" else "📜"
        } catch (e: Exception) { }
    }

    private fun isMusicLike(title: String): Boolean {
        val goodKeywords = listOf(
            "노래모음", "노래 모음", "playlist", "플레이리스트", "재생목록",
            "1시간", "2시간", "3시간", "한시간", "hours", "hour",
            "가사", "lyrics", "ost", "bpm", "music", "음악",
            "노래", "song", "album", "앨범", "커버", "cover",
            "명품", "소울", "발라드", "kpop", "k-pop", "cafe", "카페",
            "bgm", "study", "공부", "릴렉스", "relax", "chill"
        )
        if (goodKeywords.any { title.contains(it, ignoreCase = true) }) return true

        val badKeywords = listOf(
            "뉴스", "속보", "인터뷰", "강연", "토크", "팟캐스트", "podcast",
            "ep.", "회차", "라이브", "생방송", "예능", "드라마", "시사",
            "뉴스룸", "긴급", "특집", "다큐", "설명", "강의",
            "한국사", "역사", "과학", "다큐멘터리", "웨비나", "세미나"
        )
        return badKeywords.none { title.contains(it, ignoreCase = true) }
    }

    private fun attachListeners() {
        val mc = mediaController ?: return

        btnPlay.setOnClickListener { if (mc.isPlaying) mc.pause() else mc.play() }
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            mc.seekTo((mc.currentPosition - 10_000).coerceAtLeast(0))
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            val dur = mc.duration
            val newPos = mc.currentPosition + 10_000
            mc.seekTo(if (dur > 0) newPos.coerceAtMost(dur) else newPos)
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { playPrevious() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { playNextManual() }

        btnRepeat.setOnClickListener { toggleRepeat() }
        btnLiveSub.setOnClickListener { toggleAudioLiveSubtitle() }
        findViewById<MaterialButton>(R.id.btnMoreAudio).setOnClickListener {
            AlertDialog.Builder(this)
                .setItems(arrayOf(
                    "💡 화면 켜짐 유지 (토글)",
                    "🔋 백그라운드 재생 유지 (토글)",
                    "⚙️ 배터리 최적화 설정 열기",
                    "🎛 이퀄라이저",
                    "🔊 음량 부스트",
                    "👎 싫어요 (다음부터 제외)",
                    "📺 영상 모드로 전환",
                    "🎚 재생 속도"
                )) { _, which ->
                    when (which) {
                        0 -> toggleKeepScreenOn()
                        1 -> toggleBackgroundPlayback()
                        2 -> openBatterySettings()
                        3 -> showEqDialog()
                        4 -> showLoudnessDialog()
                        5 -> dislikeCurrent()
                        6 -> {
                            if (currentVideoId.startsWith("local:") || currentVideoId.isBlank()) {
                                Toast.makeText(this, "로컬 파일은 영상 모드가 없습니다", Toast.LENGTH_SHORT).show()
                            } else {
                                val pos = mediaController?.currentPosition ?: 0L
                                startActivity(Intent(this, PlayerActivity::class.java).apply {
                                    putExtra("VIDEO_ID", currentVideoId)
                                    putExtra("VIDEO_TITLE", currentTitle)
                                    putExtra("VIDEO_CHANNEL", currentChannel)
                                    putExtra("VIDEO_THUMB", currentThumb)
                                    putExtra("IS_SAME_VIDEO", true)
                                    putExtra("CURRENT_POS", pos)
                                })
                                finish()
                            }
                        }
                        7 -> showSpeedDialog()
                    }
                }
                .show()
        }
        btnRepeat.setOnLongClickListener { dislikeCurrent(); true }
        btnSpeed.setOnClickListener { showSpeedDialog() }

        findViewById<MaterialButton>(R.id.btnLyrics).setOnClickListener { openLyrics() }
        findViewById<MaterialButton>(R.id.btnEq).setOnClickListener { showEqDialog() }
        findViewById<MaterialButton>(R.id.btnDislike).setOnClickListener { dislikeCurrent() }
        findViewById<MaterialButton>(R.id.btnVideoMode).setOnClickListener {
            if (currentVideoId.startsWith("local:") || currentVideoId.isBlank()) {
                Toast.makeText(this, "로컬 파일은 영상 모드가 없습니다", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_ID", currentVideoId)
                    putExtra("VIDEO_TITLE", currentTitle)
                    putExtra("VIDEO_CHANNEL", currentChannel)
                    putExtra("VIDEO_THUMB", currentThumb)
                })
                finish()
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isDragging) {
                    val dur = mc.duration
                    if (dur > 0) mc.seekTo((dur * progress / 1000))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isDragging = false
                val dur = mc.duration
                if (dur > 0) mc.seekTo((dur * (sb?.progress ?: 0) / 1000))
                // ★ 끝 90% 이상으로 이동 시 prefetch 즉시 시작
                val pos = sb?.progress ?: 0
                if (pos >= 900 && prefetchedStreams.isEmpty()) {
                    diag("seekbar 90%+ → prefetch 즉시 시작")
                    try { prefetchNext() } catch (_: Exception) {}
                }
            }
        })
    }

    private fun toggleRepeat() {
        val mc = mediaController ?: return
        val next = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        mc.repeatMode = next
        btnRepeat.text = when (next) {
            Player.REPEAT_MODE_ONE -> "🔁1"
            Player.REPEAT_MODE_ALL -> "🔁A"
            else -> "🔁"
        }
    }

    private fun showSpeedDialog() {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog.Builder(this)
            .setTitle("재생 속도")
            .setItems(speeds.map { "${it}x" }.toTypedArray()) { _, i ->
                mediaController?.setPlaybackSpeed(speeds[i])
                btnSpeed.text = "${speeds[i]}x"
            }
            .show()
    }

    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = bgScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && !isDragging) {
                    val pos = mc.currentPosition
                    val dur = mc.duration
                    runOnUiThread {
                        tvPos.text = fmt(pos)
                        tvDur.text = if (dur > 0) fmt(dur) else "0:00"
                        if (dur > 0) seekBar.progress = (pos * 1000 / dur).toInt()
                        btnPlay.setImageResource(
                            if (mc.isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun startAnimation() {
        if (pulseAnimator != null) return
        pulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val s = 1f + t * 0.06f
                ivArt.scaleX = s
                ivArt.scaleY = s
                ivBackground.rotation = -3f + t * 6f
                ivBackground.scaleX = 1.1f + t * 0.05f
                ivBackground.scaleY = 1.1f + t * 0.05f
                ivBackground.alpha = 0.15f + t * 0.15f
            }
            start()
        }
    }

    private fun stopAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        ivArt.scaleX = 1f
        ivArt.scaleY = 1f
        ivBackground.rotation = 0f
        ivBackground.scaleX = 1f
        ivBackground.scaleY = 1f
        ivBackground.alpha = 0.25f
    }

    private fun fmt(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun onVideoEnded() {
        val nextId = "다음"
        if (!loadingNext) {
            loadingNext = true
            bgScope.launch {
                delay(300)
                playNextRelatedBg()
            }
        }
    }

    private fun cancelAutoNext() {
        // 카운트다운 없음 (오디오 모드)
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
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                HistoryDatabase.get(appCtx).historyDao().updatePosition(vid, pos, durationMs)
            } catch (e: Exception) { }
        }
    }

    private fun stopLiveSubtitle() {
        liveSubtitleActive = false
        updateAudioLiveSubButton()
        try { liveCaptureManager?.stop() } catch (_: Exception) {}
        liveCaptureManager = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try {
            findViewById<View>(R.id.liveSubtitleOverlay)?.visibility = View.GONE
        } catch (_: Exception) {}
    }

    private fun startLiveCapture() {
        val mp = mediaProjection ?: return
        if (liveGroqManager == null) liveGroqManager = GroqSttManager(BuildConfig.GROQ_API_KEY)
        liveGroqManager?.reset()
        liveSubtitleActive = true
        updateAudioLiveSubButton()
        liveBuilder.setLength(0)
        lastLiveText = ""
        liveCaptureManager = AudioCaptureManager(this) { chunk ->
            liveGroqManager?.transcribeChunk(chunk, "ko",
                onResult = { text ->
                    val newText = if (lastLiveText.isNotEmpty() && text.startsWith(lastLiveText))
                        text.removePrefix(lastLiveText).trim() else text
                    if (newText.isNotBlank()) {
                        lastLiveText = text
                        liveBuilder.append(newText).append(" ")
                    }
                },
                onError = { }
            )
        }
        if (liveCaptureManager?.start(mp) != true) {
            Toast.makeText(this, "캡처 실패", Toast.LENGTH_SHORT).show()
            stopLiveSubtitle()
        }
    }

    private fun loadSponsorSegments(videoId: String) {
        sbCheckJob?.cancel()
        sponsorSegments = emptyList()
        if (!sbEnabled || videoId.isBlank()) return
        bgScope.launch {
            try {
                sponsorSegments = SponsorBlock.fetch(videoId, sbCategories)
            } catch (_: Exception) {}
        }
        sbCheckJob = bgScope.launch {
            while (isActive) {
                checkAndSkip()
                delay(1000)
            }
        }
    }

    private fun checkAndSkip() {
        if (!sbEnabled) return
        if (sponsorSegments.isEmpty()) return
        val pos = mediaController?.currentPosition ?: return
        for (seg in sponsorSegments) {
            if (pos >= seg.startMs && pos < seg.endMs - 200) {
                mediaController?.seekTo(seg.endMs)
                break
            }
        }
    }

    private fun startPreviewWatcher() {
        previewShown = false
        previewJob?.cancel()
        previewJob = bgScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null) {
                    val dur = mc.duration
                    val pos = mc.currentPosition
                    if (dur > 0 && !previewShown && dur - pos in 1..10000 && pos > 5000) {
                        previewShown = true
                    }
                }
                delay(1000)
            }
        }
    }

    private fun cycleAbRepeat() {
        val mc = mediaController ?: return
        val pos = mc.currentPosition
        when {
            abStart < 0 -> {
                abStart = pos
                abEnd = -1
            }
            abEnd < 0 -> {
                if (pos > abStart + 1000) {
                    abEnd = pos
                    startAbLoop()
                }
            }
            else -> {
                abStart = -1
                abEnd = -1
                abJob?.cancel()
            }
        }
    }

    private fun startAbLoop() {
        abJob?.cancel()
        abJob = bgScope.launch {
            while (isActive) {
                val mc = mediaController
                if (mc != null && abStart >= 0 && abEnd > 0) {
                    if (mc.currentPosition >= abEnd) {
                        mc.seekTo(abStart)
                    }
                } else {
                    return@launch
                }
                delay(500)
            }
        }
    }

    private fun extractAndPlay(
        videoId: String, title: String, channel: String, thumb: String, startPosMs: Long = 0L
    ) {
        currentVideoId = videoId
        currentTitle = title
        currentChannel = channel
        currentThumb = thumb
        runOnUiThread { updateUI() }
        loadAudio(videoId, isInitial = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        PlaybackService.nextTrackHandler = null
        stopAnimation()
        updateJob?.cancel()
        bgScope.cancel()
        try { LoudnessManager.release() } catch (_: Exception) {}

        try {
            val bgEnabled = pref?.getBoolean("keep_bg_playback", true) ?: true
            val isPlaying = mediaController?.isPlaying == true
            val playWhenReady = mediaController?.playWhenReady == true

            // ★ 백그라운드 재생 ON이면 무조건 release 안 함
            if (bgEnabled) {
                diag("onDestroy: 백그라운드 ON → MediaController 유지 (isPlaying=$isPlaying, playWhenReady=$playWhenReady)")
                try { acquireWakeLock() } catch (_: Exception) {}
            } else {
                releaseWakeLock()
                MediaController.releaseFuture(controllerFuture)
                diag("onDestroy: 백그라운드 OFF → release")
            }
        } catch (e: Exception) {
            try { MediaController.releaseFuture(controllerFuture) } catch (_: Exception) {}
        }
    }
}
