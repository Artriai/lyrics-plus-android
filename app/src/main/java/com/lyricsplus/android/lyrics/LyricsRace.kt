package com.lyricsplus.android.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

/** One short collection window, followed by strictly better late results. */
internal class LyricsRace(
    private val awaitWindow: suspend () -> Unit = { delay(300L) },
    private val awaitDeadline: suspend () -> Unit = { delay(20_000L) }
) {
    private sealed interface Event {
        data class Completed(val result: Result<CachedLyricsResult>, val fallback: Boolean = false) : Event
        data object WindowElapsed : Event
        data object Deadline : Event
    }

    suspend fun run(
        primary: List<suspend () -> Result<CachedLyricsResult>>,
        fallback: suspend () -> Result<CachedLyricsResult>,
        onUpdate: suspend (CachedLyricsResult) -> Unit
    ): Result<CachedLyricsResult> = coroutineScope {
        require(primary.isNotEmpty())
        val events = Channel<Event>(Channel.UNLIMITED)
        val jobs = mutableListOf<Job>()
        val failures = mutableListOf<Throwable>()
        var remaining = primary.size
        var best: CachedLyricsResult? = null
        var publishedScore: Int? = null
        var windowStarted = false
        var fallbackStarted = false

        fun CachedLyricsResult.totalScore() = LyricsMatching.selectionScore(score, lyrics)

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
                                if (best == null || candidate.totalScore() > best!!.totalScore()) best = candidate
                            },
                            onFailure = { failures.add(it) }
                        )
                        val candidate = best
                        if (candidate != null) {
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
                                outcome = Result.success(candidate)
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
                        publish()
                        outcome = best?.let { Result.success(it) }
                            ?: Result.failure(SocketTimeoutException("歌词搜索超过 20 秒"))
                    }
                }
            }
            outcome
        } finally {
            jobs.forEach { it.cancel() }
            events.close()
        }
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
