package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LyricsCacheResolverTest {
    private val now = 1_000_000_000L

    private fun candidate(source: String, score: Int? = 90, words: Boolean = false) = CachedLyricsResult(
        listOf(LyricsLine(1000L, if (words) "hello(1000,500)" else "hello")), source, score
    )

    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5000L, block) }

    private inner class Harness(
        scope: CoroutineScope,
        val cached: LyricsCacheEntry,
        prepare: suspend (List<LyricsLine>) -> List<LyricsLine> = { it }
    ) {
        val first = CompletableDeferred<Result<CachedLyricsResult>>()
        val second = CompletableDeferred<Result<CachedLyricsResult>>()
        val firstDone = CompletableDeferred<Unit>()
        val deadline = CompletableDeferred<Unit>()
        val updates = Channel<CachedLyricsResult>(Channel.UNLIMITED)
        val received = mutableListOf<CachedLyricsResult>()
        val saved = mutableListOf<LyricsCacheEntry>()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val cancelled = mutableListOf<String>()
        var windows = 0
        val result = scope.async {
            LyricsCacheResolver(
                LyricsRace(awaitWindow = { windows++ }, awaitDeadline = { deadline.await() }), { now }
            ).resolve(
                cached = cached,
                request = { source, refresh ->
                    calls.add(source to refresh)
                    try {
                        when (source) {
                            "网易云音乐" -> first.await().also { firstDone.complete(Unit) }
                            "QQ音乐" -> second.await()
                            else -> error("LRCLIB must not run on a cache hit")
                        }
                    } finally {
                        if (!currentCoroutineContext().isActive) cancelled.add(source)
                    }
                },
                onUpdate = { received.add(it); updates.send(it) },
                save = { saved.add(it) },
                prepare = prepare
            )
        }
    }

    @Test fun showsCacheBeforeReadingPreparationOrNetworkWork() = scenario {
        val prepare = CompletableDeferred<Unit>()
        val cached = LyricsCacheEntry(candidate("网易云音乐", 70), now - 1000)
        val h = Harness(this, cached) { prepare.await(); it }
        assertEquals(cached.result, h.updates.receive())
        assertTrue(h.calls.isEmpty())
        assertFalse(h.result.isCompleted)
        prepare.complete(Unit)
        h.first.complete(Result.success(candidate("网易云音乐", 80)))
        h.second.complete(Result.success(candidate("QQ音乐", 85, words = true)))
        h.result.await().getOrThrow()
    }

    @Test fun collectsBothSourcesAndUpgradesOnlyOnceToFinalBest() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", 70), now - 1000)
        val h = Harness(this, cached)
        assertEquals(cached.result, h.updates.receive())
        h.first.complete(Result.success(candidate("网易云音乐", 95)))
        h.firstDone.await()
        repeat(5) { yield() }
        assertEquals(listOf(cached.result), h.received)
        val qq = candidate("QQ音乐", 85, words = true)
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result, qq), h.received)
        assertEquals(setOf("网易云音乐" to true, "QQ音乐" to true), h.calls.toSet())
        assertEquals(0, h.windows)
        assertEquals(qq, h.saved.single().result)
        assertTrue(h.saved.single().checkSucceeded)
    }

    @Test fun evenMaximumScoreWaitsForCollectionOnCacheHits() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", 70), now - 1000)
        val h = Harness(this, cached)
        h.updates.receive()
        val best = candidate("网易云音乐", 100, words = true)
        h.first.complete(Result.success(best))
        h.firstDone.await()
        repeat(5) { yield() }
        assertEquals(listOf(cached.result), h.received)
        h.second.complete(Result.success(candidate("QQ音乐", 95)))
        assertEquals(best, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result, best), h.received)
    }

    @Test fun tiesAndLowerScoresKeepCacheIncludingItsOriginalAge() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", 90, words = true), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.success(candidate("网易云音乐", 100)))
        h.second.complete(Result.success(candidate("QQ音乐", 90, words = true)))
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result), h.received)
        assertEquals(cached.cachedAt, h.saved.single().cachedAt)
        assertEquals(now, h.saved.single().checkedAt)
    }

    @Test fun deadlineUpgradesOnceAndCancelsSlowSourceWithoutAcceptingLateResult() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", 70), now - 1000)
        val h = Harness(this, cached)
        h.updates.receive()
        val netease = candidate("网易云音乐", 95)
        h.first.complete(Result.success(netease))
        h.firstDone.await()
        h.deadline.complete(Unit)
        assertEquals(netease, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result, netease), h.received)
        assertTrue("QQ音乐" in h.cancelled)
        assertFalse(h.saved.single().checkSucceeded)
        h.second.complete(Result.success(candidate("QQ音乐", 85, words = true)))
        repeat(5) { yield() }
        assertEquals(listOf(cached.result, netease), h.received)
    }

    @Test fun failedRefreshPreservesCacheAndUsesShortRetryInterval() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", 90), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.failure(IOException("offline")))
        h.second.complete(Result.failure(IOException("timeout")))
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result), h.received)
        val saved = h.saved.single()
        assertFalse(saved.checkSucceeded)
        assertFalse(LyricsCachePolicy.shouldRefresh(saved, now + LyricsCachePolicy.RETRY_INTERVAL_MS - 1))
        assertTrue(LyricsCachePolicy.shouldRefresh(saved, now + LyricsCachePolicy.RETRY_INTERVAL_MS))
    }

    @Test fun realEmptySearchesCompleteTheCheckWithoutDeletingUsefulLyrics() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", 85, words = true), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.failure(LyricsSearchException(LyricsSearchFailure.NO_MATCH, "网易云音乐")))
        h.second.complete(Result.failure(LyricsSearchException(LyricsSearchFailure.NO_SYNCED_LYRICS, "QQ音乐")))
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertTrue(h.saved.single().checkSucceeded)
        assertFalse(LyricsCachePolicy.shouldRefresh(h.saved.single(), now + LyricsCachePolicy.RETRY_INTERVAL_MS))
    }

    @Test fun freshCacheDoesNotRequestAnyProviderOrResetRefreshAge() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", 85, words = true), now - 1000, now - 1000, true)
        val h = Harness(this, cached)
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result), h.received)
        assertTrue(h.calls.isEmpty())
        assertTrue(h.saved.isEmpty())
    }

    @Test fun oldUnknownScoreIsRevalidatedWithoutInventingFullMarks() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", null), now - 1000)
        val h = Harness(this, cached)
        val best = candidate("QQ音乐", 85)
        h.first.complete(Result.success(candidate("网易云音乐", 80)))
        h.second.complete(Result.success(best))
        assertEquals(best, h.result.await().getOrThrow())
        assertEquals(85, h.saved.single().result.score)
        assertEquals(listOf(cached.result, best), h.received)
    }

    @Test fun oldUnknownWordLyricsCannotBeDowngradedToLineLyrics() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", null, words = true), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.success(candidate("网易云音乐", 100)))
        h.second.complete(Result.failure(IOException()))
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertNull(h.saved.single().result.score)
        assertEquals(listOf(cached.result), h.received)
    }

    @Test fun unknownPrimaryCacheWaitsForItsOwnSourceBeforeComparingEqualQuality() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", null, words = true), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.success(candidate("网易云音乐", 90, words = true)))
        h.second.complete(Result.failure(IOException()))
        assertEquals(cached.result, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result), h.received)
    }

    @Test fun unknownCacheCanReceiveDefiniteWordUpgradeWhenItsSourceFails() = scenario {
        val cached = LyricsCacheEntry(candidate("网易云音乐", null), now - 1000)
        val h = Harness(this, cached)
        val qq = candidate("QQ音乐", 85, words = true)
        h.first.complete(Result.failure(IOException()))
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(listOf(cached.result, qq), h.received)
    }

    @Test fun revalidatedLegacySourceWinsTiesEvenIfOtherSourceReturnsFirst() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", null, words = true), now - 1000)
        val h = Harness(this, cached)
        h.first.complete(Result.success(candidate("网易云音乐", 85, words = true)))
        h.firstDone.await()
        val qq = candidate("QQ音乐", 85, words = true)
        h.second.complete(Result.success(qq))
        assertEquals(qq, h.result.await().getOrThrow())
        assertEquals(85, h.saved.single().result.score)
        assertEquals(listOf(cached.result, qq), h.received)
    }

    @Test fun readingEnrichmentKeepsScoreAndDoesNotPostponeRefresh() = scenario {
        val cached = LyricsCacheEntry(candidate("QQ音乐", 85, words = true), now - 1000, now - 1000, true)
        val h = Harness(this, cached) { lines -> lines.map { it.copy(reading = "new reading") } }
        val enriched = h.result.await().getOrThrow()
        assertEquals("new reading", enriched.lyrics.single().reading)
        assertEquals(85, enriched.score)
        assertEquals(cached.checkedAt, h.saved.single().checkedAt)
        assertEquals(cached.cachedAt, h.saved.single().cachedAt)
        assertTrue(h.calls.isEmpty())
    }

    @Test fun manualSelectionDuringSharedRefreshBlocksAutomaticSaveButStillWarmsSources() = scenario {
        val pool = InFlightLyricsRequests(this)
        val cached = LyricsCacheEntry(candidate("网易云音乐", 70), now - 1000)
        var automatic = cached
        val sources = mutableMapOf<String, CachedLyricsResult>()
        val finish = CompletableDeferred<Unit>()
        val updates = Channel<CachedLyricsResult>(Channel.UNLIMITED)
        val qq = candidate("QQ音乐", 85, words = true)
        val request = async {
            pool.await("song", { updates.send(it) }) { publish, commit ->
                LyricsCacheResolver(now = { now }).resolve(
                    cached,
                    request = { source, _ ->
                        finish.await()
                        val value = if (source == "QQ音乐") qq else candidate(source, 95)
                        sources[source] = value
                        Result.success(value)
                    },
                    onUpdate = publish,
                    save = { entry -> commit { automatic = entry } }
                )
            }
        }
        assertEquals(cached.result, updates.receive())
        pool.markManualSelection("song")
        finish.complete(Unit)
        assertEquals(qq, request.await().getOrThrow())
        assertEquals(cached, automatic)
        assertEquals(setOf("网易云音乐", "QQ音乐"), sources.keys)
        // A later automatic session is not permanently locked by the previous manual action.
        assertEquals(qq, pool.await("song", {}) { _, commit ->
            assertTrue(commit { automatic = LyricsCacheEntry(qq, now) })
            Result.success(qq)
        }.getOrThrow())
        assertEquals(qq, automatic.result)
    }
}
