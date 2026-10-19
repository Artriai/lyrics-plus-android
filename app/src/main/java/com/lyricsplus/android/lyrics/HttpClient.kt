package com.lyricsplus.android.lyrics

import android.content.Context
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object HttpClient {
    private var cacheDir: File? = null
    private val activeLyricsRequest = ThreadLocal<LyricsCalls>()

    // Client parsing/search is synchronous on IO. Tie every underlying call to
    // that coroutine so the race deadline can actually stop blocking HTTP work.
    suspend fun <T> lyricsRequest(block: () -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val calls = LyricsCalls()
            continuation.invokeOnCancellation { calls.cancel() }
            activeLyricsRequest.set(calls)
            try {
                calls.checkActive()
                continuation.resume(block())
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                activeLyricsRequest.remove()
            }
        }
    }

    fun newLyricsCall(request: Request): Call = okHttpClient.newCall(request).also { call ->
        activeLyricsRequest.get()?.add(call)
    }

    fun pauseLyricsRequest(milliseconds: Long) {
        val calls = activeLyricsRequest.get()
        if (calls != null) calls.pause(milliseconds) else Thread.sleep(milliseconds)
    }

    private class LyricsCalls {
        private var cancelled = false
        private val calls = mutableListOf<Call>()
        private val cancellation = CountDownLatch(1)

        fun pause(milliseconds: Long) {
            if (cancellation.await(milliseconds, TimeUnit.MILLISECONDS)) {
                throw CancellationException("Lyrics request cancelled")
            }
        }

        @Synchronized fun checkActive() {
            if (cancelled) throw CancellationException("Lyrics request cancelled")
        }

        @Synchronized fun add(call: Call) {
            checkActive()
            calls.add(call)
        }

        @Synchronized fun cancel() {
            cancelled = true
            cancellation.countDown()
            calls.forEach { it.cancel() }
        }
    }

    fun initialize(context: Context) {
        if (cacheDir == null) {
            cacheDir = File(context.applicationContext.cacheDir, "http_cache")
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        cacheDir?.let { dir ->
            runCatching {
                val cacheSize = 10 * 1024 * 1024L // 10 MB Cache
                builder.cache(Cache(dir, cacheSize))
            }
        }

        builder.build()
    }
}
