package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine

/** Cache hits stay visible while a single, shared refresh collects the primary sources. */
internal class LyricsCacheResolver(
    private val race: LyricsRace = LyricsRace(),
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun resolve(
        cached: LyricsCacheEntry?,
        request: suspend (source: String, refresh: Boolean) -> Result<CachedLyricsResult>,
        onUpdate: suspend (CachedLyricsResult) -> Unit,
        save: suspend (LyricsCacheEntry) -> Unit,
        prepare: suspend (List<LyricsLine>) -> List<LyricsLine> = { it }
    ): Result<CachedLyricsResult> {
        // Display before Japanese reading generation or any network work.
        cached?.let { onUpdate(it.result) }
        val prepared = cached?.copy(result = cached.result.copy(lyrics = prepare(cached.result.lyrics)))
        suspend fun persist(resolved: CachedLyricsResult) {
            save(LyricsCacheEntry(
                result = resolved,
                cachedAt = if (resolved == prepared?.result) prepared.cachedAt else now()
            ))
        }
        val result = race.run(
            primary = listOf({ request("网易云音乐", true) }, { request("QQ音乐", true) }),
            fallback = { request("LRCLIB", true) },
            onUpdate = { candidate -> if (candidate != cached?.result) onUpdate(candidate) },
            cached = prepared?.result,
            onInterrupted = ::persist
        )
        result.getOrNull()?.let { persist(it) }
        return result
    }
}
