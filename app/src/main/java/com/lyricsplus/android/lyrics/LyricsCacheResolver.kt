package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import java.util.concurrent.ConcurrentHashMap

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
        if (prepared != null && !LyricsCachePolicy.shouldRefresh(prepared, now())) {
            if (prepared != cached) {
                save(prepared)
                onUpdate(prepared.result)
            }
            return Result.success(prepared.result)
        }

        val checked = ConcurrentHashMap<String, Boolean>()
        suspend fun check(source: String): Result<CachedLyricsResult> {
            val result = request(source, cached != null)
            val error = result.exceptionOrNull()
            // A genuine empty search is a completed check; transport failures need a retry.
            checked[source] = error == null || error is LyricsSearchException
            return result
        }
        val result = race.run(
            primary = listOf({ check("网易云音乐") }, { check("QQ音乐") }),
            fallback = { request("LRCLIB", false) },
            onUpdate = { candidate -> if (candidate != cached?.result) onUpdate(candidate) },
            cached = prepared?.result
        )
        result.getOrNull()?.let { resolved ->
            val checkedAt = now()
            val entry = LyricsCacheEntry(
                result = resolved,
                cachedAt = if (resolved == prepared?.result) prepared.cachedAt else checkedAt,
                checkedAt = checkedAt,
                checkSucceeded = checked.size == 2 && checked.values.all { it }
            )
            save(entry)
        }
        return result
    }
}
