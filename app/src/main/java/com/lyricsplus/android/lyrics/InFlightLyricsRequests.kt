package com.lyricsplus.android.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One request per playback, including its completed success or failure. No time-based retries. */
internal class InFlightLyricsRequests(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private class Request(val session: LyricsPlaybackSession) {
        val state = MutableStateFlow(Snapshot())
        lateinit var job: Job
    }

    private data class Snapshot(
        val candidate: CachedLyricsResult? = null,
        val completed: Result<CachedLyricsResult>? = null
    )

    private val lock = Any()
    // Database writes never hold lock, so beginning playback cannot wait on disk IO.
    private val commitMutex = Mutex()
    private var current: LyricsPlaybackSession? = null
    private var request: Request? = null
    private val latestSessions = mutableMapOf<String, LyricsPlaybackSession>()

    fun begin(key: String): LyricsPlaybackSession {
        val old: Request?
        val session: LyricsPlaybackSession
        synchronized(lock) {
            current?.takeIf { it.key == key }?.let { return it }
            old = request
            current?.let { previous ->
                if (old == null || old.state.value.completed != null) {
                    if (latestSessions[previous.key] === previous) latestSessions.remove(previous.key)
                }
            }
            session = LyricsPlaybackSession(key)
            current = session
            request = null
            latestSessions[key] = session
        }
        old?.job?.cancel(CancellationException("播放歌曲已切换"))
        return session
    }

    fun requireCurrent(session: LyricsPlaybackSession) {
        synchronized(lock) {
            if (current !== session) throw CancellationException("歌词请求属于上一轮播放")
        }
    }

    suspend fun markManualSelection(session: LyricsPlaybackSession) {
        commitMutex.withLock {
            synchronized(lock) {
                if (current !== session) throw CancellationException("歌词请求属于上一轮播放")
                session.manualSelection = true
            }
        }
    }

    // Convenience for callers that synchronously begin and subscribe together.
    suspend fun markManualSelection(key: String) = markManualSelection(begin(key))

    suspend fun await(
        key: String,
        onUpdate: suspend (CachedLyricsResult) -> Unit,
        fetch: suspend (suspend (CachedLyricsResult) -> Unit, suspend (() -> Unit) -> Boolean) -> Result<CachedLyricsResult>
    ): Result<CachedLyricsResult> = await(begin(key), onUpdate, fetch)

    suspend fun await(
        session: LyricsPlaybackSession,
        onUpdate: suspend (CachedLyricsResult) -> Unit,
        fetch: suspend (suspend (CachedLyricsResult) -> Unit, suspend (() -> Unit) -> Boolean) -> Result<CachedLyricsResult>
    ): Result<CachedLyricsResult> {
        val shared = synchronized(lock) {
            requireCurrent(session)
            request ?: Request(session).also { created ->
                request = created
                created.job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = try {
                            fetch({ created.state.value = Snapshot(candidate = it) }) { save ->
                                commitMutex.withLock {
                                    val allowed = synchronized(lock) {
                                        !session.manualSelection && latestSessions[session.key] === session
                                    }
                                    if (allowed) save()
                                    allowed
                                }
                            }
                        } catch (error: Exception) {
                            Result.failure(error)
                        }
                        created.state.value = Snapshot(result.getOrNull() ?: created.state.value.candidate, result)
                    } finally {
                        synchronized(lock) {
                            if (current !== session && latestSessions[session.key] === session) {
                                latestSessions.remove(session.key)
                            }
                        }
                    }
                }
                created.job.invokeOnCompletion { error ->
                    // A song can change before a lazy job starts. Wake its subscribers
                    // even when the coroutine body never gets a chance to run.
                    if (error is CancellationException ||
                        (error != null && created.state.value.completed == null)) {
                        created.state.value = Snapshot(created.state.value.candidate, Result.failure(error))
                    }
                    synchronized(lock) {
                        if (current !== session && latestSessions[session.key] === session) {
                            latestSessions.remove(session.key)
                        }
                    }
                }
            }
        }
        shared.job.start()
        var delivered: CachedLyricsResult? = null
        val final = shared.state.first { snapshot ->
            requireCurrent(session)
            // Switching songs is cancellation, never a no-lyrics or request-failed state.
            snapshot.completed?.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            snapshot.candidate?.let { candidate ->
                if (candidate != delivered) {
                    delivered = candidate
                    onUpdate(candidate)
                }
            }
            snapshot.completed != null
        }
        return final.completed!!
    }
}
