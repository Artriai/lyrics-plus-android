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
    @Test fun completedSuccessIsReplayedWithoutRepeatingProviderRequests() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val session = pool.begin("A")
            var fetches = 0
            repeat(3) {
                val updates = mutableListOf<CachedLyricsResult>()
                val result = pool.await(session, { updates.add(it) }) { publish, _ ->
                    fetches++
                    publish(better)
                    Result.success(better)
                }
                assertEquals(better, result.getOrThrow())
                assertEquals(listOf(better), updates)
            }
            assertEquals(1, fetches)
            assertSame(session, pool.begin("A"))
        }
    }

    @Test fun completedFailureDoesNotRetryUntilPlaybackChanges() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            var fetches = 0
            suspend fun fetch(): Result<CachedLyricsResult> {
                fetches++
                return Result.failure(java.io.IOException("offline"))
            }
            repeat(3) { assertTrue(pool.await("A", {}) { _, _ -> fetch() }.isFailure) }
            assertEquals(1, fetches)
            pool.begin("B")
            assertTrue(pool.await("A", {}) { _, _ -> fetch() }.isFailure)
            assertEquals(2, fetches)
        }
    }

    @Test fun changingSongsCancelsOldWorkAndRejectsItsDelayedSubscribers() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val oldSession = pool.begin("A")
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val old = async {
                pool.await(oldSession, {}) { _, _ ->
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            started.await()
            val nextSession = pool.begin("B")
            stopped.await()
            assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
            var staleFetch = false
            val stale = runCatching {
                pool.await(oldSession, {}) { _, _ -> staleFetch = true; Result.success(first) }
            }
            assertTrue(stale.exceptionOrNull() is CancellationException)
            assertFalse(staleFetch)
            assertEquals(better, pool.await(nextSession, {}) { _, _ -> Result.success(better) }.getOrThrow())
        }
    }

    @Test fun manualLockAppliesEvenBeforeTheAutomaticRequestStarts() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val session = pool.begin("A")
            pool.markManualSelection(session)
            pool.await(session, {}) { _, commit ->
                assertFalse(commit { error("A manual playback cannot save an automatic winner") })
                Result.success(first)
            }
            pool.begin("B")
            val again = pool.begin("A")
            assertNotSame(session, again)
            pool.await(again, {}) { _, commit ->
                assertTrue(commit {})
                Result.success(better)
            }
            Unit
        }
    }

    @Test fun oldCleanupCannotOverwriteOrForgetANewerPlaybackOfTheSameSong() = runBlocking {
        withTimeout(5000L) {
            val pool = InFlightLyricsRequests(this)
            val oldSession = pool.begin("A")
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            var value = first
            var lateCommit = true
            val old = async {
                pool.await(oldSession, {}) { _, commit ->
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            finishCleanup.await()
                            lateCommit = commit { value = first }
                        }
                    }
                }
            }
            started.await()
            pool.begin("B")
            cleaning.await()
            val again = pool.begin("A")
            assertEquals(better, pool.await(again, {}) { _, commit ->
                assertTrue(commit { value = better })
                Result.success(better)
            }.getOrThrow())
            finishCleanup.complete(Unit)
            assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
            assertFalse(lateCommit)
            assertEquals(better, value)
            assertEquals(better, pool.await(again, {}) { _, _ -> error("Must reuse new A") }.getOrThrow())
        }
    }

    @Test fun cancellingBeforeWorkerDispatchDoesNotHangItsSubscribers() = runBlocking {
        withTimeout(5000L) {
            val queue = java.util.ArrayDeque<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
            }
            val workers = CoroutineScope(SupervisorJob() + dispatcher)
            val pool = InFlightLyricsRequests(workers)
            var fetched = false
            val old = async {
                pool.await("A", {}) { _, _ -> fetched = true; Result.success(first) }
            }
            yield()
            assertFalse(queue.isEmpty())
            pool.begin("B")
            while (!queue.isEmpty()) queue.removeFirst().run()
            assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
            assertFalse(fetched)
            workers.cancel()
        }
    }

    @Test fun sourceWritesAllowManualPrewarmingButRejectEarlierPlaybackOfTheSameSong() = runBlocking {
        val pool = InFlightLyricsRequests(this)
        val old = pool.begin("A")
        pool.markManualSelection(old)
        var source = first
        assertTrue(pool.commitSource(old) { source = better })
        assertEquals(better, source)
        pool.begin("B")
        val again = pool.begin("A")
        assertTrue(pool.commitSource(again) { source = better })
        assertFalse(pool.commitSource(old) { source = first })
        assertEquals(better, source)
    }

}
