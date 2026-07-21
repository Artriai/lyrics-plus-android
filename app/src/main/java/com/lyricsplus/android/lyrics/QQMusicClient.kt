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
import kotlin.math.abs

class QQMusicClient {
    suspend fun findSyncedLyrics(track: NowPlaying): Result<LyricsSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val searchResult = searchSongMid(track) ?: error("QQ 音乐未找到歌曲")

            // Prefer QQ Music's first-party API. It returns encrypted QRC data,
            // which is decrypted locally without relying on a third-party service.
            val officialResult = runCatching {
                fetchOfficialLyrics(searchResult)
            }
            if (officialResult.isSuccess) {
                return@runCatching officialResult.getOrThrow()
            }

            // The legacy endpoint remains the last fallback for clients or songs
            // that do not expose PlayLyricInfo/QRC data.
            val lyricData = fetchLegacyLyrics(searchResult.mid)
                ?: error("QQ 音乐未找到歌词 (新版和旧版接口均失败)")
            
            val lyricBase64 = lyricData.optString("lyric").orEmpty()
            val transBase64 = lyricData.optString("trans").orEmpty()
            
            if (lyricBase64.isBlank()) error("QQ 音乐歌词为空")

            val rawLyricEncoded = String(android.util.Base64.decode(lyricBase64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val rawTransEncoded = if (transBase64.isNotBlank()) {
                String(android.util.Base64.decode(transBase64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else ""

            val rawLyric = unescapeHtml(rawLyricEncoded)
            val rawTrans = unescapeHtml(rawTransEncoded)

            val synced = LrcParser.parse(rawLyric).ifEmpty { error("QQ 音乐同步歌词为空") }
            val translation = LrcParser.parse(rawTrans).filter { line ->
                val clean = line.text.trim()
                !(clean.all { it == '/' } && clean.isNotEmpty())
            }

            val merged = mergeTranslation(synced, translation)
            LyricsSearchResult(merged, searchResult.score)
        }
    }

    private fun fetchOfficialLyrics(searchResult: QQMusicSearchResult): LyricsSearchResult {
        val data = fetchOfficialLyricData(searchResult.mid)
            ?: error("QQ 音乐新版歌词接口无数据")
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
        if (rawLyric.isBlank()) error("QQ 音乐新版歌词为空")

        val synced = LrcParser.parse(rawLyric)
            .ifEmpty { error("QQ 音乐新版歌词解析为空") }
        val translation = LrcParser.parse(decodeField("trans")).filter(::isUsefulAuxiliaryLine)
        val reading = LrcParser.parse(decodeField("roma"))

        val mergedTranslation = mergeTranslation(synced, translation)
        val mergedReading = mergeReading(mergedTranslation, reading)
        return LyricsSearchResult(mergedReading, searchResult.score)
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

    private fun searchSongMid(track: NowPlaying): QQMusicSearchResult? {
        val query = "${track.track} ${track.artist}"

        // The unsigned SearchCgiService musicu request now returns an empty
        // song list. QQ's public search endpoint still provides the MID needed
        // by GetPlayLyricInfo, while lyric retrieval itself remains on musicu.
        val url = QQ_MUSIC_SEARCH_API.toHttpUrl().newBuilder()
            .addQueryParameter("w", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("p", "1")
            .addQueryParameter("n", "10")
            .addQueryParameter("aggr", "1")
            .addQueryParameter("cr", "1")
            .build()
        val response = requestGet(url.toString())
        if (response.code !in 200..299) return null

        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 0) return null
        val songs = json.optJSONObject("data")
            ?.optJSONObject("song")
            ?.optJSONArray("list")
            ?: return null

        var bestMid: String? = null
        var bestScore = -1

        val normalizedTitle = track.track.lowercase().replace("\\s+".toRegex(), "")
        val normalizedArtist = track.artist.lowercase().replace("\\s+".toRegex(), "")
        val normalizedAlbum = track.album.lowercase().replace("\\s+".toRegex(), "")

        for (i in 0 until songs.length()) {
            val song = songs.getJSONObject(i)
            val name = song.optString("songname").lowercase().replace("\\s+".toRegex(), "")
            val album = song.optString("albumname").lowercase().replace("\\s+".toRegex(), "")
            
            val singersArray = song.optJSONArray("singer")
            val singers = StringBuilder()
            if (singersArray != null) {
                for (j in 0 until singersArray.length()) {
                    singers.append(singersArray.getJSONObject(j).optString("name")).append(" ")
                }
            }
            val artistStr = singers.toString().lowercase().replace("\\s+".toRegex(), "")
            
            val duration = song.optLong("interval")
            val expectedDuration = track.durationSeconds.toLong()
            val durationDiff = if (expectedDuration > 0) abs(expectedDuration - duration) else Long.MAX_VALUE

            var score = 0
            if (name == normalizedTitle) score += 50
            else if (normalizedTitle.isNotBlank() && (name.contains(normalizedTitle) || normalizedTitle.contains(name))) score += 20
            
            if (normalizedArtist.isNotBlank() && (artistStr.contains(normalizedArtist) || normalizedArtist.contains(artistStr))) score += 40
            if (normalizedAlbum.isNotBlank() && album == normalizedAlbum) score += 20
            
            if (durationDiff < 3) score += 30
            else if (durationDiff < 10) score += 10

            if (score > bestScore && score > 0) {
                bestScore = score
                bestMid = song.optString("songmid")
            }
        }

        return bestMid?.let { QQMusicSearchResult(it, bestScore) }
    }

    private data class QQMusicSearchResult(val mid: String, val score: Int)

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
        if (response.code !in 200..299) return null

        val requestResult = JSONObject(response.body).optJSONObject("req_0") ?: return null
        if (requestResult.optInt("code", -1) != 0) return null
        return requestResult.optJSONObject("data")
    }

    private fun fetchLegacyLyrics(songMid: String): JSONObject? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&format=json&g_tk=5381"
        val response = requestGet(url)
        if (response.code !in 200..299) return null
        
        return JSONObject(response.body)
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
        const val QQ_MUSIC_SEARCH_API = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        const val QQ_MUSIC_CLIENT_TYPE = 11
        const val QQ_MUSIC_CLIENT_VERSION = 14090008
        const val QQ_MUSIC_ANDROID_USER_AGENT = "QQMusic 14090008(android 15)"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
