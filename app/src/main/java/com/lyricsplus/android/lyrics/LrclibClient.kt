package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class LrclibClient(private val observer: LyricsSearchObserver? = null) {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = HttpClient.lyricsRequest {
        runCatching {
            val expected = SongMetadata(track.track, track.artist, track.album, track.durationSeconds.toDouble())
            fetchExact(track, expected) ?: LyricsSearch.find(
                source = "LRCLIB",
                expected = expected,
                search = ::searchSongs,
                fetch = { parseLyrics(it.value) },
                observer = observer
            )
        }
    }

    private fun fetchExact(track: NowPlaying, expected: SongMetadata): LyricsSearchResult? {
        val query = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=").append(track.track.urlEncode())
            append("&artist_name=").append(track.artist.urlEncode())
            if (track.album.isNotBlank()) append("&album_name=").append(track.album.urlEncode())
            if (track.durationSeconds > 0) append("&duration=").append(track.durationSeconds)
        }
        val response = request(query)
        if (response.code == 404) return null
        if (response.code !in 200..299) throw java.io.IOException("LRCLIB HTTP ${response.code}")
        val obj = JSONObject(response.body)
        val score = LyricsMatching.score(expected, obj.songMetadata()) ?: return null
        val lyrics = parseLyrics(obj).ifEmpty { return null }
        observer?.selected(obj.optLong("id").toString(), obj.songMetadata(), score)
        return LyricsSearchResult(lyrics, score)
    }

    private fun searchSongs(query: LyricsSearchQuery): List<LyricsCandidate<JSONObject>> {
        val url = buildString {
            append("https://lrclib.net/api/search?track_name=").append(query.title.urlEncode())
            if (query.artist.isNotBlank()) append("&artist_name=").append(query.artist.urlEncode())
        }
        val response = request(url)
        if (response.code !in 200..299) throw java.io.IOException("LRCLIB HTTP ${response.code}")
        val results = JSONArray(response.body)
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optLong("id") > 0 }
            .map { LyricsCandidate(it.optLong("id").toString(), it.songMetadata(), it) }
    }

    private fun JSONObject.songMetadata(): SongMetadata = SongMetadata(
        title = optString("trackName"),
        artist = optString("artistName"),
        album = optString("albumName"),
        durationSeconds = optDouble("duration", 0.0)
    )

    private fun parseLyrics(obj: JSONObject): List<LyricsLine> {
        if (obj.optBoolean("instrumental", false)) return listOf(LyricsLine(0L, "♪ 纯音乐 ♪"))
        val enhanced = if (obj.isNull("enhancedLyrics")) "" else obj.optString("enhancedLyrics")
        val synced = if (obj.isNull("syncedLyrics")) "" else obj.optString("syncedLyrics")
        fun usable(raw: String) = SyncedLyricsQuality.usable(
            LrcParser.parse(raw), obj.optString("trackName"), obj.optDouble("duration", 0.0)
        )
        return usable(enhanced).ifEmpty { usable(synced) }
    }

    private fun request(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "LyricsPlusAndroid/0.1")
            .build()

        return HttpClient.newLyricsCall(request).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}
