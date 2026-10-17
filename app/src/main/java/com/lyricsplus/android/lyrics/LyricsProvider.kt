package com.lyricsplus.android.lyrics

import android.content.Context
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

class LyricsProvider(
    context: Context,
    private val qqMusicClient: QQMusicClient = QQMusicClient(),
    private val neteaseClient: NeteaseClient = NeteaseClient(),
    private val lrclibClient: LrclibClient = LrclibClient(),
    private val japaneseReader: JapaneseReadingConverter = JapaneseReadingConverter()
) {
    private val cacheDb = LyricsCacheDatabase(context)
    private val automaticRequests = InFlightLyricsRequests()
    
    // In-memory cache for temporary source switching (秒切)
    // Each source is independent from the automatic winner.
    private val inMemoryCache = ConcurrentHashMap<String, LyricsCacheEntry>()

    init {
        HttpClient.initialize(context)
    }

    fun preWarm() {
        japaneseReader.preWarm()
    }

    fun clearCache(track: NowPlaying) {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        cacheDb.deleteLyrics(trackKey)
        // Clear in-memory cache for this track key
        inMemoryCache.keys.filter { it.startsWith("$trackKey|") }.forEach { inMemoryCache.remove(it) }
    }

    suspend fun findSyncedLyrics(
        track: NowPlaying,
        preferredSource: String? = null,
        onUpdate: suspend (CachedLyricsResult) -> Unit = {}
    ): Result<CachedLyricsResult> {
        val callerContext = currentCoroutineContext()
        return withContext(Dispatchers.IO) {
            runCatching {
                val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
                if (preferredSource != null) {
                    automaticRequests.markManualSelection(trackKey)
                    // A manual selection fills only its own source cache, never the automatic winner.
                    return@runCatching findSyncedLyricsForSource(track, preferredSource).getOrThrow()
                }

                automaticRequests.await(
                    key = trackKey,
                    onUpdate = { candidate -> withContext(callerContext) { onUpdate(candidate) } }
                ) { publish, commit ->
                    LyricsCacheResolver().resolve(
                        cached = cacheDb.getLyrics(trackKey),
                        request = { source, refresh -> findSyncedLyricsForSource(track, source, refresh) },
                        onUpdate = publish,
                        save = { entry -> commit { cacheDb.saveLyrics(trackKey, entry) } },
                        prepare = ::addReadings
                    )
                }.getOrThrow()
            }
        }
    }

    suspend fun findSyncedLyricsForSource(
        track: NowPlaying,
        sourceName: String,
        refresh: Boolean = false
    ): Result<CachedLyricsResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
            val memKey = "$trackKey|$sourceName"
            
            if (!refresh) {
                val cached = inMemoryCache[memKey] ?: cacheDb.getSourceLyrics(trackKey, sourceName)
                if (cached != null && LyricsCachePolicy.canReuseSource(cached, System.currentTimeMillis())) {
                    inMemoryCache[memKey] = cached
                    return@runCatching cached.result
                }
            }

            val searchResult = when (sourceName) {
                "QQ音乐" -> qqMusicClient.findSyncedLyrics(track).getOrThrow()
                "网易云音乐" -> neteaseClient.findSyncedLyrics(track).getOrThrow()
                "LRCLIB" -> lrclibClient.findSyncedLyrics(track).getOrThrow()
                else -> error("未知歌词源: $sourceName")
            }

            val result = CachedLyricsResult(addReadings(searchResult.lyrics), sourceName, searchResult.score)
            val entry = LyricsCacheEntry(result, System.currentTimeMillis())
            cacheDb.saveSourceLyrics(trackKey, entry)
            inMemoryCache[memKey] = entry
            result
        }
    }

    private suspend fun addReadings(base: List<LyricsLine>): List<LyricsLine> {
        val hasKana = base.any { line -> line.text.hasJapaneseKana() }

        val processed = base.map { line ->
            currentCoroutineContext().ensureActive()
            val existingReading = line.reading.orEmpty()
            val reading = if (existingReading.isNotBlank()) {
                if (existingReading.startsWith("{")) {
                    existingReading
                } else {
                    val cleanText = line.text.replace(timestampStripRegex, "").trim()
                    val furigana = japaneseReader.furiganaFor(cleanText)
                    org.json.JSONObject().apply {
                        put("romaji", existingReading)
                        put("furigana", furigana.orEmpty())
                    }.toString()
                }
            } else if (hasKana) {
                val cleanText = line.text.replace(timestampStripRegex, "").trim()
                val romaji = japaneseReader.readingFor(cleanText)
                val furigana = japaneseReader.furiganaFor(cleanText)
                if (romaji != null || furigana != null) {
                    org.json.JSONObject().apply {
                        put("romaji", romaji.orEmpty())
                        put("furigana", furigana.orEmpty())
                    }.toString()
                } else {
                    null
                }
            } else {
                null
            }
            line.copy(reading = reading)
        }
        return processed
    }

    private fun String.hasJapaneseKana(): Boolean =
        any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }

    fun isCached(track: NowPlaying): Boolean {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        return cacheDb.hasLyrics(trackKey)
    }

    fun isCachedForSource(track: NowPlaying, sourceName: String): Boolean {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        return inMemoryCache["$trackKey|$sourceName"]?.let {
            LyricsCachePolicy.canReuseSource(it, System.currentTimeMillis())
        } == true
    }

    companion object {
        @Volatile
        private var instance: LyricsProvider? = null

        private val timestampStripRegex = Regex("<\\d{2}:\\d{2}[.:]\\d{1,3}>|\\(\\d+,\\d+(?:,\\d+)?\\)")

        fun getInstance(context: Context): LyricsProvider {
            return instance ?: synchronized(this) {
                instance ?: LyricsProvider(context.applicationContext).also { instance = it }
            }
        }
    }
}
