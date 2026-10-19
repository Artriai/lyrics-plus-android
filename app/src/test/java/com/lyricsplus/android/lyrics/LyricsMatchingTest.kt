package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import org.junit.Assert.*
import org.junit.Test

class LyricsMatchingTest {
    private val expected = SongMetadata("Hotel California - 2013 Remaster", "Eagles", "Hotel California", 391.0)

    @Test
    fun missingOptionalQqFieldsDoNotReduceTheScore() {
        val qq = SongMetadata("Hotel California", "Eagles")
        val full = qq.copy(album = "Hotel California", durationSeconds = 391.0)
        assertEquals(100, LyricsMatching.score(expected, qq))
        assertEquals(LyricsMatching.score(expected, qq), LyricsMatching.score(expected, full))
    }

    @Test
    fun normalizesCasePunctuationAndFullWidthCharacters() {
        assertEquals(100, LyricsMatching.score(
            SongMetadata("Ｓｏｎｇ：Ａ", "ARTIST"), SongMetadata("song a", "Artist")
        ))
    }

    @Test
    fun onlyRemasterAnnotationsAreRemovedFromSearchTitles() {
        assertEquals(listOf(expected.title, "Hotel California"), LyricsMatching.searchTitles(expected.title))
        assertEquals(listOf("Song (Remastered 2013)", "Song"), LyricsMatching.searchTitles("Song (Remastered 2013)"))
        assertEquals(listOf("Song [2013 Remastered]", "Song"), LyricsMatching.searchTitles("Song [2013 Remastered]"))
        for (title in listOf("Song - Live", "Song (Remix)", "Song (Acoustic)", "Love - Hate")) {
            assertEquals(listOf(title), LyricsMatching.searchTitles(title))
        }
    }

    @Test
    fun versionDifferencesAreNotFullTitleMatches() {
        val studio = SongMetadata("Song", "Artist")
        assertNull(LyricsMatching.score(studio, studio.copy(title = "Song - Live")))
    }

    @Test
    fun featuringCreditsHaveACommonFallbackWithoutRemovingOrdinarySubtitles() {
        assertEquals(listOf("Song (feat. Guest)", "Song"), LyricsMatching.searchTitles("Song (feat. Guest)"))
        assertEquals(listOf("Song feat. Guest", "Song"), LyricsMatching.searchTitles("Song feat. Guest"))
        assertEquals(listOf("With You"), LyricsMatching.searchTitles("With You"))
        assertEquals(listOf("Song - Subtitle"), LyricsMatching.searchTitles("Song - Subtitle"))
    }

    @Test
    fun artistOrAlbumAloneCannotRescueAnUnrelatedSong() {
        assertNull(LyricsMatching.score(expected, expected.copy(title = "Desperado")))
        assertNull(LyricsMatching.score(expected, expected.copy(artist = "Another Artist")))
        assertNull(LyricsMatching.score(expected, expected.copy(title = "")))
        assertNull(LyricsMatching.score(expected, expected.copy(artist = "")))
    }

    @Test
    fun knownMetadataDifferencesReduceConfidenceButUnknownDurationDoesNot() {
        val same = expected.copy(title = "Hotel California")
        assertNull(LyricsMatching.score(expected, same.copy(durationSeconds = 500.0)))
        assertTrue(LyricsMatching.score(expected, same.copy(album = "Live Album"))!! < 100)
        assertEquals(100, LyricsMatching.score(expected, same.copy(durationSeconds = 0.0)))
        assertEquals(100, LyricsMatching.score(expected, same.copy(durationSeconds = Double.NaN)))
    }

    @Test
    fun qrcYrcAndEnhancedLrcAllReceiveTheFullHundredPointBonus() {
        val plain = listOf(LyricsLine(1000, "test lyrics"))
        assertEquals(100, LyricsMatching.selectionScore(100, plain))
        for (text in listOf("test(1000,500)", "test(1000,500,0)", "<00:01.000>test")) {
            assertEquals(180, LyricsMatching.selectionScore(80, listOf(LyricsLine(1000, text))))
        }
    }

    @Test
    fun qqWordTimingCanBeatNeteaseLineTimingEvenWithALowerMatchScore() {
        val qqScore = LyricsMatching.selectionScore(80, listOf(LyricsLine(1000, "test(1000,500)")))
        val neteaseScore = LyricsMatching.selectionScore(100, listOf(LyricsLine(1000, "test")))
        assertTrue(qqScore > neteaseScore)
    }
    @Test fun unknownCacheScoreIsNotInventedAndWordBonusIsStillPreserved() {
        assertEquals(0, LyricsMatching.selectionScore(null, listOf(LyricsLine(1000, "plain"))))
        assertEquals(100, LyricsMatching.selectionScore(null, listOf(LyricsLine(1000, "hello(1000,500)"))))
    }


    @Test fun rejectsAccompanimentClipsAndIncompatibleVersionsBeforeTimingBonus() {
        val expected = SongMetadata("说好不哭", "周杰伦", durationSeconds = 222.0)
        for (title in listOf("说好不哭 (伴奏)", "说好不哭 (DJ Remix)", "说好不哭 (Live)")) {
            assertNull(LyricsMatching.score(expected, expected.copy(title = title)))
        }
        assertNull(LyricsMatching.score(expected, expected.copy(durationSeconds = 84.0)))
        assertEquals(100, LyricsMatching.score(expected, expected.copy(durationSeconds = 221.0)))
        val live = expected.copy(title = "说好不哭 - Live")
        assertEquals(100, LyricsMatching.score(live, live.copy(title = "说好不哭 (Live)")))
        assertEquals(100, LyricsMatching.score(SongMetadata("Live Forever", "Artist"), SongMetadata("Live Forever", "Artist")))
    }

    @Test fun exactButMisleadingArtistAliasesDoNotRescueShortClips() {
        val actual = SongMetadata("稻香", "何兵兵", durationSeconds = 121.599, artistAliases = listOf("周杰伦"))
        assertNull(LyricsMatching.score(SongMetadata("稻香", "周杰伦", durationSeconds = 223.0), actual))
    }

    @Test fun featuredGuestNamesAreNotRecordingVersionLabels() {
        assertEquals(100, LyricsMatching.score(
            SongMetadata("Song (feat. DJ Guest)", "Artist"), SongMetadata("Song", "Artist")
        ))
        assertNull(LyricsMatching.score(SongMetadata("Song", "!!!"),
            SongMetadata("Song", "?", artistAliases = listOf("???"))))
    }
}
