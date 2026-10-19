package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import org.junit.Assert.*
import org.junit.Test

class SyncedLyricsQualityTest {
    @Test fun rejectsQqCreditsOnlyEvenWhenTheyHaveWordTiming() {
        val lines = listOf(
            LyricsLine(0, "特(0,100)别(100,100)的人 - 方大同"),
            LyricsLine(1000, "词(1000,100)：方大同"),
            LyricsLine(2000, "混音师：Someone"),
            LyricsLine(3000, "Lyrics by：Someone"),
            LyricsLine(4000, "Produced by：Someone"),
            LyricsLine(5000, "♪")
        )
        assertTrue(SyncedLyricsQuality.usable(lines, "特别的人", 259.0).isEmpty())
        assertTrue(SyncedLyricsQuality.usable(lines, "特别的人", 0.0).isEmpty())
    }

    @Test fun rejectsClearlyTruncatedPreviewAndAcceptsFullLrcFallback() {
        val preview = listOf(LyricsLine(0, "Song - Artist"), LyricsLine(10000, "a(10000,300) lyric"))
        assertTrue(SyncedLyricsQuality.usable(preview, "Song", 200.0).isEmpty())
        val complete = listOf(LyricsLine(0, "Song - Artist"), LyricsLine(10000, "a lyric"), LyricsLine(180000, "ending"))
        assertEquals(2, SyncedLyricsQuality.usable(complete, "Song", 200.0).size)
    }

    @Test fun supportsShortSongsAndUnknownDurationsWithoutGuessingCompleteness() {
        val lines = listOf(LyricsLine(1000, "one line(1000,1000)"))
        assertEquals(lines, SyncedLyricsQuality.usable(lines, "Song", 20.0))
        assertEquals(lines, SyncedLyricsQuality.usable(lines, "Song", 0.0))
        assertEquals(lines, SyncedLyricsQuality.usable(lines, "Song", Double.NaN))
    }

    @Test fun enhancedLrcCanContainTheFullSongInOneLongLine() {
        val lines = listOf(LyricsLine(0, "<00:01.000>start <03:00.000>ending"))
        assertEquals(lines, SyncedLyricsQuality.usable(lines, "Song", 200.0))
    }

    @Test fun rejectsOldCreditsOnlyCacheBeforeItsWordBonusCanBlockACompleteResult() {
        val entry = LyricsCacheEntry(CachedLyricsResult(listOf(
            LyricsLine(0, "Song - Artist"), LyricsLine(1000, "Lyrics by：Someone")
        ), "QQ音乐", 100), 123)
        assertNull(SyncedLyricsQuality.cache(entry, "Song", 200.0))
        val full = entry.copy(result = entry.result.copy(lyrics = listOf(LyricsLine(180000, "actual ending"))))
        assertEquals(full, SyncedLyricsQuality.cache(full, "Song", 200.0))
        val instrumental = entry.copy(result = entry.result.copy(lyrics = listOf(LyricsLine(0, "♪ 纯音乐 ♪"))))
        assertEquals(instrumental, SyncedLyricsQuality.cache(instrumental, "Song", 200.0))
    }
}
