package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.io.File
import java.time.Instant

private class Trace(private val expected: SongMetadata) : LyricsSearchObserver {
    val queries = JSONArray()
    var selected: JSONObject? = null
    override fun candidates(query: LyricsSearchQuery, songs: List<Pair<String, SongMetadata>>) {
        queries.put(JSONObject().put("title", query.title).put("artist", query.artist).put("candidates",
            JSONArray(songs.map { (id, song) -> metadata(id, song) })))
    }
    override fun selected(id: String, song: SongMetadata, score: Int) {
        selected = metadata(id, song).put("score", score)
    }
    private fun metadata(id: String, song: SongMetadata) = JSONObject()
        .put("id", id).put("title", song.title).put("artist", song.artist)
        .put("artistAliases", JSONArray(song.artistAliases)).put("artistCredits", JSONArray(song.artistCredits)).put("album", song.album)
        .put("durationSeconds", song.durationSeconds)
        .put("score", LyricsMatching.score(expected, song) ?: JSONObject.NULL)
        .put("scoreWithoutAliases", LyricsMatching.score(expected, song.copy(artistAliases = emptyList(), artistCredits = emptyList())) ?: JSONObject.NULL)
}

fun main(args: Array<String>) = runBlocking {
    val input = JSONObject(File(args[0]).readText()).getJSONArray("songs")
    val output = File(args[1]).apply { parentFile?.mkdirs() }
    val sources = args.getOrNull(2)?.split(",") ?: listOf("netease", "qq", "lrclib")
    val limit = args.getOrNull(3)?.toInt() ?: input.length()
    val workers = args.getOrNull(4)?.toInt() ?: 3
    val spacingMs = args.getOrNull(5)?.toLong() ?: 3000L
    val sourceLocks = sources.associateWith { Mutex() }
    val nextStart = mutableMapOf<String, Long>()
    val rateLimited = mutableSetOf<String>()
    val completed = if (!output.exists()) emptySet() else output.readLines().filter(String::isNotBlank).map {
        val row = JSONObject(it); row.getString("sampleId") + ":" + row.getString("source")
    }.toSet()
    val queue = kotlinx.coroutines.channels.Channel<Pair<JSONObject, String>>(workers)
    coroutineScope {
        repeat(workers) {
            launch {
                for ((song, source) in queue) {
                    val skipped = sourceLocks.getValue(source).withLock {
                        if (source in rateLimited) true else {
                            val wait = (nextStart[source] ?: 0L) - System.nanoTime() / 1_000_000
                            if (wait > 0) delay(wait)
                            nextStart[source] = System.nanoTime() / 1_000_000 + spacingMs
                            source in rateLimited
                        }
                    }
                    val track = NowPlaying(track = song.getString("title"), artist = song.getString("artist"),
                        album = song.optString("album"), durationSeconds = song.optInt("durationSeconds"))
                    val trace = Trace(SongMetadata(track.track, track.artist, track.album, track.durationSeconds.toDouble()))
                    val start = System.nanoTime()
                    var result: Result<LyricsSearchResult>? = null
                    val finished = if (skipped) true else withTimeoutOrNull(LyricsRace.REQUEST_TIMEOUT_MS) {
                        result = when (source) {
                            "netease" -> NeteaseClient(trace).findSyncedLyrics(track)
                            "qq" -> QQMusicClient(trace).findSyncedLyrics(track)
                            "lrclib" -> LrclibClient(trace).findSyncedLyrics(track)
                            else -> error("Unknown source: $source")
                        }
                        true
                    } ?: false
                    val value = result?.getOrNull()
                    val error = result?.exceptionOrNull()
                    if (error?.message?.contains("HTTP 429") == true) rateLimited.add(source)
                    val status = when {
                        skipped -> "SKIPPED_RATE_LIMIT"
                        !finished -> "TIMEOUT"
                        value != null -> when {
                            value.lyrics.all { it.text == "♪ 纯音乐 ♪" } -> "INSTRUMENTAL"
                            LyricsMatching.selectionScore(0, value.lyrics) > 0 -> "WORD"
                            else -> "LINE"
                        }
                        error is LyricsSearchException -> error.reason.name
                        else -> "REQUEST_FAILED"
                    }
                    val row = JSONObject().put("sampleId", song.getString("sampleId")).put("source", source)
                        .put("title", track.track).put("artist", track.artist).put("status", status)
                        .put("elapsedMs", (System.nanoTime() - start) / 1_000_000)
                        .put("testedAt", Instant.now().toString()).put("queries", trace.queries)
                        .put("selected", trace.selected ?: JSONObject.NULL)
                        .put("lineCount", value?.lyrics?.size ?: 0)
                        .put("matchScore", value?.score ?: JSONObject.NULL)
                        .put("selectionScore", value?.let { LyricsMatching.selectionScore(it.score, it.lyrics) } ?: JSONObject.NULL)
                        .put("errorType", error?.javaClass?.simpleName ?: JSONObject.NULL)
                        .put("error", error?.message ?: JSONObject.NULL)
                    output.appendText(row.toString() + "\n")
                    println("${song.getString("sampleId")} $source $status ${row.getLong("elapsedMs")}ms ${track.track}")
                    delay(250)
                }
            }
        }
        for (i in 0 until minOf(limit, input.length())) for (source in sources) {
            val song = input.getJSONObject(i)
            if ("${song.getString("sampleId")}:$source" !in completed) queue.send(song to source)
        }
        queue.close()
        Unit
    }
    HttpClient.okHttpClient.connectionPool.evictAll()
    HttpClient.okHttpClient.dispatcher.executorService.shutdown()

}
