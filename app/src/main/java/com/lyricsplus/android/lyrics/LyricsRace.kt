package com.lyricsplus.android.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

/** Uncached: a short window then upgrades. Cached: collect everything before one upgrade. */
internal class LyricsRace(
    private val awaitWindow: suspend () -> Unit = { delay(300L) },
    private val awaitDeadline: suspend () -> Unit = { delay(REQUEST_TIMEOUT_MS) }
) {
    private sealed interface Event {
        data class Completed(val result: Result<CachedLyricsResult>, val fallback: Boolean = false) : Event
        data object WindowElapsed : Event
        data object Deadline : Event
    }

    suspend fun run(
        primary: List<suspend () -> Result<CachedLyricsResult>>,
        fallback: suspend () -> Result<CachedLyricsResult>,
        onUpdate: suspend (CachedLyricsResult) -> Unit,
        cached: CachedLyricsResult? = null,
        onInterrupted: suspend (CachedLyricsResult) -> Unit = {}
    ): Result<CachedLyricsResult> = coroutineScope {
        require(primary.isNotEmpty())
        val events = Channel<Event>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        val failures = mutableListOf<Throwable>()
        val refreshed = mutableListOf<CachedLyricsResult>()
        var remaining = primary.size
        var best: CachedLyricsResult? = cached
        var publishedScore: Int? = cached?.let { LyricsMatching.selectionScore(it.score, it.lyrics) }
        var windowStarted = false
        var fallbackStarted = false

        fun CachedLyricsResult.totalScore() = LyricsMatching.selectionScore(score, lyrics)

        fun finishLegacyValidation() {
            val previous = cached?.takeIf { it.score == null } ?: return
            val timingFloor = LyricsMatching.selectionScore(0, previous.lyrics)
            val verified = refreshed.filter {
                it.score != null && LyricsMatching.selectionScore(0, it.lyrics) >= timingFloor
            }
            // Until its source is revalidated, an unscored primary cache can only
            // receive a definite line -> word upgrade. Do not guess its old match score.
            val baseline = verified.firstOrNull { it.source == previous.source }
            val eligible = if (baseline != null || previous.source !in listOf("网易云音乐", "QQ音乐")) {
                verified
            } else verified.filter { LyricsMatching.selectionScore(0, it.lyrics) > timingFloor }
            best = eligible.fold(baseline ?: previous) { winner, candidate ->
                if (candidate.totalScore() > winner.totalScore()) candidate else winner
            }
        }

        suspend fun publish() {
            val candidate = best ?: return
            val score = candidate.totalScore()
            if (publishedScore == null || score > publishedScore!!) {
                publishedScore = score
                onUpdate(candidate)
            }
        }

        fun failure(): Result<CachedLyricsResult> = Result.failure(
            failures.firstOrNull { it is IOException }
                ?: failures.firstOrNull {
                    it is LyricsSearchException && it.reason == LyricsSearchFailure.NO_SYNCED_LYRICS
                }
                ?: failures.firstOrNull()
                ?: LyricsSearchException(LyricsSearchFailure.NO_MATCH, "歌词服务")
        )

        // A cache hit is useful immediately; never expose intermediate refresh winners.
        if (cached != null) onUpdate(cached)
        primary.forEach { request ->
            jobs += launch { events.send(Event.Completed(requestResult(request))) }
        }
        jobs += launch {
            awaitDeadline()
            events.send(Event.Deadline)
        }

        try {
            var outcome: Result<CachedLyricsResult>? = null
            while (outcome == null) {
                when (val event = events.receive()) {
                    is Event.Completed -> {
                        if (!event.fallback) remaining--
                        event.result.fold(
                            onSuccess = { candidate ->
                                if (cached != null && cached.score == null) refreshed.add(candidate)
                                if (best == null || candidate.totalScore() > best!!.totalScore()) best = candidate
                            },
                            onFailure = { failures.add(it) }
                        )
                        val candidate = best
                        if (candidate != null && cached == null) {
                            if (publishedScore != null || remaining == 0 ||
                                candidate.totalScore() >= LyricsMatching.MAX_MATCH_SCORE + LyricsMatching.SYLLABLE_BONUS) {
                                publish()
                            } else if (!windowStarted) {
                                // Failures never start the timer, and later arrivals never reset it.
                                windowStarted = true
                                jobs += launch {
                                    awaitWindow()
                                    events.send(Event.WindowElapsed)
                                }
                            }
                        }
                        if (remaining == 0) {
                            if (candidate != null) {
                                finishLegacyValidation()
                                publish()
                                outcome = Result.success(best!!)
                            } else if (event.fallback) {
                                outcome = failure()
                            } else if (!fallbackStarted) {
                                // LRCLIB never competes with an available primary result.
                                fallbackStarted = true
                                jobs += launch { events.send(Event.Completed(requestResult(fallback), fallback = true)) }
                            }
                        }
                    }
                    Event.WindowElapsed -> publish()
                    Event.Deadline -> {
                        // Keep any useful lyrics when the remaining source is too slow.
                        finishLegacyValidation()
                        publish()
                        outcome = best?.let { Result.success(it) }
                            ?: Result.failure(SocketTimeoutException("歌词搜索超过 8 秒"))
                    }
                }
            }
            outcome
        } catch (error: CancellationException) {
            // A track switch stops work, but useful results already obtained belong in
            // that track's cache. Do not publish an upgrade to the departing screen.
            finishLegacyValidation()
            best?.let { withContext(NonCancellable) { onInterrupted(it) } }
            throw error
        } finally {
            jobs.forEach { it.cancel() }
            events.close()
        }
    }

    companion object {
        const val REQUEST_TIMEOUT_MS = 8_000L
    }

    private suspend fun requestResult(request: suspend () -> Result<CachedLyricsResult>): Result<CachedLyricsResult> {
        val result = try {
            request()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.mapCatching { candidate ->
            if (candidate.lyrics.isEmpty()) throw LyricsSearchException(LyricsSearchFailure.NO_SYNCED_LYRICS, candidate.source)
            candidate
        }
    }
}
