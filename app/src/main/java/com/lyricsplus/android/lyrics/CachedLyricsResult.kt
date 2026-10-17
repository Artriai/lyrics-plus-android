package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine

data class CachedLyricsResult(
    val lyrics: List<LyricsLine>,
    val source: String,
    // Old databases did not record a match score. Unknown is never treated as 100.
    val score: Int? = null
)

internal data class LyricsCacheEntry(
    val result: CachedLyricsResult,
    val cachedAt: Long
)
