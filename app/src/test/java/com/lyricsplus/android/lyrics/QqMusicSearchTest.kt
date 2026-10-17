package com.lyricsplus.android.lyrics

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class QqMusicSearchTest {
    @Test fun primarySuccessSkipsLegacyAndSuggestionRequests() {
        val result = QqMusicSearch.withFallback(
            fullSearches = listOf({ listOf("modern") }, { error("Legacy must not run") }),
            suggestions = { error("Suggestions must not run") }
        )
        assertEquals(listOf("modern"), result)
    }

    @Test fun primaryFailureUsesLegacyBeforeSuggestions() {
        val visited = mutableListOf<String>()
        val result = QqMusicSearch.withFallback(
            fullSearches = listOf(
                { visited.add("modern"); throw IOException("HTTP 500") },
                { visited.add("legacy"); listOf("song") }
            ),
            suggestions = { error("Suggestions must not run") }
        )
        assertEquals(listOf("modern", "legacy"), visited)
        assertEquals(listOf("song"), result)
    }

    @Test fun suggestionsRemainAvailableWhenBothFullSearchesFail() {
        val result = QqMusicSearch.withFallback(
            fullSearches = listOf({ throw IOException() }, { throw IOException() }),
            suggestions = { listOf("suggestion") }
        )
        assertEquals(listOf("suggestion"), result)
    }

    @Test fun validEmptyPrimaryStillTriesOtherSearches() {
        val result = QqMusicSearch.withFallback(
            fullSearches = listOf({ emptyList() }, { listOf("legacy") }),
            suggestions = { error("Suggestions must not run") }
        )
        assertEquals(listOf("legacy"), result)
    }

    @Test fun validEmptyFullSearchIsNotReclassifiedAsRequestFailure() {
        val result = QqMusicSearch.withFallback<String>(
            fullSearches = listOf({ emptyList() }, { throw IOException("legacy unavailable") }),
            suggestions = { emptyList() }
        )
        assertTrue(result.isEmpty())
    }

    @Test fun emptySuggestionsDoNotHideFailedFullSearches() {
        val original = IOException("modern request failed")
        val error = assertThrows(IOException::class.java) {
            QqMusicSearch.withFallback<String>(
                fullSearches = listOf({ throw original }, { throw IOException("legacy failed") }),
                suggestions = { emptyList() }
            )
        }
        assertSame(original, error)
    }

    @Test fun cancellationNeverStartsFallbackRequests() {
        assertThrows(CancellationException::class.java) {
            QqMusicSearch.withFallback<String>(
                fullSearches = listOf({ throw CancellationException() }, { error("Legacy must not run") }),
                suggestions = { error("Suggestions must not run") }
            )
        }
    }

    @Test fun retries2001AtMostTwiceWithShortWaits() {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = QqMusicSearch.requestWithRetry<String>(
            request = { attempts++; QqMusicSearchResponse("songs", if (attempts < 3) 2001 else 0) },
            pause = { waits.add(it) }
        )
        assertEquals("songs", result)
        assertEquals(3, attempts)
        assertEquals(listOf(300L, 300L), waits)
    }

    @Test fun persistent2001BecomesRequestFailureAfterThreeAttempts() {
        var attempts = 0
        var waits = 0
        val error = assertThrows(IOException::class.java) {
            QqMusicSearch.requestWithRetry<String>(
                request = { attempts++; QqMusicSearchResponse("", 2001) },
                pause = { waits++ }
            )
        }
        assertEquals(3, attempts)
        assertEquals(2, waits)
        assertTrue(error.message!!.contains("2001"))
    }

    @Test fun otherBusinessCodesDoNotRetryOrMasqueradeAsEmptySearches() {
        for (code in listOf(1000, 500, -1)) {
            var attempts = 0
            assertThrows(IOException::class.java) {
                QqMusicSearch.requestWithRetry<String>(
                    request = { attempts++; QqMusicSearchResponse("", code) },
                    pause = { error("Must not retry $code") }
                )
            }
            assertEquals(1, attempts)
        }
    }

    @Test fun transportFailureDoesNotTriggerBusinessCodeRetries() {
        var attempts = 0
        val network = IOException("HTTP 500")
        val error = assertThrows(IOException::class.java) {
            QqMusicSearch.requestWithRetry<String>(
                request = { attempts++; throw network },
                pause = { error("Must not retry HTTP errors") }
            )
        }
        assertSame(network, error)
        assertEquals(1, attempts)
    }

    @Test fun cancelledRetryDelayDoesNotSendAnotherRequest() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            QqMusicSearch.requestWithRetry<String>(
                request = { attempts++; QqMusicSearchResponse("", 2001) },
                pause = { throw CancellationException() }
            )
        }
        assertEquals(1, attempts)
    }
}
