package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

class NeteaseClient {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            LyricsSearch.find(
                source = "网易云音乐",
                expected = SongMetadata(track.track, track.artist, track.album, track.durationSeconds.toDouble()),
                search = ::searchSongs,
                fetch = { fetchSyncedLyrics(it.value) }
            )
        }
    }

    private fun fetchSyncedLyrics(songId: Long): List<LyricsLine> {
        val lyricJson = fetchLyrics(songId) ?: return emptyList()
        if (lyricJson.optBoolean("nolyric", false)) return listOf(LyricsLine(0L, "♪ 纯音乐 ♪"))
        val yrc = lyricJson.optJSONObject("yrc")?.optString("lyric").orEmpty()
        val lrc = lyricJson.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val synced = if (yrc.isNotBlank()) parseNeteaseYrc(yrc).ifEmpty { parseNeteaseLrc(lrc) }
            else parseNeteaseLrc(lrc)
        val translation = parseNeteaseLrc(lyricJson.optJSONObject("tlyric")?.optString("lyric").orEmpty())
        return mergeTranslation(synced, translation)
    }

    private fun searchSongs(query: LyricsSearchQuery): List<LyricsCandidate<Long>> {
        val url = "https://music.163.com/api/cloudsearch/pc?csrf_token=&type=1&offset=0&limit=10&s=" +
            "${query.title} ${query.artist}".trim().urlEncode()
        val response = request(url)
        if (response.code !in 200..299) throw java.io.IOException("网易云音乐 HTTP ${response.code}")
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 200) throw java.io.IOException("网易云音乐搜索服务请求失败")
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { index ->
            val song = songs.getJSONObject(index)
            val id = song.optLong("id")
            if (id <= 0) null else LyricsCandidate(
                id.toString(),
                SongMetadata(
                    title = song.optString("name"),
                    artist = song.artistNames(),
                    album = song.optJSONObject("al")?.optString("name")
                        ?: song.optJSONObject("album")?.optString("name").orEmpty(),
                    durationSeconds = song.optLong("dt", song.optLong("duration", 0L)) / 1000.0
                ),
                id
            )
        }
    }

    private fun fetchLyrics(songId: Long): JSONObject? {
        val urls = listOf(
            "https://music.163.com/api/song/lyric/v1?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1",
            "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=1&yv=1&rv=1"
        )

        var failure: java.io.IOException? = null
        for (url in urls) {
            val response = try { request(url) } catch (error: java.io.IOException) {
                failure = error
                continue
            }
            if (response.code !in 200..299) {
                failure = java.io.IOException("网易云音乐 HTTP ${response.code}")
                continue
            }
            val json = JSONObject(response.body)
            if (json.optInt("code", -1) != 200) {
                failure = java.io.IOException("网易云音乐歌词服务请求失败")
                continue
            }
            if (json.optJSONObject("lrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optJSONObject("yrc")?.optString("lyric").orEmpty().isNotBlank() ||
                json.optBoolean("nolyric", false)) return json
        }
        failure?.let { throw it }
        return null
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
        val spacingRegex = Regex("[　\\s]")
        val punctuationRegex = Regex("""[!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~？！，。、《》【】「」]""")
        val creditRegex = Regex(
            "^(\\s?作?\\s*词|\\s?作?\\s*曲|\\s?编\\s*曲?|\\s?监\\s*制?|.*编写|.*和音|.*和声|.*合声|.*提琴|.*录|.*工程|.*工作室|.*设计|.*剪辑|.*制作|.*发行|.*出品|.*后期|.*混音|.*缩混|原唱|翻唱|题字|文案|海报|古筝|二胡|钢琴|吉他|贝斯|笛子|鼓|弦乐|lrc|publish|vocal|guitar|program|produce|write|mix).*(:|：)",
            RegexOption.IGNORE_CASE
        )
    }
}
