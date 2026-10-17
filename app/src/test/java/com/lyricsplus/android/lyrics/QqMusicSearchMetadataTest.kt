package com.lyricsplus.android.lyrics

import org.junit.Assert.*
import org.junit.Test

class QqMusicSearchMetadataTest {
    @Test
    fun removesLocalizedAliasesButRetainsVersionInformation() {
        assertEquals("スパークル (Movie ver.)", QqMusicSearchMetadata.title("スパークル (火花) (Movie ver.)"))
        assertEquals("スパークル (Original ver.)", QqMusicSearchMetadata.title("スパークル (火花) (Original ver.)"))
        assertEquals("Song (Live) (现场版)", QqMusicSearchMetadata.title("Song (Live) (现场版)"))
        assertEquals("Song (黑胶版)", QqMusicSearchMetadata.title("Song (黑胶版)"))
    }

    @Test
    fun preservesEnglishVersionsAndMeaningfulParentheticalSubtitles() {
        assertEquals("Song (Acoustic)", QqMusicSearchMetadata.title("Song (Acoustic)"))
        assertEquals("Song (Part Two)", QqMusicSearchMetadata.title("Song (Part Two)"))
        assertEquals("Hotel California (2013 Remaster)", QqMusicSearchMetadata.title("Hotel California (2013 Remaster)"))
    }

    @Test
    fun fullSearchMetadataSelectsTheSoundtrackVersionEvenWithADifferentAlbumLanguage() {
        val expected = SongMetadata("スパークル - movie ver.", "RADWIMPS", "Your Name.", 535.0)
        fun candidate(id: String, title: String, album: String, seconds: Double) = LyricsCandidate(
            id, SongMetadata(QqMusicSearchMetadata.title(title), "RADWIMPS (ラッドウィンプス)", album, seconds), id
        )
        // Order and fields reflect the actual QQ full-search response.
        val songs = listOf(
            candidate("short", "スパークル (Movie Ver.)", "スパークル(Movie Ver.)(单曲)", 385.0),
            candidate("vinyl", "スパークル (movie ver.) (黑胶版)", "君の名は。（黑胶版）", 523.0),
            candidate("soundtrack", "スパークル (火花) (Movie ver.)", "君の名は。 (《你的名字。》动画电影原声带)", 534.0),
            candidate("original", "スパークル (火花) (Original ver.)", "人間開花", 408.0)
        )
        val selected = songs.mapNotNull { song -> LyricsMatching.score(expected, song.song)?.let { song.id to it } }
            .maxByOrNull { it.second }
        assertEquals("soundtrack", selected?.first)
        assertNull(LyricsMatching.score(expected, songs.last().song))
    }
}
