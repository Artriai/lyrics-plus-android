package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LyricsSearchTest {
    private val expected = SongMetadata("Hotel California - 2013 Remaster", "Eagles")
    private val candidate = LyricsCandidate("1", SongMetadata("Hotel California", "Eagles"), 1)
    private val lyrics = listOf(LyricsLine(1000, "test(1000,500)"))

    @Test
    fun plansOriginalThenCleanedThenTitleOnlySearchAndDeduplicates() {
        assertEquals(listOf(
            LyricsSearchQuery(expected.title, "Eagles"),
            LyricsSearchQuery("Hotel California", "Eagles"),
            LyricsSearchQuery("Hotel California", "")
        ), LyricsSearch.queries(expected))
        assertEquals(2, LyricsSearch.queries(candidate.song).size)
        assertEquals(1, LyricsSearch.queries(candidate.song.copy(artist = "")).size)
    }

    @Test
    fun allSourcesUseTheSameRemasterFallbackAndScore() {
        for (source in listOf("QQ 音乐", "网易云音乐", "LRCLIB")) {
            val queries = mutableListOf<LyricsSearchQuery>()
            val result = LyricsSearch.find(source, expected, { query ->
                queries.add(query)
                if (query.title == expected.title) emptyList() else listOf(candidate)
            }, { lyrics })
            assertEquals(2, queries.size)
            assertEquals(100, result.score)
            assertEquals(lyrics, result.lyrics)
        }
    }

    @Test
    fun titleOnlyFallbackStillChecksTheOriginalArtist() {
        val fetched = mutableListOf<Int>()
        val wrong = candidate.copy(id = "2", song = candidate.song.copy(artist = "Another Artist"), value = 2)
        val result = LyricsSearch.find("QQ 音乐", expected, { query ->
            if (query.artist.isNotBlank()) emptyList() else listOf(wrong, candidate)
        }, { fetched.add(it.value); lyrics })
        assertEquals(listOf(1), fetched)
        assertEquals(100, result.score)
    }

    @Test
    fun triesNextCandidateWhenBestHasNoLyrics() {
        val second = candidate.copy(id = "2", value = 2)
        val fetched = mutableListOf<Int>()
        val result = LyricsSearch.find("网易云音乐", expected, { listOf(candidate, second) }, {
            fetched.add(it.value)
            if (it.value == 1) emptyList() else lyrics
        })
        assertEquals(listOf(1, 2), fetched)
        assertEquals(lyrics, result.lyrics)
    }

    @Test
    fun ranksBeforeFetchingAndRejectsUnrelatedCandidates() {
        val partial = candidate.copy(id = "2", song = candidate.song.copy(title = "Hotel California (Movie ver.)"))
        val wrong = candidate.copy(id = "3", song = candidate.song.copy(title = "Another Song"))
        val fetched = mutableListOf<String>()
        LyricsSearch.find("LRCLIB", expected, { listOf(wrong, partial, candidate) }, {
            fetched.add(it.id); lyrics
        })
        assertEquals(listOf("1"), fetched)
    }

    @Test
    fun limitsLyricAttemptsAndDoesNotFetchDuplicateIds() {
        val fetched = mutableListOf<String>()
        val error = assertThrows(LyricsSearchException::class.java) {
            LyricsSearch.find("QQ 音乐", expected, {
                listOf(candidate, candidate) + (2..6).map { candidate.copy(id = it.toString()) }
            }, { fetched.add(it.id); emptyList() })
        }
        assertEquals(listOf("1", "2", "3"), fetched)
        assertEquals(LyricsSearchFailure.NO_SYNCED_LYRICS, error.reason)
    }

    @Test
    fun retriesDuplicateResultsOnlyOnceAcrossQueries() {
        var fetched = 0
        assertThrows(LyricsSearchException::class.java) {
            LyricsSearch.find("QQ 音乐", expected, { listOf(candidate) }, { fetched++; emptyList() })
        }
        assertEquals(1, fetched)
    }

    @Test
    fun distinguishesNoMatchFromNoLyrics() {
        val error = assertThrows(LyricsSearchException::class.java) {
            LyricsSearch.find<Int>("QQ 音乐", expected, { emptyList() }, { lyrics })
        }
        assertEquals(LyricsSearchFailure.NO_MATCH, error.reason)
        assertEquals("未找到匹配歌曲", LyricsSearch.failureMessage(error, showDetails = true))
        assertEquals("QQ音乐 (未匹配)", LyricsSearch.failureSource("QQ音乐", error, showDetails = true))
    }

    @Test
    fun serviceErrorsAreNotReportedAsInstrumentalOrNoLyrics() {
        val error = IOException("HTTP 503")
        val actual = assertThrows(IOException::class.java) {
            LyricsSearch.find("QQ 音乐", expected, { listOf(candidate) }, { throw error })
        }
        assertSame(error, actual)
        assertEquals("QQ音乐 (请求失败)", LyricsSearch.failureSource("QQ音乐", error, showDetails = true))
        assertEquals("歌词服务请求失败，请稍后重试", LyricsSearch.failureMessage(error, showDetails = true))
    }

    @Test
    fun releaseMessagesHideEveryFailureCategory() {
        val errors = listOf(
            LyricsSearchException(LyricsSearchFailure.NO_MATCH, "QQ 音乐"),
            LyricsSearchException(LyricsSearchFailure.NO_SYNCED_LYRICS, "网易云音乐"),
            IOException("HTTP 503: internal request details"),
            IllegalArgumentException("invalid encrypted lyrics"),
            null
        )
        for (error in errors) {
            assertEquals("纯音乐 / 无歌词", LyricsSearch.failureMessage(error, showDetails = false))
            assertEquals("未找到", LyricsSearch.failureSource(null, error, showDetails = false))
            for (source in listOf("QQ音乐", "网易云音乐", "LRCLIB")) {
                assertEquals("$source (无歌词)", LyricsSearch.failureSource(source, error, showDetails = false))
            }
        }
    }

    @Test
    fun debugMessagesStillDistinguishMissingLyricsFromOtherFailures() {
        val error = LyricsSearchException(LyricsSearchFailure.NO_SYNCED_LYRICS, "QQ 音乐")
        assertEquals("歌曲暂无同步歌词", LyricsSearch.failureMessage(error, showDetails = true))
        assertEquals("QQ音乐 (无歌词)", LyricsSearch.failureSource("QQ音乐", error, showDetails = true))
        assertEquals("歌词加载失败，请稍后重试", LyricsSearch.failureMessage(IllegalStateException(), showDetails = true))
    }

    @Test
    fun cancellationIsNeverTreatedAsAnEmptyCandidate() {
        assertThrows(CancellationException::class.java) {
            LyricsSearch.find("QQ 音乐", expected, { listOf(candidate) }, { throw CancellationException() })
        }
    }
}
