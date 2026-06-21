package com.lyricsplus.android.lyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lyricsplus.android.AppLyricsStateStore
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.MainActivity
import com.lyricsplus.android.PREF_PREFERRED_LYRICS_SOURCE
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.PlaybackAnchor
import com.lyricsplus.android.spotify.SpotifyBroadcasts
import com.lyricsplus.android.spotify.LyricsNotificationListenerService
import com.lyricsplus.android.spotify.SpotifyMediaSessionReader
import com.lyricsplus.android.spotify.SpotifyMediaSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuperIslandLyricsService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var lyricsProvider: LyricsProvider
    private lateinit var superIslandHandler: SuperIslandHandler
    private val mediaSessionReader by lazy { SpotifyMediaSessionReader(this) }

    private var currentTrack: NowPlaying? = null
    private var currentLyrics: List<LyricsLine> = emptyList()
    private var currentPlayback: PlaybackAnchor? = null
    private var lyricsOffsetMs = 0L
    private var trackRequestKey: String? = null
    private var tickJob: kotlinx.coroutines.Job? = null
    private lateinit var prefs: SharedPreferences
    private var currentLyricsSource: String? = null
    private var mediaSyncInFlight = false
    private var lastMediaSyncElapsed = 0L
    private var hasAppState = false
    private var collapsedForPausedPlayback = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_PREFERRED_LYRICS_SOURCE) {
            currentTrack?.let { track ->
                currentLyrics = emptyList()
                fetchLyrics(track)
                renderCurrentState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("lyrics_plus_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        lyricsProvider = LyricsProvider.getInstance(this)
        startForeground(SuperIslandHandler.NOTIFICATION_ID, buildInitialIslandNotification())

        superIslandHandler = SuperIslandHandler(
            context = this,
            foregroundStarter = { notification, _ ->
                startForeground(SuperIslandHandler.NOTIFICATION_ID, notification)
            }
        )
        superIslandHandler.start()
        observePlaybackUpdates()
        syncCurrentMediaSnapshot()
        startTickLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_OFFSET -> lyricsOffsetMs = intent.getLongExtra(EXTRA_OFFSET, 0L)
            ACTION_STOP -> stopSelf()
            else -> {
                if (intent?.hasExtra(EXTRA_OFFSET) == true) {
                    lyricsOffsetMs = intent.getLongExtra(EXTRA_OFFSET, 0L)
                }
                syncCurrentMediaSnapshot()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
        superIslandHandler.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observePlaybackUpdates() {
        scope.launch {
            AppLyricsStateStore.state.collect { state ->
                if (state != null) {
                    hasAppState = true
                    applyAppState(state)
                }
            }
        }
        scope.launch {
            LyricsNotificationListenerService.snapshotFlow.collect { snapshot ->
                if (snapshot != null) {
                    if (hasAppState) {
                        applyPlaybackOnlySnapshot(snapshot)
                    } else {
                        applySnapshot(snapshot)
                    }
                }
            }
        }
    }

    private fun applyAppState(state: LyricsUiState) {
        if (!state.nowPlaying.hasTrack) return
        currentTrack = state.nowPlaying
        currentLyrics = state.lyrics
        currentPlayback = state.playback
        lyricsOffsetMs = state.lyricsOffsetMs
        currentLyricsSource = state.activeLyricsSource
        trackRequestKey = state.nowPlaying.stableIslandRequestKey()
        renderCurrentState()
    }

    private fun applyPlaybackOnlySnapshot(snapshot: SpotifyMediaSnapshot) {
        val track = snapshot.nowPlaying
        if (track != null && track.hasTrack) {
            val currentKey = currentTrack?.stableIslandRequestKey()
            val nextKey = track.stableIslandRequestKey()
            if (currentTrack == null || currentKey != nextKey) {
                applySnapshot(snapshot)
                return
            }
        }

        if (snapshot.playback != null) {
            currentPlayback = snapshot.playback
            renderCurrentState()
        }
    }

    private fun syncCurrentMediaSnapshot() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                mediaSessionReader.readSnapshot()
            }
            snapshot?.let(::applySnapshot)
        }
    }

    private fun applySnapshot(snapshot: SpotifyMediaSnapshot) {
        val track = snapshot.nowPlaying
        val playback = snapshot.playback

        if (track != null && track.hasTrack) {
            val key = track.stableIslandRequestKey()
            val preferredSource = preferredLyricsSource()
            if (trackRequestKey != key || currentLyricsSource != preferredSource) {
                trackRequestKey = key
                currentLyricsSource = preferredSource
                currentLyrics = emptyList()
                lyricsOffsetMs = 0L
                currentTrack = track
                fetchLyrics(track)
            } else {
                currentTrack = track
            }
        }

        if (playback != null) {
            currentPlayback = playback
        }

        renderCurrentState()
    }

    private fun fetchLyrics(track: NowPlaying) {
        scope.launch {
            val source = preferredLyricsSource()
            currentLyricsSource = source
            val result = withContext(Dispatchers.IO) {
                lyricsProvider.findSyncedLyrics(track, source)
            }
            currentLyrics = result.fold(
                onSuccess = { it.lyrics },
                onFailure = { listOf(LyricsLine(0L, "纯音乐 / 无歌词")) }
            )
        }
    }

    private fun preferredLyricsSource(): String? =
        prefs.getString(PREF_PREFERRED_LYRICS_SOURCE, null)
            ?.takeIf { it in LYRICS_SOURCES }

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                maybeSyncCurrentMediaSnapshot()
                val pb = currentPlayback
                if (pb?.isPlaying == true) {
                    renderCurrentState()
                    delay(250)
                } else {
                    delay(2000)
                }
            }
        }
    }

    private suspend fun maybeSyncCurrentMediaSnapshot() {
        val now = android.os.SystemClock.elapsedRealtime()
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

    private fun renderCurrentState() {
        val track = currentTrack ?: return
        if (!track.hasTrack) return

        val playback = currentPlayback ?: PlaybackAnchor()
        val positionMs = playback.currentPositionMs() + lyricsOffsetMs
        val activeIndex = findActiveIndex(positionMs, currentLyrics)
        val activeLine = currentLyrics.getOrNull(activeIndex)
        val previousLine = currentLyrics.getOrNull(activeIndex - 1)
        val nextLine = currentLyrics.getOrNull(activeIndex + 1)
        val timedWindow = activeLine?.let {
            buildTimedLyricWindow(it, positionMs, previousLine, nextLine, activeIndex == 0)
                ?: buildStaticPreviewWindow(it, previousLine, nextLine, activeIndex == 0)
        }
        val lyric = timedWindow?.combinedText ?: activeLine?.text?.cleanSuperIslandLyricText()
            ?: if (currentLyrics.isNotEmpty() && positionMs < currentLyrics.first().startTimeMs) {
                "${track.track} - ${track.artist}"
            } else if (currentLyrics.isEmpty()) {
                "${track.track} - ${track.artist}".takeIf { it.isNotBlank() } ?: "♪"
            } else {
                "♪"
            }

        val durationMs = (track.durationSeconds * 1000L).coerceAtLeast(1L)
        val progress = ((positionMs.coerceAtLeast(0L) * 100L) / durationMs).toInt().coerceIn(0, 100)
        val accent = track.backgroundAccent?.let {
            runCatching { android.graphics.Color.parseColor(it) }.getOrNull()
        } ?: 0xFF4AD295.toInt()

        if (!playback.isPlaying) {
            collapseIslandForPause(track)
            return
        }

        collapsedForPausedPlayback = false
        superIslandHandler.render(
            SuperIslandState(
                title = track.track,
                artist = track.artist,
                lyric = lyric,
                fullLyric = activeLine?.text?.cleanSuperIslandLyricText().orEmpty(),
                leftLyric = timedWindow?.left,
                rightLyric = timedWindow?.right,
                progressPercent = progress,
                isPlaying = playback.isPlaying,
                accentColor = accent,
                mediaPackage = track.mediaPackage.ifBlank { SpotifyBroadcasts.PACKAGE_NAME }
            )
        )
    }

    private fun collapseIslandForPause(track: NowPlaying) {
        if (collapsedForPausedPlayback) return
        collapsedForPausedPlayback = true
        superIslandHandler.prepareForCollapsedState()
        startForeground(SuperIslandHandler.NOTIFICATION_ID, buildPausedIslandNotification(track))
    }

    private data class TimedToken(
        val text: String,
        val startMs: Long,
        val endMs: Long
    )

    private data class TimedWindow(
        val left: String,
        val right: String
    ) {
        val combinedText: String = listOf(left, right)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "♪" }
    }

    private fun buildTimedLyricWindow(
        line: LyricsLine,
        positionMs: Long,
        previousLine: LyricsLine?,
        nextLine: LyricsLine?,
        isFirstLine: Boolean
    ): TimedWindow? {
        val rawTokens = parseTimedTokens(line)
        if (line.text.cleanSuperIslandLyricText().shouldUseEnglishWordPairWindow()) {
            return buildEnglishWordWindow(line, rawTokens, positionMs, previousLine, nextLine, isFirstLine)
        }

        val tokens = expandLatinTokens(rawTokens)
        if (tokens.size < 2) return null

        val activeIndex = tokens.indexOfFirst { positionMs in it.startMs until it.endMs }
            .takeIf { it >= 0 }
            ?: tokens.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)

        val sideCapacity = TIMED_NORMAL_SIDE_WEIGHT

        val leftTokens = mutableListOf<TimedToken>()
        var leftWeight = 0
        for (index in activeIndex downTo 0) {
            val token = tokens[index]
            val nextWeight = leftWeight + token.text.lyricWeight()
            if (nextWeight > sideCapacity) {
                val remaining = sideCapacity - leftWeight
                if (remaining > 0) {
                    leftTokens.add(0, token.copy(text = token.text.takeByLyricWeightFromEnd(remaining)))
                }
                break
            }
            leftTokens.add(0, token)
            leftWeight = nextWeight
        }

        if (!isFirstLine && leftWeight < sideCapacity && previousLine != null) {
            val previousTokens = previewTokens(previousLine)
            for (index in previousTokens.lastIndex downTo 0) {
                val token = previousTokens[index]
                val nextWeight = leftWeight + token.text.lyricWeight()
                if (nextWeight > sideCapacity) {
                    val remaining = sideCapacity - leftWeight
                    if (remaining > 0) {
                        leftTokens.add(0, token.copy(text = token.text.takeByLyricWeightFromEnd(remaining)))
                    }
                    break
                }
                leftTokens.add(0, token)
                leftWeight = nextWeight
            }
        }

        val rightTokens = mutableListOf<TimedToken>()
        var rightWeight = 0
        for (index in activeIndex + 1 until tokens.size) {
            val token = tokens[index]
            val nextWeight = rightWeight + token.text.lyricWeight()
            if (nextWeight > sideCapacity) {
                val remaining = sideCapacity - rightWeight
                if (remaining > 0) {
                    rightTokens.add(token.copy(text = token.text.takeByLyricWeight(remaining)))
                }
                break
            }
            rightTokens.add(token)
            rightWeight = nextWeight
        }

        if (activeIndex >= tokens.size / 2 && rightWeight < sideCapacity && nextLine != null) {
            for (token in previewTokens(nextLine)) {
                val nextWeight = rightWeight + token.text.lyricWeight()
                if (nextWeight > sideCapacity) {
                    val remaining = sideCapacity - rightWeight
                    if (remaining > 0) {
                        rightTokens.add(token.copy(text = token.text.takeByLyricWeight(remaining)))
                    }
                    break
                }
                rightTokens.add(token)
                rightWeight = nextWeight
            }
        }

        val left = joinTimedTokens(leftTokens).takeByLyricWeightFromEnd(sideCapacity)
        val right = joinTimedTokens(rightTokens).takeByLyricWeight(sideCapacity)
        if (left.isBlank() && right.isBlank()) return null
        return TimedWindow(left = left, right = right)
    }

    private fun buildStaticPreviewWindow(
        line: LyricsLine,
        previousLine: LyricsLine?,
        nextLine: LyricsLine?,
        isFirstLine: Boolean
    ): TimedWindow? {
        val current = line.text.cleanSuperIslandLyricText()
        if (current == "♪") return null
        val previous = if (!isFirstLine) previousLine?.text?.cleanSuperIslandLyricText()?.takeIf { it != "♪" } else null
        val next = nextLine?.text?.cleanSuperIslandLyricText()?.takeIf { it != "♪" }

        return if (current.isLatinStaticText()) {
            buildStaticLatinPreviewWindow(current, previous, next)
        } else {
            buildStaticWeightedPreviewWindow(current, previous, next)
        }
    }

    private fun buildStaticLatinPreviewWindow(
        current: String,
        previous: String?,
        next: String?
    ): TimedWindow {
        val capacity = ENGLISH_MAX_SIDE_CHARS
        if (current.length <= capacity) {
            val left = listOfNotNull(previous, current)
                .joinToString(" ")
                .takeLast(capacity)
                .trimStart()
            val right = (next ?: current)
                .take(capacity)
                .trimEnd()
            return TimedWindow(left = left, right = right)
        }

        val splitIndex = findStaticLatinSplitIndex(current, capacity)
        val leftCore = current.substring(0, splitIndex).trim()
        val rightCore = current.substring(splitIndex).trim()
        val left = listOfNotNull(previous, leftCore)
            .joinToString(" ")
            .takeLast(capacity)
            .trimStart()
        val right = listOfNotNull(rightCore, next)
            .joinToString(" ")
            .take(capacity)
            .trimEnd()
        return TimedWindow(left = left, right = right)
    }

    private fun buildStaticWeightedPreviewWindow(
        current: String,
        previous: String?,
        next: String?
    ): TimedWindow {
        val capacity = TIMED_NORMAL_SIDE_WEIGHT
        if (current.lyricWeight() <= capacity) {
            val left = listOfNotNull(previous, current)
                .joinToString("")
                .takeByLyricWeightFromEnd(capacity)
            val right = (next ?: current).takeByLyricWeight(capacity)
            return TimedWindow(left = left, right = right)
        }

        val visible = current.takeByLyricWeight(capacity * 2)
        val splitIndex = findStaticWeightedSplitIndex(visible, capacity)
        val leftCore = visible.substring(0, splitIndex).trim()
        val rightCore = visible.substring(splitIndex).trim()
        val left = listOfNotNull(previous, leftCore)
            .joinToString("")
            .takeByLyricWeightFromEnd(capacity)
        val right = listOfNotNull(rightCore, next)
            .joinToString("")
            .takeByLyricWeight(capacity)
        return TimedWindow(left = left, right = right)
    }

    private fun findStaticLatinSplitIndex(text: String, capacity: Int): Int {
        if (text.length <= 1) return text.length
        val limit = text.length.coerceAtMost(capacity * 2)
        val visible = text.take(limit)
        val midpoint = visible.length / 2
        val candidates = (1 until visible.length)
            .filter { visible[it - 1].isWhitespace() || visible[it].isWhitespace() }
            .ifEmpty { (1 until visible.length).toList() }
        return candidates.minByOrNull { kotlin.math.abs(it - midpoint) } ?: midpoint.coerceAtLeast(1)
    }

    private fun findStaticWeightedSplitIndex(text: String, capacity: Int): Int {
        if (text.length <= 1) return text.length
        var bestIndex = 1
        var bestScore = Int.MAX_VALUE
        for (index in 1 until text.length) {
            val leftWeight = text.substring(0, index).trim().lyricWeight()
            val rightWeight = text.substring(index).trim().lyricWeight()
            if (leftWeight > capacity || rightWeight > capacity) continue
            val score = kotlin.math.abs(leftWeight - rightWeight)
            if (score < bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun buildEnglishWordWindow(
        line: LyricsLine,
        rawTokens: List<TimedToken>,
        positionMs: Long,
        previousLine: LyricsLine?,
        nextLine: LyricsLine?,
        isFirstLine: Boolean
    ): TimedWindow? {
        val tokens = rawTokens.toEnglishWordTokens()
        if (tokens.isEmpty()) return null

        val activeIndex = tokens.indexOfFirst { positionMs in it.startMs until it.endMs }
            .takeIf { it >= 0 }
            ?: tokens.indexOfLast { positionMs >= it.startMs }.coerceAtLeast(0)

        val nextLineWords = nextLine
            ?.takeIf { it.text.cleanSuperIslandLyricText().shouldUseEnglishWordPairWindow() }
            ?.let { previewEnglishWordTokens(it).mapNotNull { token -> token.text.cleanEnglishIslandWord().ifBlank { null } } }
            .orEmpty()
        val previousLineWords = if (!isFirstLine) {
            previousLine
                ?.takeIf { it.text.cleanSuperIslandLyricText().shouldUseEnglishWordPairWindow() }
                ?.let { previewEnglishWordTokens(it).mapNotNull { token -> token.text.cleanEnglishIslandWord().ifBlank { null } } }
                .orEmpty()
        } else {
            emptyList()
        }
        val words = tokens.map { it.text.cleanEnglishIslandWord() }
        val left = buildEnglishLeftWords(words, activeIndex, previousLineWords)
        val right = buildEnglishRightWords(words, activeIndex, nextLineWords)
        if (left.isBlank() && right.isBlank()) return null
        return TimedWindow(left = left, right = right)
    }

    private fun buildEnglishLeftWords(
        words: List<String>,
        activeIndex: Int,
        previousLineWords: List<String>
    ): String {
        val active = words.getOrNull(activeIndex).orEmpty()
        val selected = mutableListOf<String>()
        if (active.isNotBlank()) {
            selected.add(active)
        }
        var index = activeIndex - 1
        while (index >= 0) {
            val word = words[index--]
            if (word.isBlank()) continue
            val candidate = (listOf(word) + selected).joinToString(" ")
            if (candidate.length > ENGLISH_MAX_SIDE_CHARS) {
                val current = selected.joinToString(" ")
                val separator = if (current.isBlank()) "" else " "
                val remaining = ENGLISH_MAX_SIDE_CHARS - current.length - separator.length
                if (remaining > 0) {
                    selected.add(0, word.takeLast(remaining))
                }
                break
            }
            selected.add(0, word)
        }
        var previousIndex = previousLineWords.lastIndex
        while (previousIndex >= 0) {
            val word = previousLineWords[previousIndex--]
            if (word.isBlank()) continue
            val candidate = (listOf(word) + selected).joinToString(" ")
            if (candidate.length > ENGLISH_MAX_SIDE_CHARS) {
                val current = selected.joinToString(" ")
                val separator = if (current.isBlank()) "" else " "
                val remaining = ENGLISH_MAX_SIDE_CHARS - current.length - separator.length
                if (remaining > 0) {
                    selected.add(0, word.takeLast(remaining))
                }
                break
            }
            selected.add(0, word)
        }
        return selected.joinToString(" ")
            .takeLast(ENGLISH_MAX_SIDE_CHARS)
            .trimStart()
    }

    private fun buildEnglishRightWords(
        words: List<String>,
        activeIndex: Int,
        nextLineWords: List<String>
    ): String {
        val selected = mutableListOf<String>()
        var index = activeIndex + 1
        var nextLineIndex = 0

        fun nextWord(): String? {
            while (index < words.size) {
                val word = words[index++]
                if (word.isNotBlank()) return word
            }
            while (nextLineIndex < nextLineWords.size) {
                val word = nextLineWords[nextLineIndex++]
                if (word.isNotBlank()) return word
            }
            return null
        }

        while (true) {
            val word = nextWord() ?: break
            val current = selected.joinToString(" ")
            val separator = if (current.isBlank()) "" else " "
            val candidate = current + separator + word
            if (candidate.length <= ENGLISH_MAX_SIDE_CHARS) {
                selected.add(word)
                continue
            }
            val remaining = ENGLISH_MAX_SIDE_CHARS - current.length - separator.length
            if (remaining > 0) {
                selected.add(word.take(remaining))
            }
            break
        }
        return selected.joinToString(" ")
            .take(ENGLISH_MAX_SIDE_CHARS)
            .trimEnd()
    }

    private fun previewTokens(line: LyricsLine): List<TimedToken> {
        val timedTokens = expandLatinTokens(parseTimedTokens(line))
        if (timedTokens.isNotEmpty()) return timedTokens
        val clean = line.text.cleanSuperIslandLyricText()
        if (clean == "♪") return emptyList()
        return expandLatinTokens(listOf(TimedToken(clean, line.startTimeMs, line.startTimeMs + 1000L)))
    }

    private fun previewEnglishWordTokens(line: LyricsLine): List<TimedToken> {
        return parseTimedTokens(line).toEnglishWordTokens()
    }

    private fun parseTimedTokens(line: LyricsLine): List<TimedToken> {
        val text = line.text
        val prefixRegex = Regex("\\((\\d+),(\\d+)(?:,\\d+)?\\)([^\\(<]+)")
        val suffixRegex = Regex("([^\\(\\)]+?)\\((\\d+),(\\d+)(?:,\\d+)?\\)")
        val enhancedRegex = Regex("<(\\d{1,2}):(\\d{2})[.:](\\d{2,3})>([^<]*)")

        val parsePrefixFirst = text.trimStart().startsWith("(")

        fun parsePrefix(): List<TimedToken> = prefixRegex.findAll(text).mapNotNull { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val tokenText = match.groupValues[3]
            val absoluteStart = resolveTokenStart(line.startTimeMs, start)
            TimedToken(tokenText, absoluteStart, absoluteStart + duration.coerceAtLeast(1L))
        }.toList()

        fun parseSuffix(): List<TimedToken> = suffixRegex.findAll(text).mapNotNull { match ->
            val tokenText = match.groupValues[1]
            val start = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[3].toLongOrNull() ?: return@mapNotNull null
            val absoluteStart = resolveTokenStart(line.startTimeMs, start)
            TimedToken(tokenText, absoluteStart, absoluteStart + duration.coerceAtLeast(1L))
        }.toList()

        if (parsePrefixFirst) {
            val prefix = parsePrefix()
            if (prefix.isNotEmpty()) return prefix
            val suffix = parseSuffix()
            if (suffix.isNotEmpty()) return suffix
        } else {
            val suffix = parseSuffix()
            if (suffix.isNotEmpty()) return suffix
            val prefix = parsePrefix()
            if (prefix.isNotEmpty()) return prefix
        }

        val enhancedMatches = enhancedRegex.findAll(text).toList()
        if (enhancedMatches.isNotEmpty()) {
            return enhancedMatches.mapIndexedNotNull { index, match ->
                val min = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
                val sec = match.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
                val fracRaw = match.groupValues[3]
                val frac = fracRaw.toLongOrNull()?.let { if (fracRaw.length == 2) it * 10 else it }
                    ?: return@mapIndexedNotNull null
                val start = min * 60_000L + sec * 1000L + frac
                val nextStart = enhancedMatches.getOrNull(index + 1)?.let { next ->
                    val nextMin = next.groupValues[1].toLongOrNull() ?: return@let null
                    val nextSec = next.groupValues[2].toLongOrNull() ?: return@let null
                    val nextFracRaw = next.groupValues[3]
                    val nextFrac = nextFracRaw.toLongOrNull()?.let { if (nextFracRaw.length == 2) it * 10 else it }
                        ?: return@let null
                    nextMin * 60_000L + nextSec * 1000L + nextFrac
                }
                TimedToken(match.groupValues[4], start, nextStart ?: (start + 300L))
            }
        }

        return emptyList()
    }

    private fun expandLatinTokens(tokens: List<TimedToken>): List<TimedToken> {
        return tokens.flatMap { token ->
            if (!token.text.shouldExpandByCharacter()) {
                listOf(token)
            } else {
                val chars = token.text.toList()
                val duration = (token.endMs - token.startMs).coerceAtLeast(chars.size.toLong())
                chars.mapIndexed { index, char ->
                    val start = token.startMs + duration * index / chars.size
                    val end = token.startMs + duration * (index + 1) / chars.size
                    TimedToken(char.toString(), start, end.coerceAtLeast(start + 1L))
                }
            }
        }
    }

    private fun String.shouldExpandByCharacter(): Boolean {
        val visible = filterNot { it.isWhitespace() }
        if (visible.length <= 1) return false
        return visible.any { it.isLetterOrDigit() } && visible.none { it.isCjkLike() }
    }

    private fun String.cleanEnglishIslandWord(): String =
        Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?[!?.,:;]*")
            .find(normalizeEnglishIslandInput())
            ?.value
            .orEmpty()

    private fun String.normalizeEnglishIslandInput(): String =
        filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .map { if (it.isWhitespace() || Character.isSpaceChar(it)) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun List<TimedToken>.toEnglishWordTokens(): List<TimedToken> {
        return flatMap { token ->
            val matches = Regex("\\S+").findAll(token.text).toList()
            if (matches.isEmpty()) {
                emptyList()
            } else if (matches.size == 1) {
                listOf(token.copy(text = matches.first().value))
            } else {
                val duration = (token.endMs - token.startMs).coerceAtLeast(matches.size.toLong())
                matches.mapIndexed { index, match ->
                    val start = token.startMs + duration * index / matches.size
                    val end = token.startMs + duration * (index + 1) / matches.size
                    TimedToken(match.value, start, end.coerceAtLeast(start + 1L))
                }
            }
        }
    }

    private fun String.shouldUseEnglishWordPairWindow(): Boolean {
        val visible = filterNot { it.isWhitespace() }
        return visible.any { it.isLetterOrDigit() } && visible.none { it.isCjkLike() } && any { it.isWhitespace() }
    }

    private fun String.isLatinStaticText(): Boolean {
        val visible = filterNot { it.isWhitespace() }
        return visible.any { it.isLetterOrDigit() } && visible.none { it.isCjkLike() }
    }

    private fun resolveTokenStart(lineStartMs: Long, tokenStartMs: Long): Long {
        val likelyRelative = tokenStartMs < lineStartMs &&
            (tokenStartMs < 1000L || (lineStartMs - tokenStartMs) > (lineStartMs * 0.5).coerceAtLeast(2000.0))
        return if (likelyRelative) lineStartMs + tokenStartMs else tokenStartMs
    }

    private fun joinTimedTokens(tokens: List<TimedToken>): String {
        if (tokens.isEmpty()) return ""
        val raw = tokens.joinToString("") { it.text }
        return raw.replace(Regex("\\s+"), " ").trim()
    }

    private fun String.cleanSuperIslandLyricText(): String =
        replace(Regex("<\\d{1,2}:\\d{2}[.:]\\d{1,3}>"), "")
            .replace(Regex("\\(\\d+,\\d+(?:,\\d+)?\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "♪" }

    private fun String.lyricWeight(): Int = sumOf { it.lyricCharWeight() }

    private fun String.takeByLyricWeight(maxWeight: Int): String {
        var weight = 0
        var end = 0
        for (index in indices) {
            weight += this[index].lyricCharWeight()
            if (weight > maxWeight) break
            end = index + 1
        }
        return substring(0, end).trim()
    }

    private fun String.takeByLyricWeightFromEnd(maxWeight: Int): String {
        var weight = 0
        var start = length
        for (index in indices.reversed()) {
            weight += this[index].lyricCharWeight()
            if (weight > maxWeight) break
            start = index
        }
        return substring(start).trim()
    }

    private fun Char.lyricCharWeight(): Int {
        if (isWhitespace()) return 1
        return if (isCjkLike()) 2 else 1
    }

    private fun Char.isCjkLike(): Boolean {
        return when (Character.UnicodeBlock.of(this)) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HANGUL_SYLLABLES -> true
            else -> false
        }
    }

    private fun findActiveIndex(positionMs: Long, lyrics: List<LyricsLine>): Int {
        if (lyrics.isEmpty() || positionMs < lyrics.first().startTimeMs) return -1
        var low = 0
        var high = lyrics.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (positionMs >= lyrics[mid].startTimeMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun NowPlaying.stableIslandRequestKey(): String =
        listOf(mediaPackage, track, artist, album).joinToString("|")

    private fun buildInitialIslandNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, SuperIslandHandler.CHANNEL_ID)
            .setContentTitle("♪")
            .setContentText("超级岛歌词")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildPausedIslandNotification(track: NowPlaying): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = listOf(track.track, track.artist)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "播放已暂停" }
        return NotificationCompat.Builder(this, SuperIslandHandler.CHANNEL_ID)
            .setContentTitle("播放已暂停")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_UPDATE_OFFSET = "com.lyricsplus.android.action.SUPER_ISLAND_UPDATE_OFFSET"
        const val ACTION_STOP = "com.lyricsplus.android.action.SUPER_ISLAND_STOP"
        const val EXTRA_OFFSET = "com.lyricsplus.android.extra.SUPER_ISLAND_OFFSET"
        private val LYRICS_SOURCES = setOf("QQ音乐", "网易云音乐", "LRCLIB")
        private const val MEDIA_SESSION_SYNC_INTERVAL_MS = 500L
        private const val TIMED_NORMAL_SIDE_WEIGHT = 16
        private const val ENGLISH_MAX_SIDE_CHARS = 20
    }
}
