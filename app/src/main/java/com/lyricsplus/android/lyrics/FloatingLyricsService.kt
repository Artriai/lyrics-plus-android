package com.lyricsplus.android.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lyricsplus.android.AppLyricsStateStore
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.MainActivity
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.PlaybackAnchor
import com.lyricsplus.android.spotify.LyricsNotificationListenerService
import com.lyricsplus.android.spotify.SpotifyMediaSessionSelector
import com.lyricsplus.android.spotify.SpotifyMediaSessionReader
import com.lyricsplus.android.spotify.SpotifyMediaSnapshot
import com.lyricsplus.android.ui.FloatingLyricsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

class FloatingLyricsService : Service() {
    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var lifecycleOwner: MyLifecycleOwner? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private lateinit var lyricsProvider: LyricsProvider

    // Thread-safe Compose states collected by the overlay view tree
    var currentTrack by mutableStateOf<NowPlaying?>(null)
    var currentLyrics by mutableStateOf<List<LyricsLine>>(emptyList())
    var currentPlayback by mutableStateOf<PlaybackAnchor?>(null)
    var lyricsOffsetMs by mutableStateOf(0L)
    var readingMode by mutableStateOf(1)
    var displayMode by mutableStateOf(0) // 0=Original, 1=Translation, 2=Romaji
    var isLocked by mutableStateOf(false)
    var textSizeSp by mutableStateOf(15f)
    var backgroundOpacity by mutableStateOf(0.7f)
    var textColorHex by mutableStateOf("#FFFFFF")
    var estimatedPositionMs by mutableLongStateOf(0L)
    private var visualOffsetMs = 0L
    private var resumeTimeMs = 0L

    private var tickJob: kotlinx.coroutines.Job? = null
    private var positionSaveJob: kotlinx.coroutines.Job? = null
    private var trackRequestKey: String? = null
    private var mediaSyncInFlight = false
    private var lastMediaSyncElapsed = 0L
    private var hasAppState = false
    private val mediaSessionReader by lazy { SpotifyMediaSessionReader(this) }

    override fun onCreate() {
        super.onCreate()
        lyricsProvider = LyricsProvider.getInstance(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备悬浮歌词..."))

        // Load persisted settings
        val prefs = getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
        readingMode = prefs.getInt("reading_mode", 1)
        displayMode = prefs.getInt("floating_display_mode", 0)
        textSizeSp = prefs.getFloat("floating_text_size", 15f)
        backgroundOpacity = prefs.getFloat("floating_bg_opacity", 0.7f)
        textColorHex = prefs.getString("floating_text_color", "#FFFFFF") ?: "#FFFFFF"

        initOverlayWindow()
        observePlaybackUpdates()
        startTickLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_UNLOCK) {
            isLocked = false
            updateNotification("悬浮歌词已解锁，点击可移动")
        } else if (action == ACTION_REFRESH_LYRICS) {
            currentTrack?.let { fetchLyricsInBackground(it) }
        } else if (action == ACTION_UPDATE_OFFSET) {
            lyricsOffsetMs = intent.getLongExtra(EXTRA_OFFSET, 0L)
        } else {
            if (intent != null && intent.hasExtra(EXTRA_OFFSET)) {
                lyricsOffsetMs = intent.getLongExtra(EXTRA_OFFSET, 0L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        windowParams?.let { saveWindowPositionNow(it.x, it.y) }
        serviceScope.cancel()
        removeOverlayWindow()
        
        // Update persistent setting preference to false upon close
        val prefs = getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_floating_lyrics", false).apply()
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initOverlayWindow() {
        val context = this
        val view = ComposeView(context).apply {
            setContent {
                FloatingLyricsView(
                    service = this@FloatingLyricsService,
                    onClose = { stopSelf() },
                    onDrag = { dx, dy -> updateWindowPosition(dx, dy) }
                )
            }
        }

        val lifecycleOwner = MyLifecycleOwner()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
            val defaultY = (resources.displayMetrics.heightPixels * 0.25f).toInt()
            x = prefs.getInt(PREF_FLOATING_WINDOW_X, 0)
            y = prefs.getInt(PREF_FLOATING_WINDOW_Y, defaultY)
            preferredRefreshRate = 120f
        }

        clampWindowPosition(params)
        windowManager.addView(view, params)
        this.composeView = view
        this.lifecycleOwner = lifecycleOwner
        this.windowParams = params
    }

    private fun updateWindowPosition(dx: Float, dy: Float) {
        val view = composeView ?: return
        val params = windowParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        clampWindowPosition(params)

        windowManager.updateViewLayout(view, params)
        scheduleWindowPositionSave(params.x, params.y)
    }

    private fun clampWindowPosition(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, metrics.widthPixels)
        params.y = params.y.coerceIn(0, (metrics.heightPixels - 150).coerceAtLeast(0))
    }

    private fun scheduleWindowPositionSave(x: Int, y: Int) {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            delay(250)
            saveWindowPositionNow(x, y)
        }
    }

    private fun saveWindowPositionNow(x: Int, y: Int) {
        getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_FLOATING_WINDOW_X, x)
            .putInt(PREF_FLOATING_WINDOW_Y, y)
            .apply()
    }

    fun updateWindowTouchability(touchable: Boolean) {
        val view = composeView ?: return
        val params = windowParams ?: return
        if (touchable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(view, params)
    }

    private fun removeOverlayWindow() {
        composeView?.let {
            windowManager.removeView(it)
            composeView = null
        }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    private fun observePlaybackUpdates() {
        serviceScope.launch {
            AppLyricsStateStore.state.collect { state ->
                if (state != null) {
                    hasAppState = true
                    applyAppState(state)
                }
            }
        }
        serviceScope.launch {
            LyricsNotificationListenerService.snapshotFlow.collect { snapshot ->
                if (!hasAppState) {
                    snapshot?.let(::applySnapshot)
                }
            }
        }
    }

    private fun applyAppState(state: LyricsUiState) {
        if (!state.nowPlaying.hasTrack) return
        currentTrack = state.nowPlaying
        currentLyrics = state.lyrics
        currentPlayback = state.playback
        estimatedPositionMs = state.playback.currentPositionMs()
        visualOffsetMs = 0L
        resumeTimeMs = if (state.playback.isPlaying) SystemClock.elapsedRealtime() else 0L
        lyricsOffsetMs = state.lyricsOffsetMs
        trackRequestKey = state.nowPlaying.stableFloatingRequestKey()
        updateNotification("${state.nowPlaying.track} - ${state.nowPlaying.artist}")
    }

    private fun applySnapshot(snapshot: SpotifyMediaSnapshot) {
        val track = snapshot.nowPlaying
        val playback = snapshot.playback

        if (track != null && track.hasTrack) {
            val key = track.stableFloatingRequestKey()
            if (trackRequestKey != key) {
                trackRequestKey = key
                currentLyrics = emptyList()
                estimatedPositionMs = 0L
                visualOffsetMs = 0L
                resumeTimeMs = 0L
                lyricsOffsetMs = 0L
                currentTrack = track
                fetchLyricsInBackground(track)
            } else {
                currentTrack = track
            }
        }

        if (playback != null) {
            applyPlayback(playback)
        }

        track?.let {
            updateNotification("${it.track} - ${it.artist}")
        }
    }

    private fun applyPlayback(playback: PlaybackAnchor) {
        val prev = currentPlayback
        val wasPlaying = prev?.isPlaying == true
        val isNowPlaying = playback.isPlaying

        if (isNowPlaying) {
            if (!wasPlaying) {
                val frozenPos = prev?.positionMs ?: playback.positionMs
                val diff = frozenPos - playback.positionMs
                visualOffsetMs = if (Math.abs(diff) < 1500L) diff else 0L
                resumeTimeMs = SystemClock.elapsedRealtime()
            }
            currentPlayback = playback
            return
        }

        if (wasPlaying) {
            val elapsed = SystemClock.elapsedRealtime() - (prev?.capturedElapsedMs ?: SystemClock.elapsedRealtime())
            val extrapolated = (prev?.positionMs ?: 0L) + elapsed
            val diff = extrapolated - playback.positionMs
            val frozenPos = if (Math.abs(diff) < 1500L) extrapolated else playback.positionMs

            currentPlayback = PlaybackAnchor(
                isPlaying = false,
                positionMs = frozenPos,
                capturedElapsedMs = SystemClock.elapsedRealtime(),
                isAccurate = playback.isAccurate
            )
            estimatedPositionMs = frozenPos
            visualOffsetMs = 0L
        } else {
            val currentFrozen = prev?.positionMs ?: 0L
            val diff = Math.abs(currentFrozen - playback.positionMs)
            if (diff > 1500L) {
                currentPlayback = playback
                estimatedPositionMs = playback.positionMs
                visualOffsetMs = 0L
            }
        }
    }

    private fun fetchLyricsInBackground(track: NowPlaying) {
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                lyricsProvider.findSyncedLyrics(track)
            }
            result.fold(
                onSuccess = { cacheResult ->
                    currentLyrics = cacheResult.lyrics
                },
                onFailure = {
                    currentLyrics = listOf(LyricsLine(0L, "纯音乐 / 无歌词"))
                }
            )
        }
    }

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                maybeSyncCurrentMediaSnapshot()
                val pb = currentPlayback
                if (pb != null && pb.isPlaying) {
                    val elapsed = SystemClock.elapsedRealtime() - pb.capturedElapsedMs
                    val rawPosition = pb.positionMs + elapsed

                    // Decaying visual offset over 1000ms
                    val timeSinceResume = SystemClock.elapsedRealtime() - resumeTimeMs
                    if (timeSinceResume < 1000L && visualOffsetMs != 0L) {
                        val progress = timeSinceResume.toFloat() / 1000f
                        // Quadratic easing out: 1 - (1 - progress)^2
                        val easeOut = 1f - (1f - progress) * (1f - progress)
                        val currentOffset = (visualOffsetMs * (1f - easeOut)).toLong()
                        estimatedPositionMs = rawPosition + currentOffset
                    } else {
                        visualOffsetMs = 0L
                        estimatedPositionMs = rawPosition
                    }
                    // 8ms (~120Hz) for butter-smooth 120FPS sweeps on VRR screens
                    delay(8)
                } else {
                    // When paused, position doesn't change — throttle to 1Hz
                    if (pb != null) estimatedPositionMs = pb.positionMs
                    delay(1000)
                }
            }
        }
    }

    private suspend fun maybeSyncCurrentMediaSnapshot() {
        val now = SystemClock.elapsedRealtime()
        if (hasAppState) return
        if (mediaSyncInFlight || now - lastMediaSyncElapsed < MEDIA_SESSION_SYNC_INTERVAL_MS) return
        mediaSyncInFlight = true
        lastMediaSyncElapsed = now
        try {
            val snapshot = withContext(Dispatchers.IO) {
                mediaSessionReader.readSnapshot()
            }
            snapshot?.let(::applySnapshot)
        } finally {
            mediaSyncInFlight = false
        }
    }

    private fun NowPlaying.stableFloatingRequestKey(): String =
        listOf(mediaPackage, track, artist, album).joinToString("|")

    fun savePrefs() {
        val prefs = getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("floating_text_size", textSizeSp)
            putFloat("floating_bg_opacity", backgroundOpacity)
            putString("floating_text_color", textColorHex)
            putInt("floating_display_mode", displayMode)
            apply()
        }
    }

    fun togglePlayback() {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, LyricsNotificationListenerService::class.java)
        runCatching {
            val controller = manager.getActiveSessions(component)
                .let { SpotifyMediaSessionSelector.chooseController(it) }
            if (controller != null) {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            } else {
                android.widget.Toast.makeText(this, "无法控制：未找到活动的媒体会话", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun nextTrack() {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, LyricsNotificationListenerService::class.java)
        runCatching {
            val controller = manager.getActiveSessions(component)
                .let { SpotifyMediaSessionSelector.chooseController(it) }
            if (controller != null) {
                controller.transportControls.skipToNext()
            } else {
                android.widget.Toast.makeText(this, "无法控制：未找到活动的媒体会话", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮歌词服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮窗歌词在桌面平稳运行"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val unlockIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingLyricsService::class.java).apply { action = ACTION_UNLOCK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("桌面悬浮歌词正在运行")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_partial_secure, "点击解锁悬浮窗", unlockIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "floating_lyrics_channel"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_UNLOCK = "com.lyricsplus.android.action.UNLOCK"
        const val ACTION_REFRESH_LYRICS = "com.lyricsplus.android.action.REFRESH_LYRICS"
        const val ACTION_UPDATE_OFFSET = "com.lyricsplus.android.action.UPDATE_OFFSET"
        const val EXTRA_OFFSET = "com.lyricsplus.android.extra.OFFSET"
        private const val PREF_FLOATING_WINDOW_X = "floating_window_x"
        private const val PREF_FLOATING_WINDOW_Y = "floating_window_y"
        private const val MEDIA_SESSION_SYNC_INTERVAL_MS = 500L
    }
}
