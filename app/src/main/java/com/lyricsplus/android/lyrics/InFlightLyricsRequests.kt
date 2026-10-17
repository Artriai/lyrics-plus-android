package com.lyricsplus.android.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares only running requests; completed results still use the existing caches. */
internal class InFlightLyricsRequests(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private class Request {
        val state = MutableStateFlow(Snapshot())
        @Volatile var manualSelection = false
    }

    private data class Snapshot(
        val candidate: CachedLyricsResult? = null,
        val completed: Result<CachedLyricsResult>? = null
    )

    private val mutex = Mutex()
    private val requests = mutableMapOf<String, Request>()

    suspend fun markManualSelection(key: String) {
        mutex.withLock { requests[key]?.manualSelection = true }
    }

    suspend fun await(
        key: String,
        onUpdate: suspend (CachedLyricsResult) -> Unit,
        fetch: suspend (publish: suspend (CachedLyricsResult) -> Unit, maySave: () -> Boolean) -> Result<CachedLyricsResult>
    ): Result<CachedLyricsResult> {
        val request = mutex.withLock {
            requests[key] ?: Request().also { request ->
                requests[key] = request
                scope.launch(start = CoroutineStart.DEFAULT) {
                    val result = try {
                        fetch({ request.state.value = Snapshot(candidate = it) }, { !request.manualSelection })
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    request.state.value = Snapshot(result.getOrNull() ?: request.state.value.candidate, result)
                    mutex.withLock {
                        if (requests[key] === request) requests.remove(key)
                    }
                }
            }
        }
        var delivered: CachedLyricsResult? = null
        val final = request.state.first { snapshot ->
            snapshot.candidate?.let { candidate ->
                if (candidate !== delivered) {
                    delivered = candidate
                    onUpdate(candidate)
                }
            }
            snapshot.completed != null
        }
        return final.completed!!
    }
}
