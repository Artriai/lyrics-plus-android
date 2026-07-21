package com.lyricsplus.android.lyrics

import android.content.Context
import com.lyricsplus.android.data.LyricsLine
import com.lyricsplus.android.data.NowPlaying
import com.lyricsplus.android.data.LyricsSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

class LyricsProvider(
    context: Context,
    private val qqMusicClient: QQMusicClient = QQMusicClient(),
    private val neteaseClient: NeteaseClient = NeteaseClient(),
    private val lrclibClient: LrclibClient = LrclibClient(),
    private val japaneseReader: JapaneseReadingConverter = JapaneseReadingConverter()
) {
    private val cacheDb = LyricsCacheDatabase(context)
    
    // In-memory cache for temporary source switching (秒切)
    // Key: trackKey|SourceName -> CachedLyricsResult
    private val inMemoryCache = ConcurrentHashMap<String, CachedLyricsResult>()

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

    fun saveToCache(track: NowPlaying, lyrics: List<LyricsLine>, source: String) {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        cacheDb.saveLyrics(trackKey, lyrics, source)
    }

    suspend fun findSyncedLyrics(
        track: NowPlaying,
        preferredSource: String? = null
    ): Result<CachedLyricsResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
            
            if (preferredSource != null) {
                return@runCatching findSyncedLyricsForSource(track, preferredSource).getOrThrow()
            }

            // Check SQLite database cache first
            val cached = cacheDb.getLyrics(trackKey)
            if (cached != null) {
                // If the cached lyrics are missing 'reading' (Romaji/Furigana) but contain Japanese kana,
                // generate them on-the-fly and update the cache so the user doesn't get stuck!
                val hasKana = cached.lyrics.any { it.text.hasJapaneseKana() }
                val needsReading = hasKana && cached.lyrics.any { it.reading.isNullOrBlank() }
                val finalLyrics = if (needsReading) {
                    val processed = cached.lyrics.map { line ->
                        if (line.reading.isNullOrBlank()) {
                            val cleanText = line.text.replace(timestampStripRegex, "").trim()
                            val romaji = japaneseReader.readingFor(cleanText)
                            val furigana = japaneseReader.furiganaFor(cleanText)
                            val reading = if (romaji != null || furigana != null) {
                                org.json.JSONObject().apply {
                                    put("romaji", romaji.orEmpty())
                                    put("furigana", furigana.orEmpty())
                                }.toString()
                            } else null
                            line.copy(reading = reading)
                        } else {
                            line
                        }
                    }
                    // Save back to DB cache so we don't have to re-generate next time
                    cacheDb.saveLyrics(trackKey, processed, cached.source)
                    processed
                } else {
                    cached.lyrics
                }
                // Return cached version with perfect score to avoid background override
                return@runCatching CachedLyricsResult(finalLyrics, cached.source, score = 140)
            }

            // Clear in-memory cache for this track key on starting fresh
            inMemoryCache.keys.filter { it.startsWith("$trackKey|") }.forEach { inMemoryCache.remove(it) }

            // Fetch NetEase and QQ Music concurrently in parallel
            val neteaseDeferred = async { runCatching { findSyncedLyricsForSource(track, "网易云音乐").getOrThrow() } }
            val qqDeferred = async { runCatching { findSyncedLyricsForSource(track, "QQ音乐").getOrThrow() } }

            val neteaseResult = neteaseDeferred.await()
            val qqResult = qqDeferred.await()

            val candidates = mutableListOf<Pair<CachedLyricsResult, String>>()
            neteaseResult.getOrNull()?.let { candidates.add(it to "网易云音乐") }
            qqResult.getOrNull()?.let { candidates.add(it to "QQ音乐") }

            val bestCandidate = if (candidates.isNotEmpty()) {
                candidates.maxByOrNull { (result, _) ->
                    var finalScore = result.score
                    // Give a massive +100 bonus to syllable-level (YRC/QRC) lyrics
                    val hasSyllables = result.lyrics.any { line -> timestampStripRegex.containsMatchIn(line.text) }
                    if (hasSyllables) {
                        finalScore += 100
                    }
                    // Tie-breaker: prefer NetEase over QQ Music if score is equal
                    finalScore
                }
            } else null

            val resolvedPair = if (bestCandidate != null) {
                bestCandidate
            } else {
                // Fallback to LRCLIB if both NetEase and QQ Music failed
                val lrclib = runCatching { findSyncedLyricsForSource(track, "LRCLIB").getOrThrow() }.getOrNull()
                if (lrclib != null) {
                    lrclib to "LRCLIB"
                } else {
                    null
                }
            }

            if (resolvedPair == null) {
                val neteaseErr = neteaseResult.exceptionOrNull()
                val qqErr = qqResult.exceptionOrNull()
                if (neteaseErr is java.io.IOException || qqErr is java.io.IOException) {
                    throw (neteaseErr as? java.io.IOException) ?: (qqErr as? java.io.IOException) ?: neteaseErr ?: qqErr ?: Exception("网络错误")
                } else {
                    val instrumental = listOf(LyricsLine(0L, "纯音乐 / 无歌词"))
                    val result = CachedLyricsResult(instrumental, "纯音乐", score = 0)
                    inMemoryCache["$trackKey|纯音乐"] = result
                    return@runCatching result
                }
            }

            val resolved = resolvedPair.first
            val resolvedSource = resolvedPair.second

            // Cache the best matched result to SQLite database
            cacheDb.saveLyrics(trackKey, resolved.lyrics, resolvedSource)
            resolved
        }
    }

    suspend fun findSyncedLyricsForSource(
        track: NowPlaying,
        sourceName: String
    ): Result<CachedLyricsResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
            val memKey = "$trackKey|$sourceName"
            
            val memCached = inMemoryCache[memKey]
            if (memCached != null) {
                return@runCatching memCached
            }

            val searchResult = when (sourceName) {
                "QQ音乐" -> qqMusicClient.findSyncedLyrics(track).getOrThrow()
                "网易云音乐" -> neteaseClient.findSyncedLyrics(track).getOrThrow()
                "LRCLIB" -> lrclibClient.findSyncedLyrics(track).getOrThrow()
                else -> error("未知歌词源: $sourceName")
            }

            val base = searchResult.lyrics
            val hasKana = base.any { line -> line.text.hasJapaneseKana() }

            val processed = base.map { line ->
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

            val result = CachedLyricsResult(processed, sourceName, searchResult.score)
            inMemoryCache[memKey] = result
            result
        }
    }

    private fun String.hasJapaneseKana(): Boolean =
        any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }

    fun isCached(track: NowPlaying): Boolean {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        if (inMemoryCache.keys.any { it.startsWith("$trackKey|") }) return true
        return cacheDb.hasLyrics(trackKey)
    }

    fun isCachedForSource(track: NowPlaying, sourceName: String): Boolean {
        val trackKey = listOf(track.track, track.artist, track.album, track.durationSeconds).joinToString("|")
        return inMemoryCache.containsKey("$trackKey|$sourceName")
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
