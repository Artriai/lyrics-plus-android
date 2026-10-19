package com.lyricsplus.android.lyrics

import com.lyricsplus.android.BuildConfig
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.LyricsSearchResult
import java.io.IOException
import java.util.concurrent.CancellationException

data class LyricsSearchQuery(val title: String, val artist: String)

data class LyricsCandidate<T>(val id: String, val song: SongMetadata, val value: T)

/** Opt-in metadata diagnostics for live regression tests; no lyric text is exposed. */
interface LyricsSearchObserver {
    fun candidates(query: LyricsSearchQuery, songs: List<Pair<String, SongMetadata>>) {}
    fun selected(id: String, song: SongMetadata, score: Int) {}
}

enum class LyricsSearchFailure { NO_MATCH, NO_SYNCED_LYRICS }

class LyricsSearchException(val reason: LyricsSearchFailure, source: String) : Exception(
    when (reason) {
        LyricsSearchFailure.NO_MATCH -> "$source 未找到匹配歌曲"
        LyricsSearchFailure.NO_SYNCED_LYRICS -> "$source 未找到同步歌词"
    }
)

/** Bounded query fallback and candidate retries, shared by all three providers. */
object LyricsSearch {
    private const val MAX_LYRIC_REQUESTS = 3

    fun failureMessage(error: Throwable?): String = failureMessage(error, BuildConfig.DEBUG)

    internal fun failureMessage(error: Throwable?, showDetails: Boolean): String {
        if (!showDetails) return "纯音乐 / 无歌词"
        return when {
            error is LyricsSearchException && error.reason == LyricsSearchFailure.NO_MATCH -> "未找到匹配歌曲"
            error is LyricsSearchException -> "歌曲暂无同步歌词"
            error is IOException -> "歌词服务请求失败，请稍后重试"
            else -> "歌词加载失败，请稍后重试"
        }
    }

    fun failureSource(source: String?, error: Throwable?): String =
        failureSource(source, error, BuildConfig.DEBUG)

    internal fun failureSource(source: String?, error: Throwable?, showDetails: Boolean): String {
        if (!showDetails) return if (source == null) "未找到" else "$source (无歌词)"
        val label = when {
            error is LyricsSearchException && error.reason == LyricsSearchFailure.NO_MATCH -> "未匹配"
            error is LyricsSearchException -> "无歌词"
            else -> "请求失败"
        }
        return if (source == null) label else "$source ($label)"
    }

    fun queries(song: SongMetadata): List<LyricsSearchQuery> {
        val titles = LyricsMatching.searchTitles(song.title)
        return (titles.map { LyricsSearchQuery(it, song.artist.trim()) } +
            titles.takeLast(1).map { LyricsSearchQuery(it, "") }).distinct()
    }

    fun <T> find(
        source: String,
        expected: SongMetadata,
        search: (LyricsSearchQuery) -> List<LyricsCandidate<T>>,
        fetch: (LyricsCandidate<T>) -> List<LyricsLine>,
        observer: LyricsSearchObserver? = null
    ): LyricsSearchResult {
        val attempted = mutableSetOf<String>()
        var foundMatch = false
        var fetchFailure: Exception? = null
        for (query in queries(expected)) {
            // Network/API failures propagate; keyword fallback is only for an
            // unsuccessful search, not a reason to repeat a failing connection.
            val songs = search(query)
            observer?.candidates(query, songs.map { it.id to it.song })
            val candidates = songs.mapNotNull { candidate ->
                LyricsMatching.score(expected, candidate.song)?.let { candidate to it }
            }.sortedByDescending { it.second }
            if (candidates.isNotEmpty()) foundMatch = true
            for ((candidate, score) in candidates) {
                if (candidate.id in attempted) continue
                if (attempted.size >= MAX_LYRIC_REQUESTS) break
                attempted.add(candidate.id)
                try {
                    val lyrics = fetch(candidate)
                    if (lyrics.isNotEmpty()) {
                        observer?.selected(candidate.id, candidate.song, score)
                        return LyricsSearchResult(lyrics, score)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Prefer an actionable service error over a later empty candidate.
                    if (fetchFailure == null || error is IOException) fetchFailure = error
                }
            }
            if (attempted.size >= MAX_LYRIC_REQUESTS) break
        }
        fetchFailure?.let { throw it }
        throw LyricsSearchException(
            if (foundMatch) LyricsSearchFailure.NO_SYNCED_LYRICS else LyricsSearchFailure.NO_MATCH,
            source
        )
    }
}
