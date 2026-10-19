package com.lyricsplus.android.lyrics

import org.junit.Assert.*
import org.junit.Test

class ArtistAliasesTest {
    @Test fun officialQqDisplayNameResolvesSayuriWithoutRemovingArtistValidation() {
        val aliases = ArtistAliases.credits(listOf(ArtistAliases.names("さユり", "さユり (Sayuri)")))
        val actual = SongMetadata("花の塔", "さユり", artistAliases = aliases)
        assertEquals(100, LyricsMatching.score(SongMetadata("花の塔", "Sayuri"), actual))
        assertNull(LyricsMatching.score(SongMetadata("花の塔", "Other Artist"), actual))
    }

    @Test fun officialNeteaseAliasesResolveLocalizedArtistNames() {
        val aliases = ArtistAliases.credits(listOf(ArtistAliases.names("さユり", aliases = listOf("酸欠少女さユり", "Sayuri", "YELLOW"))))
        val actual = SongMetadata("花の塔", "さユり", artistAliases = aliases)
        assertEquals(100, LyricsMatching.score(SongMetadata("花の塔", "Sayuri"), actual))
        assertNull(LyricsMatching.score(SongMetadata("Other Song", "Sayuri"), actual))
    }

    @Test fun aliasesPreserveAllDuetMembersAndNeverInventRomanizations() {
        val aliases = ArtistAliases.credits(listOf(
            ArtistAliases.names("さユり", aliases = listOf("Sayuri")),
            ArtistAliases.names("歌手乙", aliases = listOf("Guest"))
        ))
        val actual = SongMetadata("Song", "さユり 歌手乙", artistAliases = aliases)
        assertEquals(100, LyricsMatching.score(SongMetadata("Song", "Sayuri Guest"), actual))
        assertTrue(LyricsMatching.score(SongMetadata("Song", "Sayuri"), actual) != 100)
        assertNull(LyricsMatching.score(SongMetadata("Song", "Utashu"), actual))
        assertTrue(ArtistAliases.credits(listOf(emptyList())).isEmpty())
        assertTrue(ArtistAliases.credits(emptyList()).isEmpty())
    }

    @Test fun collaborationOrderUsesEveryCreditedArtistExactlyOnce() {
        val credits = listOf(listOf("A2O LITE"), listOf("A2O"))
        val actual = SongMetadata("NEON", "A2O LITE A2O", artistCredits = credits)
        assertEquals(100, LyricsMatching.score(SongMetadata("NEON", "A2O & A2O LITE"), actual))
        assertTrue(LyricsMatching.score(SongMetadata("NEON", "A2O & A2O"), actual) != 100)
        assertNull(LyricsMatching.score(SongMetadata("NEON", "Other & A2O"), actual))
    }

    @Test fun descriptiveAliasCannotPretendToBeTheOriginalArtist() {
        val actual = SongMetadata("搁浅", "沈幼楚", artistAliases = listOf("周杰伦的迷妹"))
        assertNull(LyricsMatching.score(SongMetadata("搁浅", "周杰伦"), actual))
    }
}
