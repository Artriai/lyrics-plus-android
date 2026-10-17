package com.lyricsplus.android.lyrics

/** Identity distinguishes A -> B -> A and rejects delayed callers from an old playback. */
class LyricsPlaybackSession internal constructor(internal val key: String) {
    internal var manualSelection = false
}
