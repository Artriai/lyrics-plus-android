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
    val cachedAt: Long,
    val checkedAt: Long = 0L,
    val checkSucceeded: Boolean = false
)

internal object LyricsCachePolicy {
    const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
    const val RETRY_INTERVAL_MS = 5 * 60 * 1000L

    fun shouldRefresh(entry: LyricsCacheEntry, now: Long): Boolean {
        val interval = if (entry.checkSucceeded && entry.result.score != null) {
            REFRESH_INTERVAL_MS
        } else RETRY_INTERVAL_MS
        return entry.checkedAt <= 0L || now < entry.checkedAt || now - entry.checkedAt >= interval
    }

    fun canReuseSource(entry: LyricsCacheEntry, now: Long): Boolean =
        entry.result.score != null && entry.cachedAt > 0L &&
            now >= entry.cachedAt && now - entry.cachedAt < REFRESH_INTERVAL_MS
}
