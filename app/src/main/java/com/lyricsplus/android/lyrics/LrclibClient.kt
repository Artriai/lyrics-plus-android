package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

import com.lyricsplus.android.data.SongSearchResult

class LrclibClient {
    suspend fun searchSongs(query: String): List<SongSearchResult> = withContext(Dispatchers.IO) {
        val url = "https://lrclib.net/api/search?q=" + query.urlEncode()
        val response = request(url)
        if (response.code !in 200..299) return@withContext emptyList()

        val results = runCatching { JSONArray(response.body) }.getOrNull() ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { i ->
            val obj = results.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optLong("id")
            if (id <= 0) return@mapNotNull null
            val trackName = obj.optString("trackName")
            val artistName = obj.optString("artistName")
            val albumName = obj.optString("albumName")
            val duration = obj.optLong("duration", 0L)
            val hasSynced = obj.optString("syncedLyrics").isNotBlank() || obj.optString("enhancedLyrics").isNotBlank() || obj.optBoolean("instrumental", false)

            SongSearchResult(
                id = id.toString(),
                title = trackName,
                artist = artistName,
                album = albumName,
                durationSeconds = duration,
                source = "LRCLIB",
                hasSyncedLyrics = hasSynced
            )
        }
    }

    suspend fun fetchLyricsById(id: Long): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request("https://lrclib.net/api/get/$id")
            if (response.code !in 200..299) error("LRCLIB 未找到歌词 (HTTP ${response.code})")

            val obj = JSONObject(response.body)
            val isInstrumental = obj.optBoolean("instrumental", false)
            val enhanced = obj.optString("enhancedLyrics")
            val synced = obj.optString("syncedLyrics")

            val lyrics = if (isInstrumental) {
                listOf(LyricsLine(0L, "♪ 纯音乐 ♪"))
            } else {
                val lrcToParse = if (!enhanced.isNullOrBlank()) enhanced else synced
                parseSynced(lrcToParse) ?: error("LRCLIB 歌词解析为空")
            }

            LyricsSearchResult(lyrics, 100)
        }
    }

    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            fetchExact(track) ?: searchBestMatch(track) ?: error("LRCLIB 未找到同步歌词")
        }
    }

    private fun fetchExact(track: NowPlaying): LyricsSearchResult? {
        val query = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=").append(track.track.urlEncode())
            append("&artist_name=").append(track.artist.urlEncode())
            if (track.album.isNotBlank()) append("&album_name=").append(track.album.urlEncode())
            if (track.durationSeconds > 0) append("&duration=").append(track.durationSeconds)
        }

        val response = request(query)
        if (response.code !in 200..299) return null

        val obj = JSONObject(response.body)
        val isInstrumental = obj.optBoolean("instrumental", false)
        val enhanced = obj.optString("enhancedLyrics")
        val synced = obj.optString("syncedLyrics")
        
        val lyrics = if (isInstrumental) {
            listOf(LyricsLine(0L, "♪ 纯音乐 ♪"))
        } else {
            val lrcToParse = if (!enhanced.isNullOrBlank()) enhanced else synced
            parseSynced(lrcToParse) ?: return null
        }
        
        val score = calculateScore(track, obj)
        return LyricsSearchResult(lyrics, score)
    }

    private fun searchBestMatch(track: NowPlaying): LyricsSearchResult? {
        val query = buildString {
            append("https://lrclib.net/api/search")
            append("?track_name=").append(track.track.urlEncode())
            append("&artist_name=").append(track.artist.urlEncode())
        }

        val response = request(query)
        if (response.code !in 200..299) return null

        val results = JSONArray(response.body)
        val candidates = (0 until results.length())
            .map { results.getJSONObject(it) }
            .filter { it.optString("syncedLyrics").isNotBlank() || it.optBoolean("instrumental", false) }

        var bestCandidate: JSONObject? = null
        var bestScore = -1
        
        for (candidate in candidates) {
            val score = calculateScore(track, candidate)
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }
        
        val best = bestCandidate ?: return null
        val isInstrumental = best.optBoolean("instrumental", false)
        val enhanced = best.optString("enhancedLyrics")
        val synced = best.optString("syncedLyrics")
        
        val lyrics = if (isInstrumental) {
            listOf(LyricsLine(0L, "♪ 纯音乐 ♪"))
        } else {
            val lrcToParse = if (!enhanced.isNullOrBlank()) enhanced else synced
            parseSynced(lrcToParse) ?: return null
        }
        
        return LyricsSearchResult(lyrics, bestScore)
    }

    private fun calculateScore(track: NowPlaying, candidate: JSONObject): Int {
        val name = candidate.optString("trackName").lowercase().replace("\\s+".toRegex(), "")
        val artist = candidate.optString("artistName").lowercase().replace("\\s+".toRegex(), "")
        val album = candidate.optString("albumName").lowercase().replace("\\s+".toRegex(), "")
        val duration = candidate.optLong("duration")
        
        val normalizedTitle = track.track.lowercase().replace("\\s+".toRegex(), "")
        val normalizedArtist = track.artist.lowercase().replace("\\s+".toRegex(), "")
        val normalizedAlbum = track.album.lowercase().replace("\\s+".toRegex(), "")
        
        val durationDiff = if (track.durationSeconds > 0) abs(track.durationSeconds - duration) else Long.MAX_VALUE
        
        var score = 0
        if (name == normalizedTitle) score += 50
        else if (normalizedTitle.isNotBlank() && (name.contains(normalizedTitle) || normalizedTitle.contains(name))) score += 20
        
        if (artist.isNotBlank() && normalizedArtist.isNotBlank() && artist == normalizedArtist) score += 40
        else if (artist.isNotBlank() && normalizedArtist.isNotBlank() && (artist.contains(normalizedArtist) || normalizedArtist.contains(artist))) score += 25
        
        if (normalizedAlbum.isNotBlank() && album == normalizedAlbum) score += 20
        
        if (durationDiff < 3) score += 30
        else if (durationDiff < 10) score += 10
        
        return score
    }

    private fun parseSynced(syncedLyrics: String): List<LyricsLine>? {
        if (syncedLyrics.isBlank()) return null
        val parsed = LrcParser.parse(syncedLyrics)
        return if (parsed.isEmpty()) null else parsed
    }

    private fun request(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "LyricsPlusAndroid/0.1")
            .build()

        return HttpClient.okHttpClient.newCall(request).execute().use { response ->
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
