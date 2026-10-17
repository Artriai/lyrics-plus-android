package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class QQMusicClient {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            LyricsSearch.find(
                source = "QQ 音乐",
                expected = SongMetadata(track.track, track.artist, track.album, track.durationSeconds.toDouble()),
                search = ::searchSongs,
                fetch = { fetchLyrics(it.value) }
            )
        }
    }

    private fun fetchLyrics(songMid: String): List<LyricsLine> {
        val officialResult = runCatching { fetchOfficialLyrics(songMid) }
        officialResult.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        val lyricData = fetchLegacyLyrics(songMid)
        if (lyricData == null || lyricData.optString("lyric").isBlank()) {
            officialResult.exceptionOrNull()?.let { throw it }
            return emptyList()
        }
        val lyricBase64 = lyricData.optString("lyric").orEmpty()
        val transBase64 = lyricData.optString("trans").orEmpty()
        if (lyricBase64.isBlank()) return emptyList()

        val rawLyric = unescapeHtml(String(
            android.util.Base64.decode(lyricBase64, android.util.Base64.DEFAULT), Charsets.UTF_8
        ))
        val rawTrans = if (transBase64.isNotBlank()) unescapeHtml(String(
            android.util.Base64.decode(transBase64, android.util.Base64.DEFAULT), Charsets.UTF_8
        )) else ""
        val synced = LrcParser.parse(rawLyric)
        val translation = LrcParser.parse(rawTrans).filter(::isUsefulAuxiliaryLine)
        return mergeTranslation(synced, translation)
    }

    private fun fetchOfficialLyrics(songMid: String): List<LyricsLine> {
        val data = fetchOfficialLyricData(songMid) ?: return emptyList()
        val encrypted = data.optInt("crypt") == 1

        fun decodeField(name: String): String {
            val value = data.optString(name).orEmpty()
            if (value.isBlank()) return ""

            val decrypted = if (encrypted) {
                QqMusicQrcDecryptor.decrypt(value)
            } else {
                value
            }
            return QrcPayloadParser.extract(decrypted)
        }

        val rawLyric = decodeField("lyric")
        if (rawLyric.isBlank()) return emptyList()

        val synced = LrcParser.parse(rawLyric)
        val translation = LrcParser.parse(decodeField("trans")).filter(::isUsefulAuxiliaryLine)
        val reading = LrcParser.parse(decodeField("roma"))

        val mergedTranslation = mergeTranslation(synced, translation)
        val mergedReading = mergeReading(mergedTranslation, reading)
        return mergedReading
    }

    private fun isUsefulAuxiliaryLine(line: LyricsLine): Boolean {
        val clean = line.text.trim()
        return !(clean.all { it == '/' } && clean.isNotEmpty())
    }

    private fun unescapeHtml(text: String): String {
        if (text.isBlank()) return text
        val temp = text.replace("\n", "__NEWLINE_PLACEHOLDER__")
        val unescaped = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(temp, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(temp).toString()
        }
        return unescaped.replace("__NEWLINE_PLACEHOLDER__", "\n")
    }

    private fun searchSongs(query: LyricsSearchQuery): List<LyricsCandidate<String>> {
        // Smartbox is still the available search endpoint. The shared search
        // planner retries remaster-free titles and then title-only keywords.
        val url = QQ_MUSIC_SEARCH_API.toHttpUrl().newBuilder()
            .addQueryParameter("key", "${query.title} ${query.artist}".trim())
            .addQueryParameter("format", "json")
            .addQueryParameter("g_tk", "5381")
            .addQueryParameter("uin", "0")
            .build()
        val response = requestGet(url.toString())
        if (response.code !in 200..299) throw java.io.IOException("QQ 音乐 HTTP ${response.code}")
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 0) throw java.io.IOException("QQ 音乐搜索服务请求失败")
        val songs = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist")
            ?: return emptyList()
        return (0 until songs.length()).mapNotNull { index ->
            val song = songs.getJSONObject(index)
            val mid = song.optString("mid")
            if (mid.isBlank()) null else LyricsCandidate(
                mid, SongMetadata(song.optString("name"), song.optString("singer")), mid
            )
        }
    }

    private fun fetchOfficialLyricData(songMid: String): JSONObject? {
        val params = JSONObject()
            .put("songMid", songMid)
            .put("crypt", 1)
            .put("lrc_t", 0)
            .put("qrc", 1)
            .put("qrc_t", 0)
            .put("trans", 1)
            .put("trans_t", 0)
            .put("roma", 1)
            .put("roma_t", 0)
            .put("type", 1)
            .put("ct", QQ_MUSIC_CLIENT_TYPE)
            .put("cv", QQ_MUSIC_CLIENT_VERSION)

        val requestData = JSONObject()
            .put("module", "music.musichallSong.PlayLyricInfo")
            .put("method", "GetPlayLyricInfo")
            .put("param", params)

        val common = JSONObject()
            .put("ct", QQ_MUSIC_CLIENT_TYPE)
            .put("cv", QQ_MUSIC_CLIENT_VERSION)
            .put("v", QQ_MUSIC_CLIENT_VERSION)
            .put("uin", "0")
            .put("format", "json")

        val payload = JSONObject()
            .put("comm", common)
            .put("req_0", requestData)

        val response = requestPost(
            url = MUSIC_U_API,
            jsonPayload = payload.toString(),
            userAgent = QQ_MUSIC_ANDROID_USER_AGENT
        )
        if (response.code !in 200..299) throw java.io.IOException("QQ 音乐 HTTP ${response.code}")

        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 0) throw java.io.IOException("QQ 音乐歌词服务请求失败")
        val requestResult = json.optJSONObject("req_0")
            ?: throw java.io.IOException("QQ 音乐歌词服务响应不完整")
        if (requestResult.optInt("code", -1) != 0) throw java.io.IOException("QQ 音乐歌词服务请求失败")
        return requestResult.optJSONObject("data")
    }

    private fun fetchLegacyLyrics(songMid: String): JSONObject? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&g_tk=5381"
        val response = requestGet(url)
        if (response.code !in 200..299) throw java.io.IOException("QQ 音乐 HTTP ${response.code}")
        
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 0) throw java.io.IOException("QQ 音乐歌词服务请求失败")
        return json
    }

    private fun mergeTranslation(base: List<LyricsLine>, translation: List<LyricsLine>): List<LyricsLine> {
        return TimedLyricsMerger.mergeTranslation(base, translation)
    }

    private fun mergeReading(base: List<LyricsLine>, reading: List<LyricsLine>): List<LyricsLine> {
        return TimedLyricsMerger.mergeReading(base, reading)
    }

    private fun requestGet(url: String): HttpResponse {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        return HttpClient.okHttpClient.newCall(request).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun requestPost(
        url: String,
        jsonPayload: String,
        userAgent: String = WEB_USER_AGENT
    ): HttpResponse {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonPayload.toRequestBody(mediaType)

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", userAgent)
            .build()

        return HttpClient.okHttpClient.newCall(request).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private companion object {
        const val MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QQ_MUSIC_SEARCH_API = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
        const val QQ_MUSIC_CLIENT_TYPE = 11
        const val QQ_MUSIC_CLIENT_VERSION = 14090008
        const val QQ_MUSIC_ANDROID_USER_AGENT = "QQMusic 14090008(android 15)"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
