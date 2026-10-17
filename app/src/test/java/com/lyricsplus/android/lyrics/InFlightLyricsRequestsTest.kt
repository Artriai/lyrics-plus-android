package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class InFlightLyricsRequestsTest {
    private val first = CachedLyricsResult(listOf(LyricsLine(1000L, "hello")), "网易云音乐", 90)
    private val better = CachedLyricsResult(listOf(LyricsLine(1000L, "hello(1000,500)")), "QQ音乐", 85)

    @Test fun sharesProgressReplaysCurrentWinnerAndSurvivesOneSubscriberLeaving() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val finish = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val earlyUpdates = Channel<CachedLyricsResult>(Channel.UNLIMITED)
            val lateUpdates = Channel<CachedLyricsResult>(Channel.UNLIMITED)
            var fetches = 0
            val early = async {
                pool.await("song", { earlyUpdates.send(it) }) { publish, _ ->
                    fetches++
                    publish(first)
                    started.complete(Unit)
                    finish.await()
                    publish(better)
                    Result.success(better)
                }
            }
            started.await()
            assertEquals(first, earlyUpdates.receive())
            val late = async {
                pool.await("song", { lateUpdates.send(it) }) { _, _ ->
                    error("A second request must not be started")
                }
            }
            assertEquals(first, lateUpdates.receive())
            early.cancelAndJoin()
            finish.complete(Unit)
            assertEquals(better, lateUpdates.receive())
            assertEquals(better, late.await().getOrThrow())
            assertEquals(1, fetches)
        }
    }

    @Test fun manualSelectionStopsLateAutomaticSaveButAllowsSourcePrewarming() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var maySaveAfterSwitch = true
            val request = async {
                pool.await("song", {}) { publish, commit ->
                    assertTrue(commit {})
                    started.complete(Unit)
                    finish.await()
                    publish(better)
                    maySaveAfterSwitch = commit { error("Manual selection must prohibit an automatic write") }
                    Result.success(better)
                }
            }
            started.await()
            pool.markManualSelection("song")
            finish.complete(Unit)
            assertEquals(better, request.await().getOrThrow())
            assertFalse(maySaveAfterSwitch)
        }
    }

    @Test fun requestGenerationRejectsOldSongAndEarlierManualSwitches() {
        val generation = LyricsRequestGeneration()
        val songA = generation.next()
        generation.next() // B
        val songAAgain = generation.next()
        assertFalse(generation.isCurrent(songA))
        assertTrue(generation.isCurrent(songAAgain))
        val firstManualSwitch = generation.next()
        val secondManualSwitch = generation.next()
        assertFalse(generation.isCurrent(songAAgain))
        assertFalse(generation.isCurrent(firstManualSwitch))
        assertTrue(generation.isCurrent(secondManualSwitch))
    }
}
