package com.lyricsplus.android.spotify

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.PlaybackAnchor
import com.lyricsplus.android.lyrics.LyricsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class LyricsNotificationListenerService : NotificationListenerService() {
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshFromActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (acceptsMediaNotification(sbn)) {
            val snapshot = sbn.toSpotifySnapshot()
            latestSnapshot = snapshot
            
            val track = snapshot.nowPlaying
            if (track != null && track.hasTrack) {
                triggerBackgroundPrefetch(track)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (acceptsMediaNotification(sbn)) {
            refreshFromActiveNotifications()
        }
    }

    private var prefetchKey: String? = null
    private var prefetchJob: Job? = null

    private fun triggerBackgroundPrefetch(track: NowPlaying) {
        val key = listOf(track.mediaPackage, track.track, track.artist, track.album).joinToString("|")
        if (prefetchKey == key) return
        prefetchKey = key
        prefetchJob?.cancel()
        val provider = LyricsProvider.getInstance(applicationContext)
        val session = provider.beginPlayback(track)
        prefetchJob = serviceScope.launch {
            provider.findSyncedLyrics(track, session = session)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun refreshFromActiveNotifications() {
        latestSnapshot = activeNotifications
            ?.filter(::acceptsMediaNotification)
            ?.maxByOrNull { sbn ->
                val controller = sbn.notification.mediaSessionToken()
                    ?.let { MediaController(this@LyricsNotificationListenerService, it) }
                when (controller?.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> 3
                    PlaybackState.STATE_BUFFERING,
                    PlaybackState.STATE_CONNECTING -> 2
                    PlaybackState.STATE_PAUSED -> 1
                    else -> 0
                }
            }
            ?.toSpotifySnapshot()
        latestSnapshot?.nowPlaying?.takeIf { it.hasTrack }?.let(::triggerBackgroundPrefetch)
        if (latestSnapshot == null) {
            prefetchJob?.cancel()
            prefetchKey = null
        }
    }

    private fun acceptsMediaNotification(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == SpotifyBroadcasts.PACKAGE_NAME
    }

    private fun StatusBarNotification.toSpotifySnapshot(): SpotifyMediaSnapshot {
        val token = notification.mediaSessionToken()
        val controller = token?.let { MediaController(this@LyricsNotificationListenerService, it) }
        val extras = notification.extras

        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val palette = AlbumArtPaletteExtractor.fromBitmap(bitmap)

        return SpotifyMediaSnapshot(
            nowPlaying = if (title.isNotBlank() || artist.isNotBlank()) {
                NowPlaying(
                    track = title,
                    artist = artist,
                    album = album,
                    mediaPackage = packageName,
                    durationSeconds = ((durationMs + 500) / 1000).toInt(),
                    backgroundStart = palette?.start,
                    backgroundEnd = palette?.end,
                    backgroundAccent = palette?.accent,
                    albumArt = bitmap
                )
            } else {
                null
            },
            playback = controller?.playbackState?.toPlaybackAnchor(),
            source = "notification-token"
        )
    }

    @Suppress("DEPRECATION")
    private fun Notification.mediaSessionToken(): MediaSession.Token? =
        extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)

    private fun PlaybackState.toPlaybackAnchor(): PlaybackAnchor {
        val capturedElapsedMs = SystemClock.elapsedRealtime()
        
        // Use a safe lastPositionUpdateTime. Some devices report 0 initially.
        val lastUpdate = if (lastPositionUpdateTime > 0) lastPositionUpdateTime else capturedElapsedMs

        val positionMs = if (state == PlaybackState.STATE_PLAYING) {
            position + ((capturedElapsedMs - lastUpdate) * playbackSpeed).toLong()
        } else {
            position
        }

        val isAccurate = !(state == PlaybackState.STATE_PLAYING && lastPositionUpdateTime <= 0)

        return PlaybackAnchor(
            isPlaying = state == PlaybackState.STATE_PLAYING,
            positionMs = positionMs.coerceAtLeast(0),
            capturedElapsedMs = capturedElapsedMs,
            isAccurate = isAccurate
        )
    }

    companion object {
        private val _snapshotFlow = MutableStateFlow<SpotifyMediaSnapshot?>(null)
        val snapshotFlow: StateFlow<SpotifyMediaSnapshot?> = _snapshotFlow.asStateFlow()

        var latestSnapshot: SpotifyMediaSnapshot?
            get() = _snapshotFlow.value
            set(value) {
                _snapshotFlow.value = value
            }
    }
}
