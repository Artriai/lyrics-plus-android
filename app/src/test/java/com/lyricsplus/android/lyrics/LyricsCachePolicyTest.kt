package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import org.junit.Assert.*
import org.junit.Test

class LyricsCachePolicyTest {
    private val now = 1_000_000_000L
    private val result = CachedLyricsResult(listOf(LyricsLine(1000, "hello(1000,500)")), "QQ音乐", 85)

    @Test fun successfulCheckIsReusedForOneDayWithoutExpiringTheLyrics() {
        val entry = LyricsCacheEntry(result, now - 1000, now, true)
        assertFalse(LyricsCachePolicy.shouldRefresh(entry, now + LyricsCachePolicy.REFRESH_INTERVAL_MS - 1))
        assertTrue(LyricsCachePolicy.shouldRefresh(entry, now + LyricsCachePolicy.REFRESH_INTERVAL_MS))
    }

    @Test fun failuresAndUnscoredLegacyEntriesRetryAfterFiveMinutes() {
        for (entry in listOf(
            LyricsCacheEntry(result, now, now, false),
            LyricsCacheEntry(result.copy(score = null), now, now, true)
        )) {
            assertFalse(LyricsCachePolicy.shouldRefresh(entry, now + LyricsCachePolicy.RETRY_INTERVAL_MS - 1))
            assertTrue(LyricsCachePolicy.shouldRefresh(entry, now + LyricsCachePolicy.RETRY_INTERVAL_MS))
        }
        assertTrue(LyricsCachePolicy.shouldRefresh(LyricsCacheEntry(result, now), now))
    }

    @Test fun clockMovingBackCannotSuppressRefreshIndefinitely() {
        val entry = LyricsCacheEntry(result, now, now, true)
        assertTrue(LyricsCachePolicy.shouldRefresh(entry, now - 1))
        assertFalse(LyricsCachePolicy.canReuseSource(entry, now - 1))
    }

    @Test fun sourceReuseRequiresKnownScoreAndFreshContent() {
        val entry = LyricsCacheEntry(result, now)
        assertTrue(LyricsCachePolicy.canReuseSource(entry, now))
        assertFalse(LyricsCachePolicy.canReuseSource(entry, now + LyricsCachePolicy.REFRESH_INTERVAL_MS))
        assertFalse(LyricsCachePolicy.canReuseSource(entry.copy(result = result.copy(score = null)), now))
    }

    @Test fun wordBonusIsRecomputedFromContentAndNeverPersistedTwice() {
        assertEquals(185, LyricsMatching.selectionScore(result.score, result.lyrics))
        assertEquals(185, LyricsMatching.selectionScore(result.copy().score, result.copy().lyrics))
        assertEquals(100, LyricsMatching.selectionScore(null, result.lyrics))
        assertEquals(0, LyricsMatching.selectionScore(null, listOf(LyricsLine(1000, "plain"))))
    }
}
