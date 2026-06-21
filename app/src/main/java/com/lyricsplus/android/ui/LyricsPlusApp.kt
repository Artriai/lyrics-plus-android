package com.lyricsplus.android.ui

import android.app.Activity
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import android.view.ViewGroup
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.lyricsplus.android.LyricsUiState
import com.lyricsplus.android.MainViewModel
import kotlinx.coroutines.delay
import androidx.compose.ui.composed
import kotlin.math.max
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.res.painterResource
import com.lyricsplus.android.R

private val AppBackground = Color(0xFF101010)
private val Accent = Color(0xFF4AD295)
private val Panel = Color(0xFF181A19)
private val Outline = Color(0x1FFFFFFF)
private val TextMuted = Color(0x998D9490)

@Composable
fun LyricsPlusApp(
    viewModel: MainViewModel,
    webController: LyricsWebController,
    onOpenSpotify: () -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    MaterialTheme {
        LyricsOverlay(
            state = uiState,
            webController = webController,
            viewModel = viewModel,
            onOpenSpotify = onOpenSpotify,
            onOpenNotificationAccess = onOpenNotificationAccess
        )
    }
}

@Composable
private fun LyricsOverlay(
    state: LyricsUiState,
    webController: LyricsWebController,
    viewModel: MainViewModel,
    onOpenSpotify: () -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isMultiPane = isLandscape || (configuration.screenWidthDp >= 600)

    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val isRightAligned = isMultiPane
    val prefs = remember(context) {
        context.getSharedPreferences("lyrics_plus_prefs", android.content.Context.MODE_PRIVATE)
    }

    LaunchedEffect(webController.isReady, state.nowPlaying) {
        webController.pushTrack(state.nowPlaying)
    }

    LaunchedEffect(webController.isReady, state.lyrics) {
        webController.pushLyrics(state.lyrics)
    }

    LaunchedEffect(webController.isReady, state.lyrics) {
        webController.pushPlayback(state.playback.currentPositionMs() + state.lyricsOffsetMs, state.playback.isPlaying)
    }

    // Throttle playback pushes to WebView: the JS renderer extrapolates position
    // via performance.now(), so we only need periodic sync updates (~150ms minimum interval)
    // to avoid overwhelming the JS bridge and competing with the rendering tick loop.
    // When paused, send one final push to sync the frozen position, then stop entirely
    // until playback resumes (WebView rAF is also stopped when paused).
    var lastPlaybackPushMs by remember { mutableStateOf(0L) }
    var hasPushedPaused by remember { mutableStateOf(false) }
    LaunchedEffect(webController.isReady, state.playback, state.lyricsOffsetMs) {
        if (!state.playback.isPlaying) {
            // Allow one final sync when pausing, then stop until playback resumes
            if (hasPushedPaused) return@LaunchedEffect
            hasPushedPaused = true
        } else {
            hasPushedPaused = false
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPlaybackPushMs
        if (elapsed < 150L) {
            delay(150L - elapsed)
        }
        lastPlaybackPushMs = SystemClock.elapsedRealtime()
        webController.pushPlayback(state.playback.currentPositionMs() + state.lyricsOffsetMs, state.playback.isPlaying)
    }

    LaunchedEffect(webController.isReady, state.readingMode) {
        webController.pushReadingMode(state.readingMode)
    }

    LaunchedEffect(webController.isReady, isRightAligned) {
        webController.pushRightAligned(isRightAligned)
    }

    LaunchedEffect(webController.isReady, state.inAppFontScale) {
        webController.pushInAppFontScale(state.inAppFontScale)
    }

    // Push safe area insets (system bars + display cutout) to the WebView
    // Use ViewCompat to get actual insets including display cutout (camera notch)
    val safeInsetConfig = remember(configuration) {
        configuration.orientation // trigger recomposition on rotation
    }
    LaunchedEffect(webController.isReady, safeInsetConfig) {
        val webView = webController.webView
        val density = webView.context.resources.displayMetrics.density

        // Try to get actual window insets via ViewCompat (includes display cutout)
        val windowInsets = androidx.core.view.ViewCompat.getRootWindowInsets(webView)
        if (windowInsets != null) {
            val typeMask = androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            val insets = windowInsets.getInsets(typeMask)
            val topDp = if (density > 0) (insets.top / density).toInt() else 28
            val rightDp = if (density > 0) (insets.right / density).toInt() else 0
            val bottomDp = if (density > 0) (insets.bottom / density).toInt() else 0
            val leftDp = if (density > 0) (insets.left / density).toInt() else 0
            webController.pushSafeInsets(topDp, rightDp, bottomDp, leftDp)
        } else {
            // Fallback: use system resource values
            val resources = webView.context.resources
            val statusBarResId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarPx = if (statusBarResId > 0) resources.getDimensionPixelSize(statusBarResId) else 0
            val statusBarDp = if (density > 0) (statusBarPx / density).toInt() else 28
            webController.pushSafeInsets(statusBarDp, 0, 0, 0)
        }
    }

    var isExpanded by remember { mutableStateOf(false) }
    var isButtonVisible by remember { mutableStateOf(false) }
    var playbackButtonsShowTrigger by remember { mutableStateOf(0) }
    var headerHeightPx by remember { mutableStateOf(0) }
    var showOverlay by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }

    LaunchedEffect(state.lyrics.isEmpty(), state.nowPlaying.track) {
        if (state.lyrics.isEmpty()) {
            showOverlay = false // Reset showOverlay immediately to prevent flashing stale states during cache lookup
            delay(150) // Delay by 150ms to allow local SQLite/in-memory cache to load first
            showOverlay = true
        } else {
            showOverlay = false
        }
    }

    LaunchedEffect(webController.isReady, isMultiPane, headerHeightPx) {
        if (isMultiPane) {
            webController.pushHeaderHeight(0)
        } else {
            webController.pushHeaderHeight(headerHeightPx)
        }
    }

    LaunchedEffect(webController.isReady, state.deviceUiMode, isMultiPane) {
        if (!isMultiPane && state.deviceUiMode == 0) {
            webController.webView.evaluateJavascript(
                """
                (function(){
                    var old = document.getElementById('phone-ui-lyrics-top');
                    if (old) old.remove();
                    var style = document.createElement('style');
                    style.id = 'phone-ui-lyrics-top';
                    style.textContent = '.stage:not(.right-aligned):not(.full-lyrics-mode) .lyrics { top: 7vh !important; }';
                    document.head.appendChild(style);
                })();
                """.trimIndent(),
                null
            )
        } else {
            webController.webView.evaluateJavascript(
                """
                (function(){
                    var old = document.getElementById('phone-ui-lyrics-top');
                    if (old) old.remove();
                })();
                """.trimIndent(),
                null
            )
        }
    }

    LaunchedEffect(isButtonVisible, isExpanded) {
        if (isButtonVisible && !isExpanded) {
            delay(4000)
            isButtonVisible = false
        }
    }

    LaunchedEffect(isExpanded) {
        if (!isExpanded) {
            isButtonVisible = false
        }
    }

    BackHandler(enabled = showAboutPage) {
        showAboutPage = false
    }

    DisposableEffect(showAboutPage) {
        val activity = view.context as? Activity
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val previousLightStatusBars = controller?.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller?.isAppearanceLightNavigationBars

        if (showAboutPage && controller != null) {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }

        onDispose {
            if (showAboutPage && controller != null) {
                previousLightStatusBars?.let { controller.isAppearanceLightStatusBars = it }
                previousLightNavigationBars?.let { controller.isAppearanceLightNavigationBars = it }
            }
        }
    }

    LaunchedEffect(playbackButtonsShowTrigger) {
        if (playbackButtonsShowTrigger > 0) {
            delay(4000)
            playbackButtonsShowTrigger = 0
        }
    }

    LaunchedEffect(state.nowPlaying.track) {
        if (state.nowPlaying.hasTrack) {
            playbackButtonsShowTrigger++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Host WebView natively inside Compose container
        AndroidView(
            factory = {
                val webView = webController.webView
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView
            },
            modifier = Modifier.fillMaxSize()
        )

        val hasTrack = state.nowPlaying.hasTrack
        val isInitializing = state.isInitializing
        if (!isInitializing && (!hasTrack || (state.lyrics.isEmpty() && showOverlay))) {
            EmptyOverlay(
                state = state,
                onOpenSpotify = onOpenSpotify,
                onOpenNotificationAccess = onOpenNotificationAccess,
                isMultiPane = isMultiPane,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(28.dp)
            )
        } else if (webController.debugMessage.startsWith("error:", ignoreCase = true)) {
            DebugOverlay(
                message = webController.debugMessage,
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(28.dp)
            )
        }

        if (webController.debugMessage.startsWith("error:", ignoreCase = true)) {
            DebugChip(
                message = webController.debugMessage,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(10.dp)
            )
        }

        // PREMIUM TOP INTEGRATED INFO & CONTROLLER HEADER BAR / LEFT SPLIT BAR
        if (state.nowPlaying.hasTrack && !webController.isFullLyricsMode) {
            if (isMultiPane) {
                // Split-Screen layout overlay
                Row(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Left Column: Album art + info as one centered visual group
                    Column(
                        modifier = Modifier
                            .weight(if (state.deviceUiMode == 0) 0.38f else 0.45f)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = if (state.deviceUiMode == 0) 20.dp else 24.dp, vertical = if (state.deviceUiMode == 0) 12.dp else 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AlbumArtView(
                            bitmap = state.nowPlaying.albumArt,
                            startColorHex = state.nowPlaying.backgroundStart,
                            endColorHex = state.nowPlaying.backgroundEnd,
                            modifier = Modifier.size(if (state.deviceUiMode == 0) 160.dp else 300.dp)
                        )

                        Spacer(modifier = Modifier.height(if (state.deviceUiMode == 0) 12.dp else 20.dp))

                        Text(
                            text = state.nowPlaying.track,
                            color = Color.White,
                            fontSize = if (state.deviceUiMode == 0) 16.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = if (state.deviceUiMode == 0) 20.sp else 24.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(if (state.deviceUiMode == 0) 3.dp else 4.dp))
                        Text(
                            text = state.nowPlaying.artist,
                            color = Color(0xB3FFFFFF),
                            fontSize = if (state.deviceUiMode == 0) 11.sp else 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = if (state.deviceUiMode == 0) 15.sp else 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(if (state.deviceUiMode == 0) 12.dp else 18.dp))

                        // Playback controls row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (state.deviceUiMode == 0) 16.dp else 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayPauseButton(
                                isPlaying = state.playback.isPlaying,
                                onClick = { viewModel.togglePlayback() }
                            )

                            Box(
                                modifier = Modifier
                                    .height(28.dp)
                                    .width(1.dp)
                                    .background(Color(0x26FFFFFF))
                            )

                            SkipNextButton(
                                onClick = { viewModel.skipToNext() }
                            )
                        }
                    }

                    // Right weight (WebView lyrics show and respond to touches underneath)
                    Spacer(modifier = Modifier.weight(0.55f))
                }
            } else {
                // Portrait Overlay Layout
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            if (playbackButtonsShowTrigger > 0) {
                                playbackButtonsShowTrigger = 0
                            } else {
                                playbackButtonsShowTrigger++
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                        .onGloballyPositioned { coordinates ->
                            val h = coordinates.size.height
                            if (h != headerHeightPx) {
                                headerHeightPx = h
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = state.nowPlaying.track,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 36.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.nowPlaying.artist,
                            color = Color(0xB3FFFFFF),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 24.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(20.dp))

                    AnimatedVisibility(
                        visible = playbackButtonsShowTrigger > 0 || !state.playback.isPlaying,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PlayPauseButton(
                                isPlaying = state.playback.isPlaying,
                                onClick = {
                                    playbackButtonsShowTrigger++
                                    viewModel.togglePlayback()
                                }
                            )

                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(Color(0x26FFFFFF))
                            )

                            SkipNextButton(
                                onClick = {
                                    playbackButtonsShowTrigger++
                                    viewModel.skipToNext()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) {
                        isExpanded = false
                    }
            )
        }

        // Invisible Touch Hotspot Box (Auto-show button when tapped)
        Box(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.BottomEnd)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    enabled = !isExpanded
                ) {
                    isButtonVisible = true
                }
        )

        val offsetSec = state.lyricsOffsetMs / 1000.0
        val offsetText = if (state.lyricsOffsetMs > 0) "+${offsetSec}s" else if (state.lyricsOffsetMs < 0) "${offsetSec}s" else "0.0s"

        // FLOATING CONTROLS MENU
        AnimatedVisibility(
            visible = isButtonVisible || isExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    if (isLandscape) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xEE161A18), RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    MenuActionRow(label = "立刻同步", emoji = "🔄") {
                                        viewModel.forceSyncTime()
                                    }
                                    MenuActionRow(label = "切换歌词源 [当前: ${state.activeLyricsSource}]", emoji = "🎵") {
                                        viewModel.switchLyricsSource()
                                    }
                                    LyricsOffsetAdjustRow(
                                        offsetText = offsetText,
                                        onAdvance = { viewModel.adjustOffset(-500) },
                                        onDelay = { viewModel.adjustOffset(500) },
                                        onReset = { viewModel.adjustOffset(-state.lyricsOffsetMs) }
                                    )
                                    MenuActionRow(label = "重新取色", emoji = "🎨") {
                                        viewModel.rotatePaletteColors()
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val readingModeLabel = when (state.readingMode) {
                                        0 -> "注音模式: 无"
                                        1 -> "注音模式: 罗马音"
                                        else -> "注音模式: 振假名"
                                    }
                                    MenuActionRow(
                                        label = readingModeLabel,
                                        emoji = "🔤",
                                        active = state.readingMode > 0
                                    ) {
                                        viewModel.cycleReadingMode()
                                    }
                                    MenuActionRow(
                                        label = if (state.keepScreenOn) "屏幕常亮: 开启" else "屏幕常亮: 关闭",
                                        emoji = "💡",
                                        active = state.keepScreenOn
                                    ) {
                                        viewModel.toggleKeepScreenOn()
                                    }
                                    MenuActionRow(
                                        label = if (state.deviceUiMode == 0) "UI模式: 手机" else "UI模式: Pad",
                                        emoji = "📱",
                                        active = state.deviceUiMode == 1
                                    ) {
                                        viewModel.toggleDeviceUiMode()
                                    }
                                    MenuActionRow(
                                        label = if (state.showFloatingLyrics) "桌面歌词: 开启" else "桌面歌词: 关闭",
                                        emoji = "📱",
                                        active = state.showFloatingLyrics
                                    ) {
                                        viewModel.toggleFloatingLyrics()
                                    }
                                    MenuActionRow(
                                        label = if (state.showSuperIslandLyrics) "小米超级岛歌词: 开启" else "小米超级岛歌词: 关闭",
                                        emoji = "🏝",
                                        active = state.showSuperIslandLyrics
                                    ) {
                                        viewModel.toggleSuperIslandLyrics()
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MenuActionRow(label = "立刻同步", emoji = "🔄") {
                                viewModel.forceSyncTime()
                            }
                            MenuActionRow(label = "切换歌词源 [当前: ${state.activeLyricsSource}]", emoji = "🎵") {
                                viewModel.switchLyricsSource()
                            }
                            LyricsOffsetAdjustRow(
                                offsetText = offsetText,
                                onAdvance = { viewModel.adjustOffset(-500) },
                                onDelay = { viewModel.adjustOffset(500) },
                                onReset = { viewModel.adjustOffset(-state.lyricsOffsetMs) }
                            )
                            MenuActionRow(label = "重新取色", emoji = "🎨") {
                                viewModel.rotatePaletteColors()
                            }
                             val readingModeLabel = when (state.readingMode) {
                                 0 -> "注音模式: 无"
                                 1 -> "注音模式: 罗马音"
                                 else -> "注音模式: 振假名"
                             }
                             MenuActionRow(
                                 label = readingModeLabel,
                                 emoji = "🔤",
                                 active = state.readingMode > 0
                             ) {
                                 viewModel.cycleReadingMode()
                             }
                            MenuActionRow(
                                label = if (state.deviceUiMode == 0) "UI模式: 手机" else "UI模式: Pad",
                                emoji = "📱",
                                active = state.deviceUiMode == 1
                            ) {
                                viewModel.toggleDeviceUiMode()
                            }
                            MenuActionRow(
                                label = if (state.keepScreenOn) "屏幕常亮: 开启" else "屏幕常亮: 关闭",
                                emoji = "💡",
                                active = state.keepScreenOn
                            ) {
                                viewModel.toggleKeepScreenOn()
                            }
                            MenuActionRow(
                                label = if (state.showFloatingLyrics) "桌面歌词: 开启" else "桌面歌词: 关闭",
                                emoji = "📱",
                                active = state.showFloatingLyrics
                            ) {
                                viewModel.toggleFloatingLyrics()
                            }
                            MenuActionRow(
                                label = if (state.showSuperIslandLyrics) "小米超级岛歌词: 开启" else "小米超级岛歌词: 关闭",
                                emoji = "🏝",
                                active = state.showSuperIslandLyrics
                            ) {
                                viewModel.toggleSuperIslandLyrics()
                            }
                            MenuActionRow(label = "关于项目", emoji = "ℹ") {
                                isExpanded = false
                                showAboutPage = true
                            }
                        }
                    }
                }

                // Main Toggle FAB Button (Minimized presence ghost button)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (isExpanded) Accent else Color(0x22FFFFFF), CircleShape)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { isExpanded = !isExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isExpanded) "✕" else "⚙",
                        color = if (isExpanded) Color.Black else Color(0x66FFFFFF),
                        fontSize = if (isExpanded) 16.sp else 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // BOTTOM LEFT FONT SCALE BUTTONS (visible when settings panel is open)
        AnimatedVisibility(
            visible = isExpanded && !showAboutPage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 24.dp, bottom = 24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Plus button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2E3230), CircleShape)
                        .border(1.dp, Color(0xFF454A47), CircleShape)
                        .noRippleClickable {
                            viewModel.adjustInAppFontScale(0.1f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Value indicator
                Box(
                    modifier = Modifier
                        .background(Color(0xFF161A18), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF2D3230), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${(state.inAppFontScale * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Minus button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF2E3230), CircleShape)
                        .border(1.dp, Color(0xFF454A47), CircleShape)
                        .noRippleClickable {
                            viewModel.adjustInAppFontScale(-0.1f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "—",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (showAboutPage) {
            AboutProjectPage(
                onCheckUpdates = { viewModel.checkForUpdates() },
                onOpenProject = {
                    uriHandler.openUri("https://github.com/Artriai/lyrics-plus-android")
                },
                onOpenFeedback = {
                    uriHandler.openUri("https://www.coolapk.com/u/2733246")
                },
                anonymousStatsAvailable = state.anonymousStatsAvailable,
                anonymousStatsEnabled = state.anonymousStatsEnabled,
                onToggleAnonymousStats = {
                    if (state.anonymousStatsAvailable) {
                        viewModel.toggleAnonymousStats()
                    } else {
                        android.widget.Toast.makeText(context, "当前构建未配置统计端点", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                autoCheckUpdatesEnabled = state.autoCheckUpdatesEnabled,
                onToggleAutoCheckUpdates = { viewModel.toggleAutoCheckUpdates() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DebugChip(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message.take(90),
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun DebugOverlay(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "歌词渲染调试",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 36.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color(0xFF8D9490),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyOverlay(
    state: LyricsUiState,
    onOpenSpotify: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    isMultiPane: Boolean = false,
    modifier: Modifier = Modifier
) {
    val hasTrack = state.nowPlaying.hasTrack
    val isLoading = state.isLoadingLyrics

    Box(modifier = modifier.fillMaxSize()) {
        if (isMultiPane) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left 45%
                Box(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
                    if (!hasTrack) {
                        // Welcome state: "Lyrics Plus" at top 1/4
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            Spacer(modifier = Modifier.weight(0.25f))
                            Text(
                                text = state.message,
                                color = Color.White,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 36.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "${state.playbackSource} - ${state.playback.positionMs / 1000}s",
                                color = Color(0xFF8D9490),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(22.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = onOpenSpotify,
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("打开 Spotify")
                                }
                                Button(
                                    onClick = onOpenNotificationAccess,
                                    colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("通知访问权限")
                                }
                            }
                            Spacer(modifier = Modifier.weight(0.75f))
                        }
                    }
                }

                // Right 55% — centered loading / no-lyrics / empty
                Box(
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasTrack && !isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "♪",
                                color = Color(0x66FFFFFF),
                                fontSize = 64.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "纯音乐 / 无歌词",
                                color = Color(0xB3FFFFFF),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${state.nowPlaying.track} - ${state.nowPlaying.artist}",
                                color = Color(0x66FFFFFF),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (hasTrack && isLoading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "正在搜索歌词...",
                                color = Color(0xB3FFFFFF),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${state.nowPlaying.track} - ${state.nowPlaying.artist}",
                                color = Color(0x66FFFFFF),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            // Portrait: full-screen centered layout
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (hasTrack && !isLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "♪",
                            color = Color(0x66FFFFFF),
                            fontSize = 64.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "纯音乐 / 无歌词",
                            color = Color(0xB3FFFFFF),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.nowPlaying.track} - ${state.nowPlaying.artist}",
                            color = Color(0x66FFFFFF),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (hasTrack && isLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "正在搜索歌词...",
                            color = Color(0xB3FFFFFF),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${state.nowPlaying.track} - ${state.nowPlaying.artist}",
                            color = Color(0x66FFFFFF),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = state.message,
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 36.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "${state.playbackSource} - ${state.playback.positionMs / 1000}s",
                            color = Color(0xFF8D9490),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(22.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onOpenSpotify,
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("打开 Spotify")
                            }
                            Button(
                                onClick = onOpenNotificationAccess,
                                colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("通知访问权限")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutProjectPage(
    onCheckUpdates: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenFeedback: () -> Unit,
    anonymousStatsAvailable: Boolean,
    anonymousStatsEnabled: Boolean,
    onToggleAnonymousStats: () -> Unit,
    autoCheckUpdatesEnabled: Boolean,
    onToggleAutoCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSponsorCodes by remember { mutableStateOf(false) }

    BackHandler(enabled = showSponsorCodes) {
        showSponsorCodes = false
    }

    Box(
        modifier = modifier
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "关于项目",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 38.sp
                )
            }

            AboutSection(title = "项目") {
                AboutActionItem(
                    title = "检测更新",
                    subtitle = "检查 GitHub Release 是否有新版本",
                    onClick = onCheckUpdates
                )
                AboutDivider()
                AboutSwitchItem(
                    title = "自动检测更新",
                    subtitle = if (autoCheckUpdatesEnabled) {
                        "启动 App 时静默检查，有新版本才提示"
                    } else {
                        "关闭后只保留手动检测"
                    },
                    checked = autoCheckUpdatesEnabled,
                    enabled = true,
                    onClick = onToggleAutoCheckUpdates
                )
                AboutDivider()
                AboutActionItem(
                    title = "项目地址",
                    subtitle = "打开 GitHub 项目主页，欢迎 Star",
                    onClick = onOpenProject
                )
                AboutDivider()
                AboutActionItem(
                    title = "反馈问题",
                    subtitle = "打开作者酷安主页",
                    onClick = onOpenFeedback
                )
            }

            AboutSection(title = "隐私") {
                AboutSwitchItem(
                    title = when {
                        !anonymousStatsAvailable -> "匿名统计未配置"
                        anonymousStatsEnabled -> "匿名统计已开启"
                        else -> "匿名统计已关闭"
                    },
                    subtitle = if (anonymousStatsAvailable) {
                        "仅统计活跃用户、版本分布和功能使用摘要，不收集歌曲名或账号"
                    } else {
                        "当前构建未配置统计端点，不会发送统计事件"
                    },
                    checked = anonymousStatsAvailable && anonymousStatsEnabled,
                    enabled = anonymousStatsAvailable,
                    onClick = onToggleAnonymousStats
                )
            }

            AboutSection(title = "支持") {
                AboutActionItem(
                    title = "赞助说明",
                    subtitle = if (showSponsorCodes) {
                        "收起支付宝 / 微信支付二维码"
                    } else {
                        "如果本项目帮助了你，可以给作者买一杯咖啡"
                    },
                    onClick = { showSponsorCodes = !showSponsorCodes }
                )
                AnimatedVisibility(
                    visible = showSponsorCodes,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        AboutDivider()
                        SponsorQrCodes()
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun AboutActionItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    AboutListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Text(
                text = "›",
                color = Color(0x99FFFFFF),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    )
}

@Composable
private fun AboutSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    AboutListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        enabled = enabled,
        trailing = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { onClick() }
            )
        }
    )
}

@Composable
private fun SponsorQrCodes() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SponsorQrImage(
            title = "支付宝",
            resId = R.drawable.sponsor_alipay
        )
        SponsorQrImage(
            title = "微信支付",
            resId = R.drawable.sponsor_wechat
        )
    }
}

@Composable
private fun SponsorQrImage(
    title: String,
    resId: Int
) {
    Column(
        modifier = Modifier.widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Image(
            painter = painterResource(id = resId),
            contentDescription = "$title 支付二维码",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Outline, RoundedCornerShape(24.dp))
        )
    }
}

@Composable
private fun AboutListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = if (enabled) Color.White else Color(0xB3FFFFFF),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        color = Outline,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun LyricsOffsetAdjustRow(
    offsetText: String,
    onAdvance: () -> Unit,
    onDelay: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xD9101211), RoundedCornerShape(6.dp))
                .noRippleClickable { onAdvance() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("歌词提前", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .background(Color(0xD9101211), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(offsetText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .background(Color(0xD9101211), RoundedCornerShape(6.dp))
                .noRippleClickable { onDelay() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("歌词延后", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .background(Color(0xD9323634), CircleShape)
                .noRippleClickable { onReset() },
            contentAlignment = Alignment.Center
        ) {
            Text("⏰", fontSize = 18.sp)
        }
    }
}

@Composable
private fun MenuActionRow(
    label: String,
    emoji: String,
    active: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
        ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xD9101211), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .background(if (active) Color(0xD9323634) else Color(0x66323634), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isPlaying) {
            Canvas(modifier = Modifier.size(32.dp)) {
                val barWidth = size.width * 0.18f
                val gap = size.width * 0.22f
                val h = size.height * 0.55f
                val startY = size.height * 0.225f
                
                // Left bar
                drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.26f, startY),
                    size = Size(barWidth, h)
                )
                // Right bar
                drawRect(
                    color = Color.White,
                    topLeft = Offset(size.width * 0.26f + barWidth + gap, startY),
                    size = Size(barWidth, h)
                )
            }
        } else {
            Canvas(modifier = Modifier.size(32.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.22f)
                    lineTo(size.width * 0.82f, size.height * 0.5f)
                    lineTo(size.width * 0.32f, size.height * 0.78f)
                    close()
                }
                drawPath(path, color = Color.White)
            }
        }
    }
}

@Composable
private fun SkipNextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.24f)
                lineTo(size.width * 0.64f, size.height * 0.5f)
                lineTo(size.width * 0.22f, size.height * 0.76f)
                close()
            }
            drawPath(path, color = Color.White)
            
            drawRect(
                color = Color.White,
                topLeft = Offset(size.width * 0.69f, size.height * 0.24f),
                size = Size(size.width * 0.12f, size.height * 0.52f)
            )
        }
    }
}

@Composable
private fun AlbumArtView(
    bitmap: android.graphics.Bitmap?,
    startColorHex: String?,
    endColorHex: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
            .background(Color(0xFF181A19), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Album Art",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            val startColor = startColorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF2C3E50)
            val endColor = endColorHex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF101010)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(startColor, endColor)
                        ),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎵",
                    fontSize = 42.sp
                )
            }
        }
    }
}
