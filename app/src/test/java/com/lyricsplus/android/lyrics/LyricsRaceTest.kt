package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class LyricsRaceTest {
    private fun candidate(source: String, score: Int = 90, words: Boolean = false) = CachedLyricsResult(
        listOf(LyricsLine(1000L, if (words) "hello(1000,500)" else "hello")), source, score
    )

    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5000L, block)
    }

    private class Harness(scope: CoroutineScope) {
        val first = CompletableDeferred<Result<CachedLyricsResult>>()
        val second = CompletableDeferred<Result<CachedLyricsResult>>()
        val fallback = CompletableDeferred<Result<CachedLyricsResult>>()
        val window = CompletableDeferred<Unit>()
        val deadline = CompletableDeferred<Unit>()
        val windowStarted = CompletableDeferred<Unit>()
        val fallbackStarted = CompletableDeferred<Unit>()
        val updates = Channel<CachedLyricsResult>(Channel.UNLIMITED)
        val received = mutableListOf<CachedLyricsResult>()
        var windows = 0
        val result = scope.async {
            LyricsRace(
                awaitWindow = { windows++; windowStarted.complete(Unit); window.await() },
                awaitDeadline = { deadline.await() }
            ).run(
                primary = listOf({ first.await() }, { second.await() }),
                fallback = { fallbackStarted.complete(Unit); fallback.await() },
                onUpdate = { received.add(it); updates.send(it) }
            )
        }
    }

    @Test fun waitsAfterFirstValidResultThenUpgradesToLateWordLyrics() = scenario {
        val h = Harness(this)
        val netease = candidate("网易云音乐", 100)
        val qq = candidate("QQ音乐", 85, words = true)
        h.first.complete(Result.success(netease))
        h.windowStarted.await()
        assertTrue(h.received.isEmpty())
        h.window.complete(Unit)
        assertEquals(netease, h.updates.receive())
        assertFalse(h.result.isCompleted)
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.updates.receive())
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(1, h.windows)
        assertFalse(h.fallbackStarted.isCompleted)
    }

    @Test fun bothFinishedInsideWindowPublishesOnlyBestWithoutWaiting() = scenario {
        val h = Harness(this)
        h.first.complete(Result.success(candidate("网易云音乐", 90)))
        h.windowStarted.await()
        val qq = candidate("QQ音乐", 85, words = true)
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(listOf(qq), h.received)
        assertFalse(h.window.isCompleted)
    }

    @Test fun failuresDoNotStartWindowAndCompletedFailureDoesNotDelaySuccess() = scenario {
        val h = Harness(this)
        h.first.complete(Result.failure(IOException("offline")))
        // Drain tasks on this single coroutine event loop; no wall-clock sleeps.
        repeat(10) { yield() }
        assertFalse(h.windowStarted.isCompleted)
        val qq = candidate("QQ音乐")
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(listOf(qq), h.received)
        assertFalse(h.windowStarted.isCompleted)
    }

    @Test fun maximumScoreIsShownImmediatelyWithoutWaitingForOtherSource() = scenario {
        val h = Harness(this)
        val qq = candidate("QQ音乐", 100, words = true)
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.updates.receive())
        assertFalse(h.windowStarted.isCompleted)
        h.first.complete(Result.success(candidate("网易云音乐", 100, words = true)))
        h.result.await().getOrThrow()
        assertEquals(listOf(qq), h.received)
    }

    @Test fun lateEqualOrLowerScoreNeverReplacesCurrentLyrics() = scenario {
        for (lateScore in listOf(90, 80)) {
            val h = Harness(this)
            val first = candidate("网易云音乐", 90)
            h.first.complete(Result.success(first))
            h.windowStarted.await()
            h.window.complete(Unit)
            h.updates.receive()
            h.second.complete(Result.success(candidate("QQ音乐", lateScore)))
            assertEquals(first, h.result.await().getOrThrow())
            assertEquals(listOf(first), h.received)
        }
    }

    @Test fun lateFailureKeepsDisplayedLyricsAndDoesNotStartFallback() = scenario {
        val h = Harness(this)
        val first = candidate("网易云音乐")
        h.first.complete(Result.success(first))
        h.windowStarted.await()
        h.window.complete(Unit)
        h.updates.receive()
        h.second.complete(Result.failure(IOException("timeout")))
        assertEquals(first, h.result.await().getOrThrow())
        assertEquals(listOf(first), h.received)
        assertFalse(h.fallbackStarted.isCompleted)
    }

    @Test fun lrclibStartsOnlyAfterBothPrimariesFail() = scenario {
        val h = Harness(this)
        h.first.complete(Result.failure(IOException("offline")))
        repeat(10) { yield() }
        assertFalse(h.fallbackStarted.isCompleted)
        h.second.complete(Result.success(CachedLyricsResult(emptyList(), "QQ音乐")))
        h.fallbackStarted.await()
        val lrc = candidate("LRCLIB")
        h.fallback.complete(Result.success(lrc))
        assertEquals(lrc, h.result.await().getOrThrow())
        assertEquals(listOf(lrc), h.received)
        assertFalse(h.windowStarted.isCompleted)
    }

    @Test fun allFailuresKeepActionableNetworkError() = scenario {
        val h = Harness(this)
        val network = IOException("offline")
        h.first.complete(Result.failure(network))
        h.second.complete(Result.failure(LyricsSearchException(LyricsSearchFailure.NO_MATCH, "QQ音乐")))
        h.fallbackStarted.await()
        h.fallback.complete(Result.failure(LyricsSearchException(LyricsSearchFailure.NO_SYNCED_LYRICS, "LRCLIB")))
        assertSame(network, h.result.await().exceptionOrNull())
        assertTrue(h.received.isEmpty())
    }

    @Test fun deadlineKeepsBestResultAndCancelsPendingPrimary() = scenario {
        val h = Harness(this)
        val first = candidate("网易云音乐")
        h.first.complete(Result.success(first))
        h.windowStarted.await()
        h.deadline.complete(Unit)
        assertEquals(first, h.result.await().getOrThrow())
        assertEquals(listOf(first), h.received)
        assertFalse(h.fallbackStarted.isCompleted)
    }

    @Test fun deadlineCoversFallbackAndReturnsFailureWhenNothingIsAvailable() = scenario {
        val h = Harness(this)
        h.first.complete(Result.failure(IOException()))
        h.second.complete(Result.failure(IOException()))
        h.fallbackStarted.await()
        h.deadline.complete(Unit)
        assertTrue(h.result.await().exceptionOrNull() is SocketTimeoutException)
        assertTrue(h.received.isEmpty())
    }

    @Test fun externalCancellationStopsRaceWithoutPublishingFailure() = scenario {
        val h = Harness(this)
        h.first.complete(Result.success(candidate("网易云音乐")))
        h.windowStarted.await()
        h.result.cancelAndJoin()
        assertTrue(h.received.isEmpty())
        assertFalse(h.fallbackStarted.isCompleted)
    }
}
