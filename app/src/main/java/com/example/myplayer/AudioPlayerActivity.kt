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
import kotlinx.coroutines.withContext
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
    private var pendingStartPos: Long = 0L
    private val isLocalMode: Boolean
        get() = intent.getBooleanExtra("LOCAL_MODE", false)
            || intent.getBooleanExtra("PLAY_DOWNLOAD_QUEUE", false)
            || currentVideoId.startsWith("local:")
    private val nextPreviewList = mutableListOf<HomeVideo>()
    private var audioSwipeDetector: android.view.GestureDetector? = null   // ★ 다음 곡 미리보기용   // ★ 이어듣기 시작 위치
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

        // ★★★ Activity가 다음 곡 관리 (PlaybackService 경쟁 방지)
        PlaybackService.autoNextDisabled = true

        // ★ 이전 종료 상태 감지 → 플래그 해제 후 계속 진행
        val wasStopped = pref?.getBoolean("service_stopped", false) == true
        if (wasStopped) {
            android.util.Log.d("AudioPlayer", "onCreate: 이전 service_stopped=true → 해제")
            pref?.edit()?.putBoolean("service_stopped", false)?.apply()
        }

        // ★ keep_bg_playback 기본값 강제 (사용자가 명시적으로 OFF 안 했으면 true)
        val userSetBg = pref?.contains("keep_bg_playback") ?: false
        if (!userSetBg) {
            pref?.edit()?.putBoolean("keep_bg_playback", true)?.apply()
            android.util.Log.d("AudioPlayer", "onCreate: keep_bg_playback=true 기본값 설정")
        }

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

        // ★ 다음 곡 미리보기 리스트 초기화 (onCreate)
        try {
            val nextRv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNextSongs)
            if (nextRv != null) {
                nextRv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                    this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
                )
                nextRv.adapter = NextSongsAdapter()
                startNextSongsUpdate()
                android.util.Log.d("AudioPlayer", "다음 곡 리스트 초기화 완료")

                // ★ 스와이프 제스처 설정
                setupSwipeGestures()
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "다음 곡 리스트 초기화 err: ${e.message}", e)
        }


        currentVideoId = intent.getStringExtra("VIDEO_ID") ?: ""
        currentTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        currentChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        currentThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""

        // ★★ Activity 재생성 시 상태 복원 (로컬/YouTube 구분)
        val fromLocalIntent = intent.getBooleanExtra("LOCAL_MODE", false)
        val savedId = pref?.getString("current_video_id", null) ?: ""

        // 케이스 A: Intent에 곡 정보가 없음 → prefs에서 복원
        if (currentVideoId.isBlank()) {
            if (fromLocalIntent) {
                // 로컬 모드: prefs에서 로컬 곡만 복원
                if (savedId.startsWith("local:")) {
                    currentVideoId = savedId
                    currentTitle = pref?.getString("current_title", "") ?: ""
                    currentChannel = pref?.getString("current_channel", "") ?: ""
                    currentThumb = pref?.getString("current_thumbnail", "") ?: ""
                    diag("onCreate: 로컬 상태 복원 ${savedId.take(30)}")
                }
            } else {
                // 일반 모드: YouTube 곡만 복원
                if (savedId.isNotBlank() && !savedId.startsWith("local:")) {
                    currentVideoId = savedId
                    currentTitle = pref?.getString("current_title", "") ?: ""
                    currentChannel = pref?.getString("current_channel", "") ?: ""
                    currentThumb = pref?.getString("current_thumbnail", "") ?: ""
                    diag("onCreate: YouTube 상태 복원 ${savedId.take(30)}")
                }
            }
        }
        // 케이스 B: intent가 YouTube 곡인데 prefs는 로컬 → intent 우선 (사용자가 명시적으로 선택)

        // ★ 구독 알림에서 진입 시 처리
        val fromSubNotif = intent.getBooleanExtra("FROM_SUB_NOTIFICATION", false)
        if (fromSubNotif) {
            diag("onCreate: 구독 알림에서 진입")
            // ★ 알림 전체 제거 (그룹)
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                nm.cancel(7000)   // 그룹 요약
                for (id in 7001..7010) nm.cancel(id)
            } catch (_: Exception) {}

            // ★ 옵션 2: QUEUE_JSON 처리 (순차 재생)
            val queueJson = intent.getStringExtra("QUEUE_JSON") ?: ""
            if (queueJson.isNotBlank() && queueJson != "[]") {
                try {
                    val arr = org.json.JSONArray(queueJson)
                    QueueManager.clear(this)
                    var added = 0
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val videoId = o.optString("videoId", "")
                        if (videoId.isBlank()) continue
                        QueueManager.add(this,
                            HomeVideo(
                                videoId = videoId,
                                title = o.optString("title", ""),
                                channel = o.optString("channel", ""),
                                thumbnail = o.optString("thumbnail", "")
                            )
                        )
                        added++
                    }
                    // ★ 첫 곡 재생 예약
                    if (added > 0) {
                        val first = arr.getJSONObject(0)
                        currentVideoId = first.optString("videoId", currentVideoId)
                        currentTitle = first.optString("title", currentTitle)
                        currentChannel = first.optString("channel", currentChannel)
                        currentThumb = first.optString("thumbnail", currentThumb)
                        diag("onCreate: 큐에 ${added}개 추가, 첫 곡: $currentVideoId")
                    }
                } catch (e: Exception) {
                    diag("onCreate: QUEUE_JSON 파싱 err: ${e.message}")
                }
            } else {
                // 단일 재생
                QueueManager.clear(this)
            }
        }

        val fromPlaylist = intent.getBooleanExtra("FROM_PLAYLIST", false)
        if (!fromPlaylist && !fromSubNotif) {
            QueueManager.clear(this)
            // ★★★ fresh selection → 현재 곡 즉시 큐 추가 (title fallback)
            if (currentVideoId.isNotBlank() && !currentVideoId.startsWith("local:")) {
                val title = currentTitle.ifBlank { currentVideoId }
                val channel = currentChannel.ifBlank { "YouTube" }
                QueueManager.add(this, HomeVideo(currentVideoId, title, channel, currentThumb))
                diag("onCreate: fresh selection → 큐 즉시 추가 $currentVideoId")
            }
        }

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
                // ★ 단순화: mc의 현재 아이템이 같거나, prefs와 같고 재생 상태면 skip
                val isSameTrack = mc?.currentMediaItem?.mediaId == currentVideoId
                val isPrefSameAndReady = prefVideoId == currentVideoId && isPlayingState

                val alreadyPlaying = mc != null && (isSameTrack || isPrefSameAndReady)

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
                    // ★★★ 이어듣기 다이얼로그 ★★★
                    checkResumeAndPlayAudio(currentVideoId)
                }
            } else {
                diag("onCreate skip failed: $currentVideoId")
            }
        }, MoreExecutors.directExecutor())

        // ★ Bottom handle → 재생목록
        try {
            findViewById<android.view.View>(R.id.bottomHandle)?.setOnClickListener {
                diag("bottomHandle 클릭")
                showPlaylistSheet()
            }
        } catch (_: Exception) {}

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

    /** ★ 큐는 안 건드리고 다음 곡 스트림만 캐시 */
    private fun prefetchNextStreamOnly() {
        bgScope.launch {
            try {
                val q = QueueManager.get(this@AudioPlayerActivity)
                val idx = q.indexOfFirst { it.videoId == currentVideoId }
                if (idx < 0 || idx >= q.size - 1) return@launch
                val next = q[idx + 1]
                if (prefetchedStreams.containsKey(next.videoId)) return@launch
                val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    YouTubeStream.extractAudioOnly(next.videoId)
                } ?: return@launch
                if (result.hasAny) {
                    prefetchedStreams[next.videoId] = result
                    diag("prefetchStreamOnly: ${next.videoId} 캐시 완료")
                }
            } catch (_: Exception) {}
        }
    }

    
    private fun prefetchNext() {
        if (isLocalMode) {
            diag("prefetch: local 모드 → skip")
            return
        }
        
        // ★ 다운로드된 곡은 프리페치 필요 없음 (로컬 즉시 재생)
        bgScope.launch {
            try {
                val dao = HistoryDatabase.get(applicationContext).downloadDao()
                val downloaded = dao.getAllOnce().filter { it.isAudioOnly }.map { it.videoId }.toSet()
                if (downloaded.isNotEmpty()) {
                    diag("prefetch: 다운로드된 곡 ${downloaded.size}개 (로컬 우선)")
                }
            } catch (_: Exception) {}
        }
        if (pref?.getBoolean("service_stopped", false) == true) {
            diag("prefetch: service_stopped=true → skip")
            return
        }

        // ★ 이미 실행 중이면 skip (중복 방지)
        if (prefetchJob?.isActive == true) {
            diag("prefetch: 이미 실행 중 → skip")
            return
        }
        
        prefetchJob = bgScope.launch {
            try {
                if (extractInProgress) {
                    diag("prefetch: extract 진행 중 → skip")
                    return@launch
                }

                val queue = QueueManager.get(this@AudioPlayerActivity)
                val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
                
                // ★ 큐에 다음 3곡 미리 캐시 (병렬)
                val toPrefetch = mutableListOf<HomeVideo>()
                if (curIdx >= 0) {
                    for (i in (curIdx + 1) until minOf(curIdx + 6, queue.size)) {
                        val item = queue[i]
                        if (item.videoId !in failedIds && !prefetchedStreams.containsKey(item.videoId)) {
                            toPrefetch.add(item)
                        }
                    }
                }
                
                // ★ 큐가 부족하면 라디오 검색을 병렬로 (곡 끝나기 전에 준비)
                if (toPrefetch.isEmpty() || curIdx >= queue.size - 2) {
                    diag("prefetch: 큐 부족 → 라디오 병렬 검색 시작")
                    bgScope.launch {
                        try {
                            fillQueueFromRadio()
                        } catch (e: Exception) {
                            diag("fillQueue err: ${e.message}")
                        }
                    }
                }
                
                // ★ 큐의 다음 곡들 순차 캐시 (Piped 동시 요청 부담 회피)
                diag("prefetch: toPrefetch.size=${toPrefetch.size}, ids=${toPrefetch.map { it.videoId.take(11) }}")
                if (toPrefetch.isNotEmpty()) {
                    diag("prefetch: ${toPrefetch.size}곡 순차 캐시 시작")
                    bgScope.launch {
                        for ((idx, item) in toPrefetch.withIndex()) {
                            try {
                                if (prefetchedStreams.containsKey(item.videoId)) {
                                    diag("prefetch skip (이미 캐시): ${item.videoId}")
                                    continue
                                }
                                if (prefetchedStreams.size >= 5) {
                                    diag("prefetch 중단 (캐시 가득: ${prefetchedStreams.size})")
                                    break
                                }
                                // ★ 첫 곡은 빠르게, 나머지는 Piped 부하 고려 (1초 간격)
                                if (idx > 0) delay(1000)
                                
                                diag("prefetch 시작[$idx]: ${item.videoId}")
                                val result = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                                    YouTubeStream.extractAudioOnly(item.videoId)
                                }
                                if (result == null) {
                                    diag("prefetch timeout[$idx]: ${item.videoId}")
                                    continue
                                }
                                if (result.hasAny) {
                                    prefetchedStreams[item.videoId] = result
                                    diag("prefetch 완료[$idx]: ${item.videoId} (cache=${prefetchedStreams.size})")
                                } else {
                                    diag("prefetch 실패[$idx]: ${item.videoId} (hasAny=false)")
                                }
                            } catch (e: Exception) {
                                diag("prefetch err[$idx]: ${item.videoId} - ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                diag("prefetch err: ${e.message}")
            }
        }
    }
    
    /** 큐가 부족할 때 라디오에서 채우기 (병렬 소스) */
    private suspend fun fillQueueFromRadio() {
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
                    else emptyList()
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        
        val radioList = radioDeferred.await()
        val artistList = artistDeferred.await()
        val combined = (radioList + artistList)
            .distinctBy { it.videoId }
            .filter { it.videoId != currentVideoId && it.videoId !in failedIds }
            .take(10)
        
        if (combined.isNotEmpty()) {
            for (v in combined) {
                val hv = HomeVideo(v.videoId, v.title, v.channel, v.thumbnail)
                QueueManager.add(this@AudioPlayerActivity, hv)
            }
            diag("라디오 ${combined.size}곡 큐 추가")
            
            // ★ 추가된 곡 즉시 병렬 캐시
            combined.take(3).forEach { v ->
                bgScope.launch {
                    try {
                        val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                            YouTubeStream.extractAudioOnly(v.videoId)
                        } ?: return@launch
                        if (result.hasAny) {
                            prefetchedStreams[v.videoId] = result
                            diag("라디오 prefetch: ${v.videoId} (${v.title.take(20)})")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }


    /** ★ 비활성화: ExoPlayer 큐에 미리 추가하지 않음 (조기 재생 방지) */
    private fun addToExoQueue(target: HomeVideo, result: YouTubeStream.StreamResult) {
        diag("addToExoQueue: skip — ${target.videoId} (조기 재생 방지)")
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
        // ★★★ prefs와 현재 상태 재확인 (동기화 보장)
        try {
            val prefId = pref?.getString("current_video_id", null)
            if (prefId != null && prefId.isNotBlank() && prefId != currentVideoId) {
                diag("updateUI: prefs와 현재 상태 불일치 → prefs 우선 ($prefId vs $currentVideoId)")
                currentVideoId = prefId
                currentTitle = pref?.getString("current_title", "") ?: currentTitle
                currentChannel = pref?.getString("current_channel", "") ?: currentChannel
                currentThumb = pref?.getString("current_thumbnail", "") ?: currentThumb
            }
        } catch (_: Exception) {}

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
            // ★ local 실패 시 YouTube 검색 금지 (playNextLocal로)
            if (failedVideoId.startsWith("local:") || isLocalMode) {
                diag("trySkip: local → playNextLocal (YouTube X)")
                failedIds.add(failedVideoId)
                loadingNext = false
                bgScope.launch { delay(300); playNextLocal() }
                return
            }

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
        // ★ 큐 위치 동기화
        try {
            QueueManager.setCurrent(this, videoId)
            diag("loadAudio setCurrent: $videoId")
        } catch (_: Exception) {}

        // ★★★ 현재 곡이 큐에 없으면 추가 (title fallback)
        try {
            if (!videoId.startsWith("local:")) {
                val queue = QueueManager.get(this)
                val exists = queue.any { it.videoId == videoId }
                if (!exists) {
                    val title = currentTitle.ifBlank { videoId }
                    val channel = currentChannel.ifBlank { "YouTube" }
                    QueueManager.add(this,
                        HomeVideo(videoId, title, channel, currentThumb))
                    diag("loadAudio: 현재 곡을 큐에 추가 $videoId (title=$title)")
                }
            }
        } catch (_: Exception) {}

        // ★★★ prefetch 캐시 히트 시 즉시 재생 (extract 스킵) ★★★
        val cached = prefetchedStreams.remove(videoId)
        diag("loadAudio 캐시 확인: videoId=$videoId, cached=${cached != null}, cacheSize=${prefetchedStreams.size + (if (cached != null) 1 else 0)}")
        if (cached != null) {
            diag("  cached.hasAny=${cached.hasAny}, muxed=${cached.muxedUrl?.take(50)}")
        }
        if (cached != null && cached.hasAny) {
            val cachedUrl = cached.audioUrlBest ?: cached.audioUrl
                ?: cached.muxedUrl ?: cached.videoUrl
            if (!cachedUrl.isNullOrBlank()) {
                diag("loadAudio: ⚡ prefetch 캐시 히트! 즉시 재생")
                val artist = currentArtist.ifBlank { currentChannel }
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(artist)
                    .setAlbumTitle(currentChannel)
                    .setArtworkUri(android.net.Uri.parse(currentThumb))
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(cachedUrl)
                    .setMediaId(videoId)
                    .setMediaMetadata(metadata)
                    .build()
                runOnUiThread {
                    mediaController?.setMediaItem(mediaItem)
                    mediaController?.seekTo(0)   // ★ 새 곡은 처음부터
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                    try {
                        val mc = mediaController
                        if (mc != null) {
                            mc.trackSelectionParameters = mc.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                                .build()
                        }
                    } catch (_: Exception) {}
                }
                pref?.edit()
                    ?.putString("current_stream_url", cachedUrl)
                    ?.putString("current_video_id", videoId)
                    ?.apply()
                addToHistory(videoId, currentTitle, currentChannel, currentThumb)
                failedIds.remove(videoId)
                // ★ 즉시 다음 prefetch 재개
                bgScope.launch { delay(500); prefetchNext() }
                return
            }
        }

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
            // ★★★ PLAY_DOWNLOAD_QUEUE: 모든 다운로드를 ExoPlayer 큐에 한 번에 setMediaItems
            val playDownloadQueue = intent.getBooleanExtra("PLAY_DOWNLOAD_QUEUE", false)
            if (playDownloadQueue) {
                diag("loadAudio: PLAY_DOWNLOAD_QUEUE → 다운로드 전체를 ExoPlayer 큐로")
                bgScope.launch {
                    try {
                        val dao = HistoryDatabase.get(applicationContext).downloadDao()
                        val allDownloads = withContext(Dispatchers.IO) {
                            dao.getAllOnce().filter { d ->
                                d.isAudioOnly && java.io.File(d.filePath).exists()
                            }
                        }
                        if (allDownloads.isEmpty()) {
                            diag("loadAudio: 다운로드 없음")
                            return@launch
                        }

                        val items = mutableListOf<MediaItem>()
                        var startIdx = 0
                        for ((idx, d) in allDownloads.withIndex()) {
                            val file = java.io.File(d.filePath)
                            val fileUri = android.net.Uri.fromFile(file).toString()
                            val mediaId = "local:download:${d.videoId}"
                            val metadata = androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(d.title)
                                .setArtist(d.channel)
                                .setArtworkUri(
                                    if (d.thumbnail.isNotBlank()) android.net.Uri.parse(d.thumbnail)
                                    else null
                                )
                                .build()
                            items.add(
                                MediaItem.Builder()
                                    .setUri(fileUri)
                                    .setMediaId(mediaId)
                                    .setMediaMetadata(metadata)
                                    .build()
                            )
                            if (d.videoId == videoId.removePrefix("local:download:")) {
                                startIdx = idx
                            }
                        }
                        diag("loadAudio: 다운로드 큐 items=${items.size} start=$startIdx")

                        runOnUiThread {
                            try {
                                mediaController?.setMediaItems(items, startIdx, 0L)
                                mediaController?.prepare()
                                mediaController?.playWhenReady = true
                            } catch (e: Exception) {
                                diag("loadAudio 다운로드 큐 err: ${e.message}")
                            }
                        }

                        // ★ prefs 업데이트
                        pref?.edit()
                            ?.putString("current_video_id", videoId)
                            ?.putString("current_title", currentTitle)
                            ?.putString("current_channel", currentChannel)
                            ?.putString("current_thumbnail", currentThumb)
                            ?.apply()
                    } catch (e: Exception) {
                        diag("loadAudio PLAY_DOWNLOAD_QUEUE err: ${e.message}")
                    }
                }
                return
            }

            val ytId = videoId.removePrefix("local:download:")
            var fileUri = intent.getStringExtra("FILE_URI")
            if (fileUri.isNullOrBlank()) {
                fileUri = pref?.getString("local_uri_$videoId", null)
                diag("local:download: prefs 복원 uri=$fileUri")
            }
            if (!fileUri.isNullOrBlank()) {
                pref?.edit()?.putString("local_uri_$videoId", fileUri)?.apply()
                diag("local:download 재생 시작 (mediaId=$videoId)")
                try {
                    val mi = MediaItem.Builder()
                        .setUri(fileUri)
                        .setMediaId(videoId)
                        .build()
                    mediaController?.setMediaItem(mi)
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                } catch (e: Exception) {
                    diag("local:download err: ${e.message}")
                }
                return
            }

            diag("local:download: URI 없음 → DB 조회 ($ytId)")
            bgScope.launch {
                val download = withContext(Dispatchers.IO) {
                    try {
                        HistoryDatabase.get(applicationContext).downloadDao().getById(ytId)
                    } catch (e: Exception) { null }
                }
                if (download != null) {
                    val file = java.io.File(download.filePath)
                    if (file.exists()) {
                        val uri = android.net.Uri.fromFile(file).toString()
                        pref?.edit()?.putString("local_uri_$videoId", uri)?.apply()
                        diag("local:download: DB 복원 성공 → $uri")
                        runOnUiThread {
                            try {
                                val mi = MediaItem.Builder()
                                    .setUri(uri)
                                    .setMediaId(videoId)
                                    .build()
                                mediaController?.setMediaItem(mi)
                                mediaController?.prepare()
                                mediaController?.playWhenReady = true
                            } catch (e: Exception) {
                                diag("local:download err: ${e.message}")
                            }
                        }
                        return@launch
                    }
                }
                diag("local:download: 파일 없음 → playNextLocal")
                playNextLocal()
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
                        // ★ mediaId 설정
                        val mi = MediaItem.Builder()
                            .setUri(uri)
                            .setMediaId(videoId)
                            .build()
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
        // ★★★ local 모드인데 YouTube ID → 재생 X
        if (isLocalMode) {
            diag("loadAudio: local 모드인데 YouTube ID ($videoId) → playNextLocal")
            bgScope.launch { delay(300); playNextLocal() }
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
                val startPos = intent.getLongExtra("START_POS_MS", 0L)

                val mediaItem = MediaItem.Builder()
                    .setUri(reuseStreamUrl)
                    .setMediaId(videoId)
                    .setMediaMetadata(metadata)
                    .build()
                runOnUiThread {
                    mediaController?.setMediaItem(mediaItem, startPos)  // ★ 위치 지정
                    mediaController?.prepare()
                    mediaController?.playWhenReady = true
                    // ★★★ 즉시 재생 강제
                    mediaController?.play()
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
                }
                addToHistory(videoId, currentTitle, currentChannel, currentThumb)
                reuseStreamUrl = ""
                reuseSubtitleUrl = ""

                // ★ prepare 완료를 기다린 후 play 재확인 (안정성)
                delay(300)
                try {
                    val mc = mediaController
                    if (mc != null && !mc.isPlaying) {
                        mc.play()
                        diag("reuse: play() 재호출 (자동 재생 보장)")
                    }
                } catch (_: Exception) {}

                delay(200)
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

            // ★ extract 전 stop (clearMediaItems 제거 — 16초 지연 원인)
            runOnUiThread {
                try {
                    val mc = mediaController
                    mc?.playWhenReady = false
                    diag("extract 전 ExoPlayer playWhenReady=false")
                } catch (_: Exception) {}
            }

            diag("⏩ extractAudioOnly 진입: $videoId (cacheSize=${prefetchedStreams.size})")
            extractInProgress = true
            val prefetched = prefetchedStreams.remove(videoId)
            val result = if (prefetched != null) {
                diag("prefetch hit: $videoId")
                prefetched
            } else {
                try {
                    val r = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        YouTubeStream.extractAudioOnly(videoId)
                    }
                    if (r == null) {
                        diag("❌ extract 25초 타임아웃: $videoId")
                        runOnUiThread {
                            Toast.makeText(this@AudioPlayerActivity,
                                "스트림 추출 타임아웃 → 다음 곡", Toast.LENGTH_SHORT).show()
                        }
                        loadingNext = false
                        bgScope.launch { delay(300); trySkipToNextInQueue(videoId) }
                        return@launch
                    }
                    diag("⏪ extractAudioOnly 종료: $videoId")
                    r
                } finally {
                    extractInProgress = false
                }
            }
            extractInProgress = false
            diag("extractAudioOnly($videoId)")
            diag("  debug=${result.debug.take(800)}")
            diag("  audioUrl=${result.audioUrl?.take(80)}")
            diag("  muxedUrl=${result.muxedUrl?.take(80)}")

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

            val useUrl = url ?: fallbackUrl ?: run {
                diag("❌ useUrl 없음 - url=$url fallbackUrl=$fallbackUrl")
                runOnUiThread {
                    Toast.makeText(this@AudioPlayerActivity, "스트림 URL 없음", Toast.LENGTH_SHORT).show()
                }
                loadingNext = false
                trySkipToNextInQueue(videoId)
                return@launch
            }

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
                ?.putString("current_stream_url", url)   // ★ 재사용용
                ?.apply()

            lastMediaSetMs = System.currentTimeMillis()
            runOnUiThread {
                // ★ ExoPlayer 큐 완전히 비우기 (transition 잔재 방지)
                try {
                    val mc = mediaController
                    if (mc != null) {
                        // ★ clear/stop 제거 (setMediaItems가 자동 교체, 16초 지연 원인)
                        diag("ExoPlayer 상태: itemCount=${mc.mediaItemCount}")
                    }
                } catch (e: Exception) {
                    diag("clear skip err: ${e.message}")
                }
                // ★ setMediaItems(단일)로 큐 확실히 리셋
                // ★ 이어듣기 시작 위치 (클래스 필드)
                val startPos = if (pendingStartPos > 0) pendingStartPos else 0L
                mediaController?.setMediaItems(listOf(mediaItem), 0, startPos)
                if (startPos > 0) {
                    diag("loadAudio: 시작 위치 ${startPos / 1000}초")
                    pendingStartPos = 0L   // ★ 사용 후 리셋
                }
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

            // ★★★ extract 완료 직후 prefetch (prefetchNext 하나로 통합)
            attachEqualizer()
            loadingNext = false
            bgScope.launch {
                delay(2000)  // 2초 후 (ExoPlayer 준비 완료 시점)
                try { prefetchNext() } catch (_: Exception) {}
            }
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
                diag("state=$stateName (title prefix 제거)")
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

                        // ★ pending resume 처리 (duration=0 회피)
                        if (pendingResumeVideoId == currentVideoId && pendingResumePos > 3000L) {
                            val mc2 = mediaController
                            val realDur = mc2?.duration ?: 0L
                            val canResume = realDur > 0 && pendingResumePos < realDur - 5000L
                            diag("STATE_READY pendingResume: pos=${pendingResumePos/1000}초 realDur=${realDur/1000}초 canResume=$canResume")
                            if (canResume) {
                                runOnUiThread {
                                    androidx.appcompat.app.AlertDialog.Builder(this@AudioPlayerActivity)
                                        .setTitle("🎵 이어 듣기")
                                        .setMessage("${formatTime(pendingResumePos)}부터 이어 들을까요?")
                                        .setPositiveButton("이어 듣기") { _, _ ->
                                            mediaController?.seekTo(pendingResumePos)
                                            mediaController?.play()
                                        }
                                        .setNegativeButton("처음부터") { _, _ ->
                                            mediaController?.seekTo(0)
                                        }
                                        .setCancelable(false)
                                        .show()
                                }
                            }
                            pendingResumePos = 0L
                            pendingResumeVideoId = ""
                        }
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

                // ★★★ STATE_ENDED는 Service가 처리 (Activity 중복 방지)
                // Activity가 살아있으면 UI만 갱신, 다음 곡 로드는 Service 담당
                if (playbackState == Player.STATE_ENDED) {
                    diag("STATE_ENDED: Service가 다음 곡 처리 (Activity 스킵)")
                    // Activity의 playNextRelatedBg 호출 제거
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

                lastMediaSetMs = System.currentTimeMillis()
                loadingNext = false

                // ★★★ 메타데이터 즉시 추출 (UI 지연 방지)
                val newTitle = mediaItem.mediaMetadata.title?.toString() ?: currentTitle
                val newChannel = mediaItem.mediaMetadata.artist?.toString() ?: currentChannel
                val newThumb = mediaItem.mediaMetadata.artworkUri?.toString() ?: currentThumb

                currentVideoId = newId
                currentTitle = newTitle
                currentChannel = newChannel
                currentThumb = newThumb
                currentArtist = ""
                currentSubtitleUrl = pref?.getString("current_subtitle_url", "") ?: ""

                QueueManager.setCurrent(this@AudioPlayerActivity, newId)

                // ★★★ prefs 즉시 저장 (모든 곡 — 로컬/YouTube)
                pref?.edit()
                    ?.putString("current_video_id", newId)
                    ?.putString("current_title", newTitle)
                    ?.putString("current_channel", newChannel)
                    ?.putString("current_thumbnail", newThumb)
                    ?.apply()

                // ★★★ UI 즉시 갱신 (runOnUiThread로 바로 반영)
                runOnUiThread {
                    try {
                        tvTitle.text = newTitle
                        tvChannel.text = newChannel
                        if (newThumb.isNotBlank()) {
                            com.bumptech.glide.Glide.with(this@AudioPlayerActivity)
                                .load(newThumb).into(ivArt)
                            com.bumptech.glide.Glide.with(this@AudioPlayerActivity)
                                .load(newThumb).into(ivBackground)
                        }
                        updateLyricsButtonLabel()
                        updateNextSongs()   // ★ 다음 곡 리스트도 즉시 갱신
                        diag("transition UI 즉시 갱신: $newTitle")
                    } catch (e: Exception) {
                        diag("transition UI err: ${e.message}")
                    }
                }

                // ★ 새 곡 artist 파싱 재시작
                extractArtistAsync()

                // ★ prefetch 재예약
                try { prefetchNext() } catch (_: Exception) {}
            }
        })
    }

    /** ★ 로컬 파일 다음 곡 (YouTube 검색 없이) */
    private fun playNextLocal() {
        bgScope.launch {
            try {
                loadingNext = true

                // 1. QueueManager에서 다음 local 항목
                val queue = QueueManager.get(this@AudioPlayerActivity)
                val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
                if (curIdx >= 0 && curIdx < queue.size - 1) {
                    for (i in (curIdx + 1) until queue.size) {
                        val item = queue[i]
                        if (item.videoId.startsWith("local:")) {
                            diag("playNextLocal: 큐 다음 → ${item.videoId}")
                            QueueManager.setCurrent(this@AudioPlayerActivity, item.videoId)
                            currentVideoId = item.videoId
                            currentTitle = item.title
                            currentChannel = item.channel
                            currentThumb = item.thumbnail
                            runOnUiThread { updateUI() }
                            loadAudio(item.videoId, isInitial = false)
                            return@launch
                        }
                    }
                }

                // 2. local:download → 다음 다운로드
                if (currentVideoId.startsWith("local:download:")) {
                    val curYtId = currentVideoId.removePrefix("local:download:")
                    val dao = HistoryDatabase.get(this@AudioPlayerActivity).downloadDao()
                    val downloads = withContext(Dispatchers.IO) { dao.getAllOnce() }
                    val dIdx = downloads.indexOfFirst { it.videoId == curYtId }
                    if (dIdx >= 0 && dIdx < downloads.size - 1) {
                        val next = downloads[dIdx + 1]
                        val nextId = "local:download:${next.videoId}"
                        val file = java.io.File(next.filePath)
                        if (file.exists()) {
                            diag("playNextLocal: 다음 다운로드 → $nextId")
                            currentVideoId = nextId
                            currentTitle = next.title
                            currentChannel = next.channel
                            currentThumb = next.thumbnail
                            // 새 Activity 인스턴스로 재시작 (FILE_URI 필요)
                            runOnUiThread {
                                startActivity(Intent(this@AudioPlayerActivity, AudioPlayerActivity::class.java).apply {
                                    putExtra("VIDEO_ID", nextId)
                                    putExtra("FILE_URI", android.net.Uri.fromFile(file).toString())
                                    putExtra("VIDEO_TITLE", next.title)
                                    putExtra("VIDEO_CHANNEL", next.channel)
                                    putExtra("FROM_PLAYLIST", true)
                                    putExtra("LOCAL_MODE", true)
                                })
                                finish()
                            }
                            return@launch
                        }
                    }
                }

                // 3. MediaStore 다음 곡
                val localId = currentVideoId
                    .removePrefix("local:download:")
                    .removePrefix("local:")
                    .toLongOrNull()
                if (localId != null) {
                    val all = LocalMediaScanner.scan(this@AudioPlayerActivity)
                    val idx = all.indexOfFirst { it.id == localId }
                    if (idx >= 0 && idx < all.size - 1) {
                        val next = all[idx + 1]
                        val nextId = "local:${next.id}"
                        QueueManager.addLocal(this@AudioPlayerActivity, next)
                        diag("playNextLocal: 스캔 다음 → $nextId (${next.title})")
                        currentVideoId = nextId
                        currentTitle = next.title
                        currentChannel = next.artist
                        currentThumb = ""
                        runOnUiThread { updateUI() }
                        loadAudio(nextId, isInitial = false)
                        return@launch
                    }
                }

                diag("playNextLocal: 다음 로컬 곡 없음")
                loadingNext = false
            } catch (e: Exception) {
                diag("playNextLocal err: ${e.message}")
                loadingNext = false
            }
        }
    }

    private fun playNextRelatedBg() {
        // ★★★ local 모드 → playNextLocal only (YouTube 폴백 완전 차단)
        if (isLocalMode) {
            diag("playNext: local 모드 → playNextLocal (YouTube X)")
            playNextLocal()
            return
        }
        if (currentVideoId.startsWith("local:")) {
            diag("playNext: local 파일 → playNextLocal")
            playNextLocal()
            return
        }

        // ★ service_stopped면 다음곡 로딩 금지
        val serviceStopped = pref?.getBoolean("service_stopped", false) ?: false
        if (serviceStopped) {
            diag("playNext: service_stopped=true → skip")
            loadingNext = false
            return
        }

        // ★ pause 상태면 다음곡 로딩 금지
        val mcCheck0 = mediaController
        if (mcCheck0 != null &&
            !mcCheck0.isPlaying &&
            !mcCheck0.playWhenReady &&
            mcCheck0.mediaItemCount == 0) {
            diag("playNext: paused & no items → skip")
            loadingNext = false
            return
        }

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
        if (currentVideoId.isBlank()) {
            Toast.makeText(this, "곡 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        // ★★★ artist 즉시 파싱 (currentArtist 빈값 대응)
        val artist = if (currentArtist.isNotBlank()) {
            currentArtist
        } else {
            // title에서 "artist - title" 파싱
            val parsed = parseArtistFromTitle(currentTitle)
            if (parsed.isNotBlank()) {
                currentArtist = parsed   // 다음에도 재사용
                parsed
            } else {
                currentChannel
            }
        }
        diag("openLyrics: $currentVideoId, artist=$artist, subtitle=${currentSubtitleUrl.take(40)}")
        startActivity(Intent(this, LyricsActivity::class.java).apply {
            putExtra("VIDEO_ID", currentVideoId)
            putExtra("VIDEO_TITLE", currentTitle)
            putExtra("VIDEO_CHANNEL", currentChannel)
            putExtra("VIDEO_ARTIST", artist)
            putExtra("SUBTITLE_URL", currentSubtitleUrl)
        })
    }

    /** ★ title에서 artist 파싱 ("artist - title" 패턴) */
    private fun parseArtistFromTitle(title: String): String {
        if (title.isBlank()) return ""
        var t = title.trim()
        t = t.replace(Regex("^\\[.*?\\]\\s*"), "")
        t = t.replace(Regex("^\\(.*?\\)\\s*"), "")
        val dash = t.split(Regex("\\s*[-–—]\\s*"))
        if (dash.size >= 2) {
            val artist = dash[0].trim()
                .split(Regex("(?i)\\s+(feat\\.?|ft\\.?|with)\\s+"))[0]
                .trim()
            if (artist.length in 2..40) return artist
        }
        return ""
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

        // ★ 구독 알림에서 진입 시 자동 재생
        val fromSubNotif = intent.getBooleanExtra("FROM_SUB_NOTIFICATION", false)
        if (fromSubNotif) {
            diag("onNewIntent: 구독 알림에서 진입 → 재생")
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                nm.cancel(7000)
                for (id in 7001..7010) nm.cancel(id)
            } catch (_: Exception) {}

            // ★ 옵션 2: QUEUE_JSON 처리
            val queueJson = intent.getStringExtra("QUEUE_JSON") ?: ""
            if (queueJson.isNotBlank() && queueJson != "[]") {
                try {
                    val arr = org.json.JSONArray(queueJson)
                    QueueManager.clear(this)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        QueueManager.add(this,
                            HomeVideo(
                                videoId = o.optString("videoId", ""),
                                title = o.optString("title", ""),
                                channel = o.optString("channel", ""),
                                thumbnail = o.optString("thumbnail", "")
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        // 새 곡 정보
        val newVideoId = intent.getStringExtra("VIDEO_ID") ?: return
        val newTitle = intent.getStringExtra("VIDEO_TITLE") ?: ""
        val newChannel = intent.getStringExtra("VIDEO_CHANNEL") ?: ""
        val newThumb = intent.getStringExtra("VIDEO_THUMB") ?: ""
        val newLocalMode = intent.getBooleanExtra("LOCAL_MODE", false)
        diag("onNewIntent: newVideoId=$newVideoId localMode=$newLocalMode")

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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (audioSwipeDetector == null) {
            audioSwipeDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    vx: Float,
                    vy: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dy = e2.y - e1.y
                    val dx = e2.x - e1.x
                    val absDy = Math.abs(dy)
                    val absDx = Math.abs(dx)
                    val absVy = Math.abs(vy)
                    if (absDy > 80 && absDy > absDx * 1.5f && absVy > 200f) {
                        diag("audioSwipe: dy=$dy absVy=$absVy")
                        try { showPlaylistSheet() } catch (e: Exception) { diag("swipe err: ${e.message}") }
                        return true
                    }
                    return false
                }
            })
        }
        try { audioSwipeDetector?.onTouchEvent(ev) } catch (_: Exception) {}
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        audioLiveActive = false
        updateAudioLiveSubButton()

        // ★ 서비스 정지 상태 감지 → 플래그 해제
        if (pref?.getBoolean("service_stopped", false) == true) {
            diag("onResume: service_stopped=true → 해제")
            pref?.edit()?.putBoolean("service_stopped", false)?.apply()
        }

        diag("onResume: 재생 상태 유지 (video 트랙 건드리지 않음)")
        // ★ 자동 저장 시작 (매 10초)
        startPositionAutoSave()
    }

    override fun onStop() {
        super.onStop()

        // ★ 명시적 종료(알림 스와이프/종료 버튼)일 때만 pause
        val serviceStopped = pref?.getBoolean("service_stopped", false) ?: false
        if (serviceStopped) {
            diag("onStop: service_stopped=true → pause (명시적 종료)")
            try { mediaController?.pause() } catch (_: Exception) {}
            return
        }

        // ★ 홈버튼/다른 앱 이동 → 재생 유지
        diag("onStop: 홈버튼/백그라운드 → 재생 유지")
        try { acquireWakeLock() } catch (_: Exception) {}

        try {
            val mc = mediaController
            if (mc != null && (mc.isPlaying || mc.playWhenReady)) {
                mc.playWhenReady = true
                diag("onStop: playWhenReady=true 강제 유지")
            }
        } catch (_: Exception) {}
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
                    "⭐ 내 플레이리스트에 저장",     // ★ NEW
                    "🔖 북마크 (★ 표시)",           // ★ NEW
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
                        0 -> saveCurrentToPlaylist()      // ★ NEW
                        1 -> bookmarkCurrent()             // ★ NEW
                        2 -> toggleKeepScreenOn()
                        3 -> toggleBackgroundPlayback()
                        4 -> openBatterySettings()
                        5 -> showEqDialog()
                        6 -> showLoudnessDialog()
                        7 -> dislikeCurrent()
                        8 -> {
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
                        9 -> showSpeedDialog()
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
                // ★ 프리페치 트리거: 캐시가 2곡 미만일 때만 (중복 방지)
                val pos = sb?.progress ?: 0
                if (pos >= 500 && prefetchedStreams.size < 2 && prefetchJob?.isActive != true) {
                    diag("seekbar 50%+ & 캐시 부족(${prefetchedStreams.size}) → prefetch 시작")
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
                    // ★★★ 현재 재생 곡 확인 (mediaController vs currentVideoId 동기화)
                    val mcId = mc.currentMediaItem?.mediaId
                    if (mcId != null && mcId.isNotBlank() && mcId != currentVideoId
                        && !mcId.endsWith("_video")) {
                        // UI와 실제 재생 곡 불일치 → 즉시 갱신
                        runOnUiThread {
                            try {
                                currentVideoId = mcId
                                val meta = mc.currentMediaItem?.mediaMetadata
                                if (meta != null) {
                                    currentTitle = meta.title?.toString() ?: currentTitle
                                    currentChannel = meta.artist?.toString() ?: currentChannel
                                    currentThumb = meta.artworkUri?.toString() ?: currentThumb
                                }
                                tvTitle.text = currentTitle
                                tvChannel.text = currentChannel
                                if (currentThumb.isNotBlank()) {
                                    com.bumptech.glide.Glide.with(this@AudioPlayerActivity)
                                        .load(currentThumb).into(ivArt)
                                }
                                diag("polling 동기화: mcId=$mcId title=${currentTitle.take(30)}")
                            } catch (_: Exception) {}
                        }
                    }

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
                delay(300)  // ★ 500ms → 300ms
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

    /** ★ 이어듣기 다이얼로그 */
    private fun checkResumeAndPlayAudio(videoId: String) {
        if (mediaController?.currentMediaItem?.mediaId == videoId &&
            (mediaController?.isPlaying == true || mediaController?.playWhenReady == true)) {
            diag("checkResume: 이미 재생 중 → 스킵")
            loadAudio(videoId, isInitial = true)
            return
        }

        // ★ duration=0 문제 회피: 저장된 duration이 0이면 일단 loadAudio 후 STATE_READY에서 resume 판정
        bgScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    HistoryDatabase.get(applicationContext).historyDao().getById(videoId)
                }
            } catch (e: Exception) { null }

            val savedPos = saved?.positionMs ?: 0L
            val savedDur = saved?.durationMs ?: 0L

            // ★ 저장된 duration이 0이면 resume 판정 불가 → pendingResumePos에 저장하고 loadAudio
            if (savedDur == 0L && savedPos > 3000L) {
                diag("checkResume: savedDur=0 → STATE_READY 대기 (pendingResume=${savedPos/1000}초)")
                pendingResumePos = savedPos
                pendingResumeVideoId = videoId
                runOnUiThread { loadAudio(videoId, isInitial = true) }
                return@launch
            }

            val canResume = savedPos > 3000L && savedDur > 0 && savedDur - savedPos > 5000L
            diag("checkResume: $videoId pos=${savedPos / 1000}초 dur=${savedDur / 1000}초 canResume=$canResume")

            if (canResume) {
                runOnUiThread {
                    androidx.appcompat.app.AlertDialog.Builder(this@AudioPlayerActivity)
                        .setTitle("🎵 이어 듣기")
                        .setMessage("${formatTime(savedPos)}부터 이어 들을까요?")
                        .setPositiveButton("이어 듣기") { _, _ ->
                            loadAudioWithStartPos(videoId, savedPos)
                        }
                        .setNegativeButton("처음부터") { _, _ ->
                            loadAudioWithStartPos(videoId, 0L)
                        }
                        .setCancelable(false)
                        .show()
                }
            } else {
                runOnUiThread { loadAudio(videoId, isInitial = true) }
            }
        }
    }

    // ★ pending resume state
    private var pendingResumePos: Long = 0L
    private var pendingResumeVideoId: String = ""

    /** ★ 시작 위치를 지정해서 loadAudio */
    private fun loadAudioWithStartPos(videoId: String, startPosMs: Long) {
        // ★ 클래스 필드에 저장 (스코프 무관)
        pendingStartPos = startPosMs
        diag("loadAudioWithStartPos: $videoId, pos=${startPosMs / 1000}초")
        // intent에도 저장
        intent.putExtra("START_POS_MS", startPosMs)
        intent.putExtra("FORCE_START_POS", true)
        loadAudio(videoId, isInitial = true)
    }

    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    /** ★ 다음 곡 리스트 매 2초마다 갱신 */
    private fun startNextSongsUpdate() {
        bgScope.launch {
            while (isActive) {
                try {
                    updateNextSongs()
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }

    /** ★ 다음 곡 리스트 갱신 */
    private fun updateNextSongs() {
        try {
            val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNextSongs) ?: return
            val adapter = rv.adapter as? NextSongsAdapter ?: return
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            val result = mutableListOf<HomeVideo>()
            // ★ 오직 QueueManager만 참조 (뒤죽박죽 방지)
            if (curIdx >= 0 && curIdx < queue.size - 1) {
                queue.drop(curIdx + 1).take(10).forEach { result.add(it) }
            } else if (curIdx < 0 && queue.isNotEmpty()) {
                // ★ 현재 곡이 큐에 없을 때: 큐 마지막 10곡 표시 (임시)
                diag("updateNextSongs: curIdx=-1 (current=$currentVideoId, queue=${queue.size})")
                // QueueManager.setCurrent로 current 곡이 추가될 때까지 임시 표시
                queue.takeLast(10).forEach { result.add(it) }
            }
            val current = adapter.currentItems()
            val changed = current.size != result.size ||
                current.zip(result).any { (a, b) -> a.videoId != b.videoId }
            if (changed) {
                diag("updateNextSongs: submit ${result.size}곡 (curIdx=$curIdx/${queue.size}, npl=${nextPreviewList.size})")
                runOnUiThread { adapter.submit(result) }
            }
        } catch (e: Exception) {
            diag("updateNextSongs err: ${e.message}")
        }
    }

    /** ★ 다음 곡 어댑터 */
    inner class NextSongsAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<NextSongsAdapter.VH>() {
        private val items = mutableListOf<HomeVideo>()

        fun submit(list: List<HomeVideo>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun currentItems(): List<HomeVideo> = items.toList()

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_next_song, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.channel.text = item.channel
            if (item.thumbnail.isNotBlank()) {
                com.bumptech.glide.Glide.with(holder.thumb)
                    .load(item.thumbnail)
                    .into(holder.thumb)
            }
            // ★ 탭하면 해당 곡으로 이동
            holder.itemView.setOnClickListener {
                try {
                    val queue = QueueManager.get(this@AudioPlayerActivity)
                    val idx = queue.indexOfFirst { it.videoId == item.videoId }
                    if (idx >= 0) {
                        // 큐에서 그 곡부터 재생
                        QueueManager.moveToTop(this@AudioPlayerActivity, item.videoId)
                        currentVideoId = item.videoId
                        currentTitle = item.title
                        currentChannel = item.channel
                        currentThumb = item.thumbnail
                        updateUI()
                        loadAudio(item.videoId, isInitial = false)
                    }
                } catch (_: Exception) {}
            }
        }

        override fun getItemCount() = items.size

        inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val thumb: android.widget.ImageView = v.findViewById(R.id.thumb)
            val title: android.widget.TextView = v.findViewById(R.id.title)
            val channel: android.widget.TextView = v.findViewById(R.id.channel)
        }
    }

    /** ★ 스와이프/재생목록에서 곡 선택 시 이동 */
    fun jumpToSong(video: HomeVideo) {
        if (video.videoId == currentVideoId) {
            diag("jumpToSong: 같은 곡 → skip")
            return
        }
        diag("jumpToSong: ${video.videoId} (${video.title.take(30)})")

        // ★★★ 즉시 상태 업데이트
        currentVideoId = video.videoId
        currentTitle = video.title
        currentChannel = video.channel
        currentThumb = video.thumbnail
        currentArtist = ""

        // ★★★ prefs 즉시 저장
        pref?.edit()
            ?.putString("current_video_id", video.videoId)
            ?.putString("current_title", video.title)
            ?.putString("current_channel", video.channel)
            ?.putString("current_thumbnail", video.thumbnail)
            ?.apply()

        // ★★★ UI 즉시 갱신 (loadAudio의 8초 extract 대기 없이)
        runOnUiThread {
            try {
                tvTitle.text = video.title
                tvChannel.text = video.channel
                if (video.thumbnail.isNotBlank()) {
                    com.bumptech.glide.Glide.with(this@AudioPlayerActivity)
                        .load(video.thumbnail).into(ivArt)
                    com.bumptech.glide.Glide.with(this@AudioPlayerActivity)
                        .load(video.thumbnail).into(ivBackground)
                }
                updateLyricsButtonLabel()
                updateNextSongs()
                diag("jumpToSong UI 즉시 갱신: ${video.title.take(30)}")
            } catch (e: Exception) {
                diag("jumpToSong UI err: ${e.message}")
            }
        }

        loadAudio(video.videoId, isInitial = false)
    }

    /** ★ 재생목록 BottomSheet 표시 */
    private fun showPlaylistSheet() {
        try {
            diag("showPlaylistSheet: 재생목록 표시")
            val items = mutableListOf<HomeVideo>()

            // ★★★ 전체 큐 순서 그대로 표시 (현재 곡 강조는 BottomSheet에서)
            val queue = QueueManager.get(this)
            val curIdx = queue.indexOfFirst { it.videoId == currentVideoId }
            diag("showPlaylistSheet: curIdx=$curIdx/${queue.size} current=$currentVideoId")

            if (curIdx >= 0) {
                // 현재 곡을 큐에서 찾음 → 큐 순서 그대로 (current 강조)
                queue.forEach { items.add(it) }
            } else {
                // 현재 곡이 큐에 없음 → 현재 먼저 + 큐 뒤
                if (currentVideoId.isNotBlank()) {
                    items.add(HomeVideo(currentVideoId, currentTitle, currentChannel, currentThumb))
                }
                queue.forEach { items.add(it) }
            }

            // nextPreviewList 보충 (큐에 없는 곡만)
            synchronized(nextPreviewList) {
                nextPreviewList
                    .filter { v -> items.none { it.videoId == v.videoId } }
                    .forEach { items.add(it) }
            }

            diag("showPlaylistSheet: 총 ${items.size}곡")
            PlaylistBottomSheet.newInstance(currentVideoId, currentTitle, items)
                .show(supportFragmentManager, "playlist_sheet")
        } catch (e: Exception) {
            diag("showPlaylistSheet err: ${e.message}")
        }
    }


    private fun setupSwipeGestures() {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                val absDy = Math.abs(dy)
                val absDx = Math.abs(dx)
                if (absDy > 100 && absDy > absDx * 1.5f) {
                    if (dy < 0) {
                        diag("Swipe UP → 재생목록")
                    } else {
                        diag("Swipe DOWN → 재생목록")
                    }
                    showPlaylistSheet()
                    return true
                }
                return false
            }
        })

        // 앨범 아트에 스와이프
        try {
            ivArt.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
        } catch (_: Exception) {}

        // "다음 곡" 영역에도 스와이프
        try {
            findViewById<android.view.View>(R.id.rvNextSongs)?.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
        } catch (_: Exception) {}
    }

    /** ★ 현재 재생 중인 곡을 내 플레이리스트에 저장 */
    private fun saveCurrentToPlaylist() {
        if (currentVideoId.isBlank() || currentVideoId.startsWith("local:")) {
            Toast.makeText(this, "로컬 파일은 저장할 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        diag("saveCurrentToPlaylist: $currentVideoId")

        val video = VideoItem(
            videoId = currentVideoId,
            title = currentTitle.ifBlank { currentVideoId },
            channel = currentChannel.ifBlank { "YouTube" },
            thumbnail = currentThumb,
            duration = "",
            viewCount = "",
            uploadDate = ""
        )

        // ★ PlaylistPickerHelper 재사용
        PlaylistPickerHelper.showSaveDialog(this, lifecycleScope, listOf(video))
    }

    /** ★ 현재 재생 중인 곡을 북마크에 저장 (별도 기능) */
    private fun bookmarkCurrent() {
        if (currentVideoId.isBlank() || currentVideoId.startsWith("local:")) {
            Toast.makeText(this, "북마크 불가", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val dao = HistoryDatabase.get(applicationContext).bookmarkDao()
                val exists = dao.isBookmarked(currentVideoId)
                if (exists) {
                    dao.delete(currentVideoId)
                    Toast.makeText(this@AudioPlayerActivity, "북마크 해제", Toast.LENGTH_SHORT).show()
                } else {
                    dao.insert(
                        BookmarkEntity(
                            videoId = currentVideoId,
                            title = currentTitle,
                            channel = currentChannel,
                            thumbnail = currentThumb,
                            savedAt = System.currentTimeMillis()
                        )
                    )
                    Toast.makeText(this@AudioPlayerActivity, "⭐ 북마크 저장", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AudioPlayerActivity, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun savePosition() {
        val mc = mediaController ?: return
        if (currentVideoId.isBlank()) return
        // ★ 로컬 파일은 스킵
        if (currentVideoId.startsWith("local:")) return

        val pos = mc.currentPosition
        val dur = mc.duration
        if (pos <= 3000) return   // ★ 3초 이상만 저장
        val durationMs = if (dur > 0) dur else 0L
        val appCtx = applicationContext
        val vid = currentVideoId
        val title = currentTitle
        val channel = currentChannel
        val thumb = currentThumb

        bgScope.launch {
            try {
                val dao = HistoryDatabase.get(appCtx).historyDao()
                val existing = dao.getById(vid)
                if (existing == null) {
                    // ★ 없으면 insert (히스토리에 없으면 새로 생성)
                    dao.insert(
                        HistoryEntity(
                            videoId = vid, title = title, channel = channel,
                            thumbnail = thumb, watchedAt = System.currentTimeMillis(),
                            positionMs = pos, durationMs = durationMs
                        )
                    )
                } else {
                    // ★ 있으면 위치만 갱신
                    dao.updatePosition(vid, pos, durationMs)
                }
                diag("위치 저장: ${pos / 1000}초 / ${durationMs / 1000}초")
            } catch (e: Exception) {
                diag("위치 저장 err: ${e.message}")
            }
        }
    }

    /** ★ 매 10초 자동 저장 */
    private fun startPositionAutoSave() {
        bgScope.launch {
            while (isActive) {
                delay(10_000)   // 10초
                try {
                    val mc = mediaController ?: continue
                    val pos = mc.currentPosition
                    val dur = mc.duration
                    if (pos > 3000 && currentVideoId.isNotBlank() &&
                        !currentVideoId.startsWith("local:")) {
                        val durationMs = if (dur > 0) dur else 0L
                        val vid = currentVideoId
                        val title = currentTitle
                        val channel = currentChannel
                        val thumb = currentThumb
                        withContext(Dispatchers.IO) {
                            try {
                                val dao = HistoryDatabase.get(applicationContext).historyDao()
                                val existing = dao.getById(vid)
                                if (existing == null) {
                                    dao.insert(
                                        HistoryEntity(
                                            videoId = vid, title = title, channel = channel,
                                            thumbnail = thumb, watchedAt = System.currentTimeMillis(),
                                            positionMs = pos, durationMs = durationMs
                                        )
                                    )
                                } else {
                                    dao.updatePosition(vid, pos, durationMs)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
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
        // ★★★ 서비스로 다음 곡 관리 복귀
        PlaybackService.autoNextDisabled = false
        PlaybackService.nextTrackHandler = null
        stopAnimation()
        updateJob?.cancel()
        bgScope.cancel()
        try { LoudnessManager.release() } catch (_: Exception) {}

        try {
            val bgEnabled = pref?.getBoolean("keep_bg_playback", true) ?: true
            val serviceStopped = pref?.getBoolean("service_stopped", false) ?: false
            val isPlaying = mediaController?.isPlaying == true
            val playWhenReady = mediaController?.playWhenReady == true

            // ★ 서비스 정지 상태면 MediaController도 release
            if (serviceStopped) {
                diag("onDestroy: service_stopped=true → MediaController release")
                releaseWakeLock()
                MediaController.releaseFuture(controllerFuture)
            } else if (bgEnabled) {
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
