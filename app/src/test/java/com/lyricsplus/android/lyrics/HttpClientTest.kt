package com.lyricsplus.android.lyrics

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HttpClientTest {
    @Test fun cancellationClosesBlockingBodyReadAndPreventsFallbackCalls() = runBlocking {
        withTimeout(5000L) {
            ServerSocket(0).use { server ->
                val headersSent = CompletableDeferred<Unit>()
                val serverDone = CompletableDeferred<Unit>()
                thread(isDaemon = true) {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 4000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                                flush() // Never send a body: the client must cancel the blocked read.
                            }
                            headersSent.complete(Unit)
                            socket.getInputStream().read()
                        }
                        serverDone.complete(Unit)
                    } catch (error: Exception) {
                        headersSent.completeExceptionally(error)
                        serverDone.completeExceptionally(error)
                    }
                }
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/lyrics").build()
                val activeCall = AtomicReference<Call>()
                val fallbackPrevented = AtomicBoolean(false)
                val lookup = async {
                    HttpClient.lyricsRequest {
                        val call = HttpClient.newLyricsCall(request)
                        activeCall.set(call)
                        runCatching { call.execute().use { it.body!!.string() } }
                        // Clients may try a fallback after IOException. Cancellation must
                        // prevent it from creating another running HTTP request.
                        try {
                            HttpClient.newLyricsCall(request)
                        } catch (error: CancellationException) {
                            fallbackPrevented.set(true)
                        }
                    }
                }
                headersSent.await()
                lookup.cancelAndJoin()
                assertTrue(activeCall.get().isCanceled())
                assertTrue(fallbackPrevented.get())
                serverDone.await()
            }
        }
    }
}
