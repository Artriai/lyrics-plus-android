package com.lyricsplus.android.lyrics

import java.io.IOException
import java.util.concurrent.CancellationException

internal data class QqMusicSearchResponse<T>(val value: T, val code: Int)

/** QQ's business errors are independent of its HTTP status. */
internal object QqMusicSearch {
    fun <T> requestWithRetry(
        request: () -> QqMusicSearchResponse<T>,
        pause: (Long) -> Unit
    ): T {
        repeat(3) { attempt ->
            val response = request()
            if (response.code == 0) return response.value
            if (response.code != 2001 || attempt == 2) {
                throw IOException("QQ 音乐搜索服务请求失败 (${response.code})")
            }
            pause(300L)
        }
        error("Unreachable")
    }

    fun <T> withFallback(
        fullSearches: List<() -> List<T>>,
        suggestions: () -> List<T>
    ): List<T> {
        var fullSearchCompleted = false
        var firstFailure: Exception? = null
        for ((index, search) in (fullSearches + suggestions).withIndex()) {
            try {
                val candidates = search()
                if (candidates.isNotEmpty()) return candidates
                if (index < fullSearches.size) fullSearchCompleted = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        // An empty suggestion list cannot establish that a full search had no match.
        if (!fullSearchCompleted) firstFailure?.let { throw it }
        return emptyList()
    }
}
