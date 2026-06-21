package com.lyricsplus.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsplus.android.analytics.AnonymousStats
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.PlaybackAnchor
import com.lyricsplus.android.lyrics.LyricsProvider
import com.lyricsplus.android.spotify.SpotifyMediaSnapshot
import com.lyricsplus.android.spotify.SpotifyBroadcasts
import com.lyricsplus.android.spotify.LyricsNotificationListenerService
import com.lyricsplus.android.spotify.SpotifyMediaSessionSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

const val PREF_PREFERRED_LYRICS_SOURCE = "preferred_lyrics_source"
private const val PREF_AUTO_CHECK_UPDATES = "auto_check_updates"
private const val PAUSE_FREEZE_TOLERANCE_MS = 2500L

data class LyricsUiState(
    val nowPlaying: NowPlaying = NowPlaying(),
    val playback: PlaybackAnchor = PlaybackAnchor(),
    val lyrics: List<LyricsLine> = emptyList(),
    val isLoadingLyrics: Boolean = false,
    val message: String = "打开 Spotify 并播放歌曲",
    val lastBroadcastAction: String? = null,
    val playbackSource: String = "未连接",
    val lyricsOffsetMs: Long = 0L,
    val readingMode: Int = 1, // 0=None, 1=Romaji, 2=Furigana
    val keepScreenOn: Boolean = false,
    val activeLyricsSource: String = "未加载",
    val isInitializing: Boolean = true,
    val showFloatingLyrics: Boolean = false,
    val showSuperIslandLyrics: Boolean = false,
    val inAppFontScale: Float = 1.0f,
    val anonymousStatsEnabled: Boolean = true,
    val anonymousStatsAvailable: Boolean = false,
    val autoCheckUpdatesEnabled: Boolean = true,
    val deviceUiMode: Int = 0
)

class MainViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val lyricsProvider: LyricsProvider = LyricsProvider.getInstance(application)
    private val prefs = application.getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(LyricsUiState(
        readingMode = prefs.getInt("reading_mode", 1),
        keepScreenOn = prefs.getBoolean("keep_screen_on", false),
        showFloatingLyrics = prefs.getBoolean("show_floating_lyrics", false),
        showSuperIslandLyrics = prefs.getBoolean("show_super_island_lyrics", false),
        inAppFontScale = prefs.getFloat("in_app_font_scale", 1.0f),
        anonymousStatsEnabled = AnonymousStats.isEnabled(application),
        anonymousStatsAvailable = AnonymousStats.isAvailable(),
        autoCheckUpdatesEnabled = prefs.getBoolean(PREF_AUTO_CHECK_UPDATES, true),
        deviceUiMode = prefs.getInt("device_ui_mode", 0)
    ))
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private var lyricsRequestKey: String? = null
    private var lastAccurateUpdateMs: Long = 0
    private var preferredSource: String? = null
    private var lyricsFetchJob: kotlinx.coroutines.Job? = null
    private var activeToast: android.widget.Toast? = null
    private var currentLyricsScore: Int = 0
    private var autoUpdateCheckStarted = false

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            lyricsProvider.preWarm()
        }

        viewModelScope.launch {
            uiState.collect { state ->
                AppLyricsStateStore.update(state)
            }
        }

        viewModelScope.launch {
            com.lyricsplus.android.spotify.LyricsNotificationListenerService.snapshotFlow.collect { snapshot ->
                if (snapshot != null) {
                    onMediaSnapshot(snapshot)
                } else {
                    onMediaSessionUnavailable()
                }
            }
        }

        val context = application
        val startFloating = prefs.getBoolean("show_floating_lyrics", false)
        if (startFloating && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || 
                    android.provider.Settings.canDrawOverlays(context))) {
            startFloatingService(context)
        }
        if (prefs.getBoolean("show_super_island_lyrics", false)) {
            startSuperIslandService(context)
        }

        maybeCheckForUpdatesAutomatically()
    }

    fun onMediaSessionUnavailable() {
        _uiState.update { state ->
            if (state.playback.isPlaying) {
                state.copy(
                    playback = state.playback.copy(isPlaying = false),
                    message = "Spotify 已断开"
                )
            } else {
                state
            }
        }
    }

    fun onSpotifyIntent(intent: Intent) {
        when (intent.action) {
            SpotifyBroadcasts.METADATA_CHANGED -> handleMetadata(intent)
            SpotifyBroadcasts.PLAYBACK_STATE_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                val hasRecentAccurateData = (now - lastAccurateUpdateMs) < 2000
                val isAtInitialState = _uiState.value.playback.positionMs == 0L
                
                // Trust broadcast ONLY if we have no data yet OR accurate data is stale
                if (isAtInitialState || !hasRecentAccurateData) {
                    handlePlayback(intent)
                }
            }
            SpotifyBroadcasts.QUEUE_CHANGED -> {
                _uiState.update {
                    it.copy(
                        message = "播放队列已变化",
                        lastBroadcastAction = SpotifyBroadcasts.QUEUE_CHANGED
                    )
                }
            }
        }
    }

    fun onMediaSnapshot(snapshot: SpotifyMediaSnapshot) {
        val nextTrack = snapshot.nowPlaying
        val nextPlayback = snapshot.playback

        if (nextTrack == null && nextPlayback == null) return

        if (nextPlayback?.isAccurate == true && (snapshot.source == "active-sessions" || snapshot.source == "notification-token")) {
            lastAccurateUpdateMs = SystemClock.elapsedRealtime()
        }

        _uiState.update { state ->
            val mergedTrack = nextTrack?.withPaletteFallback(state.nowPlaying)
            val updatedPlayback = if (nextPlayback != null) {
                if (nextPlayback.isAccurate || state.playback.positionMs == 0L || !state.playback.isPlaying) {
                    nextPlayback.normalizedAgainst(state.playback)
                } else {
                    state.playback
                }
            } else {
                state.playback
            }

            val isNewTrack = mergedTrack?.hasTrack == true && mergedTrack.requestKey() != state.nowPlaying.requestKey()

            state.copy(
                nowPlaying = mergedTrack ?: state.nowPlaying,
                playback = updatedPlayback,
                lyrics = if (isNewTrack) emptyList() else state.lyrics,
                lyricsOffsetMs = if (isNewTrack) 0L else state.lyricsOffsetMs,
                isLoadingLyrics = if (isNewTrack) false else state.isLoadingLyrics,
                message = when {
                    mergedTrack?.hasTrack == true -> "媒体会话已同步：${playbackSourceLabel(snapshot.source)}"
                    nextPlayback?.isPlaying == true -> "播放中"
                    nextPlayback != null -> "已暂停"
                    else -> state.message
                },
                playbackSource = playbackSourceLabel(snapshot.source),
                isInitializing = false
            )
        }

        val mergedTrack = nextTrack?.withPaletteFallback(_uiState.value.nowPlaying)
        if (mergedTrack?.hasTrack == true && shouldFetchLyrics(mergedTrack)) {
            fetchLyrics(mergedTrack)
        }
    }

    private fun handleMetadata(intent: Intent) {
        val track = NowPlaying(
            spotifyUri = intent.getStringExtra(SpotifyBroadcasts.EXTRA_ID),
            track = intent.getStringExtra(SpotifyBroadcasts.EXTRA_TRACK).orEmpty(),
            artist = intent.getStringExtra(SpotifyBroadcasts.EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(SpotifyBroadcasts.EXTRA_ALBUM).orEmpty(),
            mediaPackage = SpotifyBroadcasts.PACKAGE_NAME,
            durationSeconds = (intent.getIntExtra(SpotifyBroadcasts.EXTRA_LENGTH, 0) + 500) / 1000
        )
        val shouldFetch = track.hasTrack && shouldFetchLyrics(track)

        _uiState.update {
            val mergedTrack = track.withPaletteFallback(it.nowPlaying)
            it.copy(
                nowPlaying = mergedTrack,
                lyrics = if (shouldFetch) emptyList() else it.lyrics,
                isLoadingLyrics = if (shouldFetch) false else it.isLoadingLyrics,
                message = when {
                    shouldFetch -> "正在搜索同步歌词"
                    track.hasTrack -> "歌曲信息已更新"
                    else -> "等待歌曲信息"
                },
                lastBroadcastAction = SpotifyBroadcasts.METADATA_CHANGED,
                isInitializing = false
            )
        }

        if (shouldFetch) {
            fetchLyrics(track)
        }
    }

    private fun handlePlayback(intent: Intent) {
        val timeSent = intent.getLongExtra("timeSent", 0L)
        val delay = if (timeSent > 0L) {
            (System.currentTimeMillis() - timeSent).coerceAtLeast(0L)
        } else {
            0L
        }
        val capturedElapsedMs = SystemClock.elapsedRealtime() - delay

        val playback = PlaybackAnchor(
            isPlaying = intent.getBooleanExtra(SpotifyBroadcasts.EXTRA_PLAYING, false),
            positionMs = intent.getIntExtra(SpotifyBroadcasts.EXTRA_PLAYBACK_POSITION, 0).toLong(),
            capturedElapsedMs = capturedElapsedMs,
            isAccurate = true
        )

        _uiState.update {
            it.copy(
                playback = playback.normalizedAgainst(it.playback),
                message = if (playback.isPlaying) "播放中" else "已暂停",
                lastBroadcastAction = SpotifyBroadcasts.PLAYBACK_STATE_CHANGED,
                playbackSource = playbackSourceLabel("spotify-broadcast")
            )
        }
    }

    private fun fetchLyrics(track: NowPlaying, targetSource: String? = null) {
        val requestKey = track.requestKey()
        val isNewTrack = lyricsRequestKey != requestKey
        if (isNewTrack) {
            preferredSource = null
            prefs.edit().remove(PREF_PREFERRED_LYRICS_SOURCE).apply()
            currentLyricsScore = 0
        }
        lyricsRequestKey = requestKey

        lyricsFetchJob?.cancel()

        lyricsFetchJob = viewModelScope.launch {
            val isCached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                lyricsProvider.isCached(track)
            }
            if (!isCached) {
                _uiState.update { it.copy(isLoadingLyrics = true, message = "正在搜索同步歌词...") }
            }

            val result = lyricsProvider.findSyncedLyrics(track, targetSource)
            if (lyricsRequestKey != requestKey) return@launch

            _uiState.update { state ->
                result.fold(
                    onSuccess = { cacheResult ->
                        currentLyricsScore = cacheResult.score
                        state.copy(
                            lyrics = cacheResult.lyrics,
                            isLoadingLyrics = false,
                            message = "同步歌词已加载",
                            activeLyricsSource = cacheResult.source
                        )
                    },
                    onFailure = { error ->
                        // Show "instrumental" as a lyric line so the WebView
                        // renders it with the colourful background instead of
                        // falling back to the black EmptyOverlay.
                        val instrumentalLine = listOf(
                            LyricsLine(
                                startTimeMs = 0L,
                                text = "纯音乐 / 无歌词",
                                translation = "",
                                reading = null
                            )
                        )
                        val failedSource = when (targetSource) {
                            "QQ音乐" -> "QQ音乐 (无歌词)"
                            "网易云音乐" -> "网易云音乐 (无歌词)"
                            "LRCLIB" -> "LRCLIB (无歌词)"
                            else -> "未找到"
                        }
                        currentLyricsScore = 0
                        state.copy(
                            lyrics = instrumentalLine,
                            isLoadingLyrics = false,
                            message = userFacingLyricsError(error),
                            activeLyricsSource = failedSource
                        )
                    }
                )
            }

            val trackedSource = result.getOrNull()?.source ?: targetSource ?: "auto"
            AnonymousStats.trackLyricsFetchResult(getApplication(), trackedSource, result.isSuccess)

            // Background pre-fetching when starting a song in Auto mode
            // This pre-warms the cache for other sources in the background,
            // providing an instant "秒切" experience if the user toggles sources manually.
            if (targetSource == null && result.isSuccess) {
                val resolvedSource = result.getOrNull()?.source
                if (resolvedSource != null && resolvedSource != "纯音乐") {
                    val remainingSources = listOf("QQ音乐", "网易云音乐", "LRCLIB").filter { it != resolvedSource }
                    remainingSources.forEach { source ->
                        launch {
                            lyricsProvider.findSyncedLyricsForSource(track, source)
                        }
                    }
                }
            }
        }
    }

    private fun shouldFetchLyrics(track: NowPlaying): Boolean {
        return track.requestKey() != lyricsRequestKey
    }

    private fun NowPlaying.requestKey(): String =
        listOf(mediaPackage, track, artist, album).joinToString("|")

    private fun NowPlaying.withPaletteFallback(previous: NowPlaying): NowPlaying =
        if (requestKey() == previous.requestKey()) {
            copy(
                backgroundStart = previous.backgroundStart ?: backgroundStart,
                backgroundEnd = previous.backgroundEnd ?: backgroundEnd,
                backgroundAccent = previous.backgroundAccent ?: backgroundAccent,
                albumArt = previous.albumArt ?: albumArt,
                colorStyleIndex = previous.colorStyleIndex,
                paletteTemplates = previous.paletteTemplates.ifEmpty { paletteTemplates }
            )
        } else {
            this
        }

    private fun PlaybackAnchor.normalizedAgainst(previous: PlaybackAnchor): PlaybackAnchor {
        if (isPlaying) {
            val previousPosition = previous.currentPositionMs()
            val currentPosition = currentPositionMs()
            val delta = previousPosition - currentPosition
            if (delta in 1..PAUSE_FREEZE_TOLERANCE_MS) {
                return copy(
                    positionMs = previousPosition.coerceAtLeast(0L),
                    capturedElapsedMs = SystemClock.elapsedRealtime()
                )
            }
            return this
        }
        if (!previous.isPlaying) return this

        val extrapolatedPrevious = previous.currentPositionMs()
        val delta = kotlin.math.abs(extrapolatedPrevious - positionMs)
        val frozenPosition = if (delta <= PAUSE_FREEZE_TOLERANCE_MS) {
            extrapolatedPrevious
        } else {
            positionMs
        }
        return copy(
            positionMs = frozenPosition.coerceAtLeast(0L),
            capturedElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    fun switchLyricsSource() {
        val nowPlaying = _uiState.value.nowPlaying
        if (nowPlaying.hasTrack) {
            val currentSource = preferredSource ?: _uiState.value.activeLyricsSource
            val nextSource = when {
                currentSource.contains("QQ音乐") -> "网易云音乐"
                currentSource.contains("网易云音乐") -> "LRCLIB"
                else -> "QQ音乐"
            }

            preferredSource = nextSource
            prefs.edit().putString(PREF_PREFERRED_LYRICS_SOURCE, nextSource).apply()

            // Dismiss the previous Toast instantly and show the new selection
            activeToast?.cancel()
            val toast = android.widget.Toast.makeText(getApplication(), "已切换至：$nextSource", android.widget.Toast.LENGTH_SHORT)
            toast.show()
            activeToast = toast

            val isCached = lyricsProvider.isCachedForSource(nowPlaying, nextSource)

            // Set UI state to loading and update source status instantly on Main thread
            _uiState.update { state ->
                state.copy(
                    lyrics = if (isCached) state.lyrics else emptyList(),
                    isLoadingLyrics = !isCached,
                    message = if (isCached) "正在切换至 $nextSource..." else "正在从 $nextSource 加载...",
                    activeLyricsSource = nextSource
                )
            }

            // Cancel any ongoing fetch job
            lyricsFetchJob?.cancel()

            // Launch the new managed fetch job
            lyricsFetchJob = viewModelScope.launch {
                // Fetch using only the nextSource
                val result = lyricsProvider.findSyncedLyrics(nowPlaying, nextSource)
                if (!isActive) return@launch

                if (result.isSuccess) {
                    val cacheResult = result.getOrNull()
                    if (cacheResult != null) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            lyricsProvider.saveToCache(nowPlaying, cacheResult.lyrics, cacheResult.source)
                        }
                    }
                } else {
                    // Evict cache in background thread ONLY when manual switch fails (timeout, error, no lyrics)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        lyricsProvider.clearCache(nowPlaying)
                    }
                }

                AnonymousStats.trackLyricsFetchResult(getApplication(), nextSource, result.isSuccess)

                _uiState.update { state ->
                    result.fold(
                        onSuccess = { cacheResult ->
                            currentLyricsScore = cacheResult.score
                            state.copy(
                                lyrics = cacheResult.lyrics,
                                isLoadingLyrics = false,
                                message = "同步歌词已加载",
                                activeLyricsSource = cacheResult.source
                            )
                        },
                        onFailure = { error ->
                            val instrumentalLine = listOf(
                                LyricsLine(
                                    startTimeMs = 0L,
                                    text = "纯音乐 / 无歌词",
                                    translation = "",
                                    reading = null
                                )
                            )
                            val failedSource = when (nextSource) {
                                "QQ音乐" -> "QQ音乐 (无歌词)"
                                "网易云音乐" -> "网易云音乐 (无歌词)"
                                "LRCLIB" -> "LRCLIB (无歌词)"
                                else -> "未找到"
                            }
                            currentLyricsScore = 0
                            state.copy(
                                lyrics = instrumentalLine,
                                isLoadingLyrics = false,
                                message = userFacingLyricsError(error),
                                activeLyricsSource = failedSource
                            )
                        }
                    )
                }

                // Notify FloatingLyricsService to refresh the lyrics if running
                val prefs = getApplication<android.app.Application>().getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
                if (prefs.getBoolean("show_floating_lyrics", false)) {
                    val intent = Intent(getApplication(), com.lyricsplus.android.lyrics.FloatingLyricsService::class.java).apply {
                        action = com.lyricsplus.android.lyrics.FloatingLyricsService.ACTION_REFRESH_LYRICS
                    }
                    runCatching {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            getApplication<android.app.Application>().startForegroundService(intent)
                        } else {
                            getApplication<android.app.Application>().startService(intent)
                        }
                    }
                }
            }
        }
    }

    fun adjustOffset(deltaMs: Long) {
        _uiState.update { state ->
            val nextOffset = state.lyricsOffsetMs + deltaMs
            if (state.showFloatingLyrics) {
                val intent = Intent(getApplication(), com.lyricsplus.android.lyrics.FloatingLyricsService::class.java).apply {
                    action = com.lyricsplus.android.lyrics.FloatingLyricsService.ACTION_UPDATE_OFFSET
                    putExtra(com.lyricsplus.android.lyrics.FloatingLyricsService.EXTRA_OFFSET, nextOffset)
                }
                getApplication<Application>().startService(intent)
            }
            if (state.showSuperIslandLyrics) {
                val intent = Intent(getApplication(), com.lyricsplus.android.lyrics.SuperIslandLyricsService::class.java).apply {
                    action = com.lyricsplus.android.lyrics.SuperIslandLyricsService.ACTION_UPDATE_OFFSET
                    putExtra(com.lyricsplus.android.lyrics.SuperIslandLyricsService.EXTRA_OFFSET, nextOffset)
                }
                getApplication<Application>().startService(intent)
            }
            state.copy(lyricsOffsetMs = nextOffset)
        }
    }

    fun adjustInAppFontScale(delta: Float) {
        _uiState.update { state ->
            val nextScale = (state.inAppFontScale + delta).coerceIn(0.5f, 2.5f)
            prefs.edit().putFloat("in_app_font_scale", nextScale).apply()
            state.copy(inAppFontScale = nextScale)
        }
    }

    fun cycleReadingMode() {
        _uiState.update { state ->
            val nextMode = (state.readingMode + 1) % 3
            prefs.edit().putInt("reading_mode", nextMode).apply()
            AnonymousStats.trackReadingModeChanged(getApplication(), nextMode)
            state.copy(readingMode = nextMode)
        }
    }

    fun toggleDeviceUiMode() {
        _uiState.update { state ->
            val nextMode = if (state.deviceUiMode == 0) 1 else 0
            prefs.edit().putInt("device_ui_mode", nextMode).apply()
            AnonymousStats.trackFeatureToggle(getApplication(), "device_ui_mode", nextMode == 1)
            state.copy(deviceUiMode = nextMode)
        }
    }

    fun toggleKeepScreenOn() {
        _uiState.update { state ->
            val nextVal = !state.keepScreenOn
            prefs.edit().putBoolean("keep_screen_on", nextVal).apply()
            AnonymousStats.trackFeatureToggle(getApplication(), "keep_screen_on", nextVal)
            state.copy(keepScreenOn = nextVal)
        }
    }

    fun toggleFloatingLyrics() {
        val context = getApplication<Application>()
        _uiState.update { state ->
            val nextVal = !state.showFloatingLyrics
            
            if (nextVal) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && 
                    !android.provider.Settings.canDrawOverlays(context)) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return@update state
                }
                
                startFloatingService(context)
            } else {
                stopFloatingService(context)
            }
            
            prefs.edit().putBoolean("show_floating_lyrics", nextVal).apply()
            AnonymousStats.trackFeatureToggle(context, "floating_lyrics", nextVal)
            state.copy(showFloatingLyrics = nextVal)
        }
    }

    fun toggleSuperIslandLyrics() {
        val context = getApplication<Application>()
        _uiState.update { state ->
            val nextVal = !state.showSuperIslandLyrics
            if (nextVal) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    android.widget.Toast.makeText(context, "请先授予通知权限后再开启超级岛歌词", android.widget.Toast.LENGTH_LONG).show()
                    return@update state
                }
                if (prefs.getBoolean("super_island_xmsf_bypass", true)) {
                    viewModelScope.launch { requestShizukuPermission(showToast = false) }
                }
                startSuperIslandService(context)
            } else {
                stopSuperIslandService(context)
            }
            prefs.edit().putBoolean("show_super_island_lyrics", nextVal).apply()
            AnonymousStats.trackFeatureToggle(context, "super_island_lyrics", nextVal)
            state.copy(showSuperIslandLyrics = nextVal)
        }
    }

    fun toggleAnonymousStats() {
        val context = getApplication<Application>()
        _uiState.update { state ->
            val nextVal = !state.anonymousStatsEnabled
            AnonymousStats.setEnabled(context, nextVal)
            if (nextVal) {
                AnonymousStats.trackFeatureToggle(context, "anonymous_stats", true)
            }
            state.copy(anonymousStatsEnabled = nextVal)
        }
    }

    fun toggleAutoCheckUpdates() {
        val context = getApplication<Application>()
        val nextVal = !_uiState.value.autoCheckUpdatesEnabled
        prefs.edit().putBoolean(PREF_AUTO_CHECK_UPDATES, nextVal).apply()
        AnonymousStats.trackFeatureToggle(context, "auto_check_updates", nextVal)
        _uiState.update { state ->
            state.copy(autoCheckUpdatesEnabled = nextVal)
        }
        if (nextVal) {
            maybeCheckForUpdatesAutomatically()
        }
    }

    private suspend fun requestShizukuPermission(showToast: Boolean = true) {
        val context = getApplication<Application>()
        val granted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.lyricsplus.android.shizuku.requireShizukuPermissionGranted { true }
            }.getOrDefault(false)
        }
        if (showToast) {
            android.widget.Toast.makeText(
                context,
                if (granted) "Shizuku 已授权" else "Shizuku 未运行或授权失败",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startFloatingService(context: Context) {
        val intent = Intent(context, com.lyricsplus.android.lyrics.FloatingLyricsService::class.java).apply {
            putExtra(com.lyricsplus.android.lyrics.FloatingLyricsService.EXTRA_OFFSET, _uiState.value.lyricsOffsetMs)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopFloatingService(context: Context) {
        val intent = Intent(context, com.lyricsplus.android.lyrics.FloatingLyricsService::class.java)
        context.stopService(intent)
    }

    private fun startSuperIslandService(context: Context) {
        val intent = Intent(context, com.lyricsplus.android.lyrics.SuperIslandLyricsService::class.java).apply {
            putExtra(com.lyricsplus.android.lyrics.SuperIslandLyricsService.EXTRA_OFFSET, _uiState.value.lyricsOffsetMs)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopSuperIslandService(context: Context) {
        val intent = Intent(context, com.lyricsplus.android.lyrics.SuperIslandLyricsService::class.java)
        context.stopService(intent)
    }

    fun checkForUpdates(silent: Boolean = false) {
        val currentVersion = BuildConfig.VERSION_NAME
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!silent) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(getApplication(), "正在检查更新...", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/Artriai/lyrics-plus-android/releases/latest")
                .header("User-Agent", "lyrics-plus-android")
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("服务器返回异常 (${response.code})")
                    val body = response.body?.string().orEmpty()
                    val tagRegex = "\"tag_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val matchResult = tagRegex.find(body)
                    val latestVersionWithV = matchResult?.groupValues?.get(1)
                    if (latestVersionWithV != null) {
                        val latestVersion = latestVersionWithV.trimStart('v', 'V')
                        val isNewer = isVersionNewer(latestVersion, currentVersion)
                        val htmlUrlRegex = "\"html_url\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                        val htmlUrlMatch = htmlUrlRegex.find(body)
                        val htmlUrl = htmlUrlMatch?.groupValues?.get(1) ?: "https://github.com/Artriai/lyrics-plus-android/releases"
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isNewer) {
                                android.widget.Toast.makeText(getApplication(), "发现新版本: v$latestVersion！即将前往下载...", android.widget.Toast.LENGTH_LONG).show()
                                kotlinx.coroutines.delay(1500)
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(htmlUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                getApplication<Application>().startActivity(intent)
                            } else {
                                if (!silent) {
                                    android.widget.Toast.makeText(getApplication(), "已是最新版本 (当前版本: v$currentVersion)", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } else {
                        throw java.io.IOException("无法解析服务器返回的版本信息")
                    }
                }
            }.onFailure { error ->
                if (!silent) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(getApplication(), "检查更新失败: ${userFacingNetworkError(error)}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun maybeCheckForUpdatesAutomatically() {
        if (autoUpdateCheckStarted || !prefs.getBoolean(PREF_AUTO_CHECK_UPDATES, true)) return
        autoUpdateCheckStarted = true
        checkForUpdates(silent = true)
    }

    private fun playbackSourceLabel(source: String): String = when (source) {
        "active-sessions" -> "媒体会话"
        "notification-token" -> "通知监听"
        "spotify-broadcast" -> "Spotify 广播"
        "none" -> "未连接"
        else -> source
    }

    private fun userFacingLyricsError(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("网络") || message.contains("超时") || message.contains("HTTP", ignoreCase = true) ->
                "歌词服务请求失败"
            else -> "未找到同步歌词"
        }
    }

    private fun userFacingNetworkError(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (message.any { it in '\u4e00'..'\u9fff' }) {
            message
        } else {
            "网络请求失败，请稍后重试"
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        return runCatching {
            val latestParts = latest.split(".").map { it.toInt() }
            val currentParts = current.split(".").map { it.toInt() }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
            false
        }.getOrDefault(latest != current)
    }

    fun forceSyncTime() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val reader = com.lyricsplus.android.spotify.SpotifyMediaSessionReader(getApplication())
            val snapshot = reader.readSnapshot()
            if (snapshot != null) {
                _uiState.update { it.copy(lyricsOffsetMs = 0L) }
                if (_uiState.value.showFloatingLyrics) {
                    val intent = Intent(getApplication(), com.lyricsplus.android.lyrics.FloatingLyricsService::class.java).apply {
                        action = com.lyricsplus.android.lyrics.FloatingLyricsService.ACTION_UPDATE_OFFSET
                        putExtra(com.lyricsplus.android.lyrics.FloatingLyricsService.EXTRA_OFFSET, 0L)
                    }
                    getApplication<Application>().startService(intent)
                }
                if (_uiState.value.showSuperIslandLyrics) {
                    val intent = Intent(getApplication(), com.lyricsplus.android.lyrics.SuperIslandLyricsService::class.java).apply {
                        action = com.lyricsplus.android.lyrics.SuperIslandLyricsService.ACTION_UPDATE_OFFSET
                        putExtra(com.lyricsplus.android.lyrics.SuperIslandLyricsService.EXTRA_OFFSET, 0L)
                    }
                    getApplication<Application>().startService(intent)
                }
                onMediaSnapshot(snapshot)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "歌词时间已同步重置", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(getApplication(), "无法同步：未找到活动的媒体会话", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun rotatePaletteColors() {
        _uiState.update { state ->
            val nowPlaying = state.nowPlaying
            if (nowPlaying.paletteTemplates.isNotEmpty()) {
                val nextIndex = (nowPlaying.colorStyleIndex + 1) % nowPlaying.paletteTemplates.size
                val nextPalette = nowPlaying.paletteTemplates[nextIndex]
                state.copy(
                    nowPlaying = nowPlaying.copy(
                        colorStyleIndex = nextIndex,
                        backgroundStart = nextPalette.start,
                        backgroundEnd = nextPalette.end,
                        backgroundAccent = nextPalette.accent
                    )
                )
            } else {
                val start = nowPlaying.backgroundStart
                val end = nowPlaying.backgroundEnd
                val accent = nowPlaying.backgroundAccent
                if (start != null && end != null) {
                    val nextStart = start.shiftColorHue(120f)
                    val nextEnd = end.shiftColorHue(120f)
                    val nextAccent = accent?.shiftColorHue(120f) ?: nextStart.shiftColorHue(60f)
                    state.copy(
                        nowPlaying = nowPlaying.copy(
                            backgroundStart = nextStart,
                            backgroundEnd = nextEnd,
                            backgroundAccent = nextAccent
                        )
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun String.shiftColorHue(degrees: Float): String {
        return runCatching {
            val color = android.graphics.Color.parseColor(this)
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + degrees + 360f) % 360f
            val nextColor = android.graphics.Color.HSVToColor(hsv)
            "#%02X%02X%02X".format(
                android.graphics.Color.red(nextColor),
                android.graphics.Color.green(nextColor),
                android.graphics.Color.blue(nextColor)
            )
        }.getOrDefault(this)
    }

    private fun getSpotifyController(): MediaController? {
        val manager = getApplication<Application>().getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val component = ComponentName(getApplication(), LyricsNotificationListenerService::class.java)
        return runCatching {
            manager.getActiveSessions(component)
                .let { SpotifyMediaSessionSelector.chooseController(it) }
        }.getOrNull()
    }

    fun togglePlayback() {
        val controller = getSpotifyController()
        if (controller != null) {
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        } else {
            android.widget.Toast.makeText(getApplication(), "无法控制：请确保已启动 Spotify", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun skipToNext() {
        val controller = getSpotifyController()
        if (controller != null) {
            controller.transportControls.skipToNext()
        } else {
            android.widget.Toast.makeText(getApplication(), "无法控制：请确保已启动 Spotify", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun syncNow() {
        // Sync floating lyrics preference state with UI instantly
        _uiState.update { it.copy(
            showFloatingLyrics = prefs.getBoolean("show_floating_lyrics", false),
            showSuperIslandLyrics = prefs.getBoolean("show_super_island_lyrics", false)
        ) }
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val reader = com.lyricsplus.android.spotify.SpotifyMediaSessionReader(getApplication())
            val snapshot = reader.readSnapshot()
            if (snapshot != null) {
                com.lyricsplus.android.spotify.LyricsNotificationListenerService.latestSnapshot = snapshot
            } else {
                _uiState.update { it.copy(isInitializing = false) }
            }
        }
    }
}
