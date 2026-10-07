package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

import com.lyricsplus.android.data.SongSearchResult

class NeteaseClient {
    suspend fun searchSongs(query: String, limit: Int = 20): List<SongSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = cleanTitle(query)
        val url = "https://music.163.com/api/cloudsearch/pc?csrf_token=&type=1&offset=0&limit=$limit&s=" +
            cleanQuery.urlEncode()
        val response = request(url)
        if (response.code !in 200..299) return@withContext emptyList()

        val songs = JSONObject(response.body)
            .optJSONObject("result")
            ?.optJSONArray("songs")
            ?: return@withContext emptyList()

        (0 until songs.length()).mapNotNull { i ->
            val song = songs.optJSONObject(i) ?: return@mapNotNull null
            val id = song.optLong("id")
            if (id <= 0) return@mapNotNull null
            val name = song.optString("name")
            val album = song.optJSONObject("al")?.optString("name") ?: song.optJSONObject("album")?.optString("name").orEmpty()
            val artists = song.artistNames()
            val durationMs = song.optLong("dt", song.optLong("duration", 0L))
            val durationSeconds = if (durationMs > 0) (durationMs + 500) / 1000 else 0L

            SongSearchResult(
                id = id.toString(),
                title = name,
                artist = artists,
                album = album,
                durationSeconds = durationSeconds,
                source = "网易云音乐"
            )
        }
    }

    suspend fun fetchLyricsById(songId: Long): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val lyricJson = fetchLyrics(songId) ?: error("网易云音乐未找到歌词")
            if (lyricJson.optBoolean("nolyric", false)) {
                return@runCatching LyricsSearchResult(listOf(LyricsLine(0L, "♪ 纯音乐 ♪")), 100)
            }
            val yrcString = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val lrcString = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()

            val synced = if (yrcString.isNotBlank()) {
                parseNeteaseYrc(yrcString)
            } else {
                parseNeteaseLrc(lrcString)
            }.ifEmpty { error("网易云音乐同步歌词为空") }

            val translation = parseNeteaseLrc(lyricJson.optJSONObject("tlyric")?.optString("lyric").orEmpty())
            val merged = mergeTranslation(synced, translation)
            LyricsSearchResult(merged, 100)
        }
    }

    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val searchResult = searchSongId(track) ?: error("网易云音乐未找到歌曲")
            val lyricJson = fetchLyrics(searchResult.first) ?: error("网易云音乐未找到歌词")
            if (lyricJson.optBoolean("nolyric", false)) {
                return@runCatching LyricsSearchResult(listOf(LyricsLine(0L, "♪ 纯音乐 ♪")), searchResult.second)
            }
            
            val yrcString = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
            val lrcString = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()

            val synced = if (yrcString.isNotBlank()) {
                parseNeteaseYrc(yrcString)
            } else {
                parseNeteaseLrc(lrcString)
            }.ifEmpty { error("网易云音乐同步歌词为空") }

            val translation = parseNeteaseLrc(lyricJson.optJSONObject("tlyric")?.optString("lyric").orEmpty())

            val merged = mergeTranslation(synced, translation)
            LyricsSearchResult(merged, searchResult.second)
        }
    }

    private fun searchSongId(track: NowPlaying): Pair<Long, Int>? {
        val cleanTitle = cleanTitle(track.track)
        val url = "https://music.163.com/api/cloudsearch/pc?csrf_token=&type=1&offset=0&limit=10&s=" +
            "${cleanTitle} ${track.artist}".urlEncode()
        val response = request(url)
        if (response.code !in 200..299) return null

        val songs = JSONObject(response.body)
            .optJSONObject("result")
            ?.optJSONArray("songs")
            ?: return null

        val normalizedTitle = normalizeForCompare(cleanTitle)
        val normalizedArtist = normalizeForCompare(track.artist)
        val normalizedAlbum = normalizeForCompare(track.album)

        return (0 until songs.length())
            .map { songs.getJSONObject(it) }
            .map { song ->
                val name = normalizeForCompare(song.optString("name"))
                val album = normalizeForCompare(song.optJSONObject("al")?.optString("name") ?: song.optJSONObject("album")?.optString("name").orEmpty())
                val artists = normalizeForCompare(song.artistNames())
                val durationMs = song.optLong("dt", song.optLong("duration", 0L))
                val expectedDurationMs = track.durationSeconds * 1000L
                val durationDiff = if (expectedDurationMs > 0) abs(expectedDurationMs - durationMs) else Long.MAX_VALUE

                var score = 0
                if (name == normalizedTitle) score += 50
                else if (normalizedTitle.isNotBlank() && (name.contains(normalizedTitle) || normalizedTitle.contains(name))) score += 20
                if (artists.isNotBlank() && normalizedArtist.isNotBlank() && artists == normalizedArtist) score += 40
                else if (artists.isNotBlank() && normalizedArtist.isNotBlank() && (artists.contains(normalizedArtist) || normalizedArtist.contains(artists))) score += 25
                if (album.isNotBlank() && album == normalizedAlbum) score += 20
                if (durationDiff < 3_000) score += 30
                else if (durationDiff < 10_000) score += 10

                song.optLong("id") to score
            }
            .filter { it.first > 0 && it.second > 0 }
            .maxByOrNull { it.second }
    }

    private fun fetchLyrics(songId: Long): JSONObject? {
        val urls = listOf(
            "https://music.163.com/api/song/lyric/v1?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1",
            "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1"
        )

        return urls.firstNotNullOfOrNull { url ->
            val response = request(url)
            if (response.code !in 200..299) return@firstNotNullOfOrNull null
            val json = JSONObject(response.body)
            if (
                json.optJSONObject("lrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optJSONObject("yrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optJSONObject("tlyric")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optBoolean("nolyric", false)
            ) {
                json
            } else {
                null
            }
        }
    }

    private fun parseNeteaseYrc(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        val parsed = LrcParser.parse(raw)
        return parsed.filterNot { line ->
            val cleanText = line.text.replace(yrcSyllableRegex, "").trim()
            cleanText.isBlank() || containsCredits(cleanText) || cleanText == "纯音乐, 请欣赏"
        }
    }

    private fun parseNeteaseLrc(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .mapNotNull { line ->
                val match = timestampRegex.find(line.trim()) ?: return@mapNotNull null
                val text = line.replace(timestampRegex, "").trim()
                if (text.isBlank() || containsCredits(text) || text == "纯音乐, 请欣赏") return@mapNotNull null

                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val millis = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricsLine(minutes * 60_000 + seconds * 1_000 + millis, text)
            }
            .sortedBy { it.startTimeMs }
            .toList()
    }

    private fun mergeTranslation(base: List<LyricsLine>, translation: List<LyricsLine>): List<LyricsLine> {
        if (translation.isEmpty() || base.looksChinese()) return base

        return TimedLyricsMerger.mergeTranslation(
            base = base,
            translation = translation,
            lineFilter = { line -> comparableText(line.text).isNotBlank() }
        )
    }

    private fun request(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 LyricsPlusAndroid/0.1")
            .header("Referer", "https://music.163.com/")
            .build()

        return HttpClient.okHttpClient.newCall(request).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun JSONObject.artistNames(): String {
        val artists = optJSONArray("ar") ?: optJSONArray("artists") ?: return ""
        return (0 until artists.length())
            .joinToString(" ") { artists.getJSONObject(it).optString("name") }
    }

    private fun cleanTitle(value: String): String =
        value.replace(extraInfoRegex, "").replace(featRegex, "").trim()

    private fun normalizeForCompare(value: String): String =
        value.lowercase().replace(whitespaceRegex, "")

    private fun comparableText(value: String): String =
        value.replace(spacingRegex, "").replace(punctuationRegex, "")

    private fun List<LyricsLine>.looksChinese(): Boolean {
        val sample = joinToString("") { it.text }.take(200)
        if (sample.isBlank()) return false
        val han = sample.count { it in '\u4E00'..'\u9FFF' }
        val kana = sample.count { it in '\u3040'..'\u30FF' }
        return han > 0 && kana == 0
    }

    private fun containsCredits(text: String): Boolean = creditRegex.containsMatchIn(text)

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private companion object {
        val timestampRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val yrcSyllableRegex = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
        val extraInfoRegex = Regex("\\s*[\\[(（].*?(?:remaster|remastered|live|mono|stereo|version|edit|mix|feat\\.?|with).*?[\\]）)]\\s*", RegexOption.IGNORE_CASE)
        val featRegex = Regex("\\s+(feat\\.?|ft\\.?|with)\\s+.+$", RegexOption.IGNORE_CASE)
        val whitespaceRegex = Regex("\\s+")
        val spacingRegex = Regex("[　\\s]")
        val punctuationRegex = Regex("""[!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~？！，。、《》【】「」]""")
        val creditRegex = Regex(
            "^(\\s?作?\\s*词|\\s?作?\\s*曲|\\s?编\\s*曲?|\\s?监\\s*制?|.*编写|.*和音|.*和声|.*合声|.*提琴|.*录|.*工程|.*工作室|.*设计|.*剪辑|.*制作|.*发行|.*出品|.*后期|.*混音|.*缩混|原唱|翻唱|题字|文案|海报|古筝|二胡|钢琴|吉他|贝斯|笛子|鼓|弦乐|lrc|publish|vocal|guitar|program|produce|write|mix).*(:|：)",
            RegexOption.IGNORE_CASE
        )
    }
}
