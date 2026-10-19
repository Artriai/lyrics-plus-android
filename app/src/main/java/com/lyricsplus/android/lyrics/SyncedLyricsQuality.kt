package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import java.text.Normalizer

/** A timestamped title/credits block or short preview is not usable synced lyrics. */
internal object SyncedLyricsQuality {
    private val wordTime = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")
    private val enhancedTime = Regex("""<(\d+):(\d+)[.:](\d{1,3})>""")
    private val credit = Regex(
        """^(?:(?:.*(?:作词|作曲|编曲|制作|录制|工程|混音|监制|出品|发行|版权)[^:：]*|词|曲|录音|和声|吉他|贝斯|鼓|弦乐)|(?:lyrics|written|composed|composer|produced|producer|arranged|arrangement|mixed|mastered|recorded|published|vocals?|guitar|bass|drums)\b[^:：]*)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    fun usable(lines: List<LyricsLine>, title: String, durationSeconds: Double): List<LyricsLine> {
        if (lines.size == 1 && lines[0].text == "♪ 纯音乐 ♪") return lines
        val body = lines.filterIndexed { index, line ->
            val text = line.text.replace(wordTime, "").replace(enhancedTime, "").trim()
            text.any(Char::isLetterOrDigit) && !credit.containsMatchIn(text) &&
                !(index == 0 && normalized(text.substringBefore(" - ")) == normalized(title))
        }
        if (body.isEmpty()) return emptyList()
        // QQ sometimes returns only the first ~15 seconds. Let its client try
        // the full LRC endpoint instead of giving that preview a +100 bonus.
        val lastTime = body.maxOf { line ->
            maxOf(line.startTimeMs, wordTime.findAll(line.text).maxOfOrNull {
                (it.groupValues[1].toLongOrNull() ?: 0) + (it.groupValues[2].toLongOrNull() ?: 0)
            } ?: 0, enhancedTime.findAll(line.text).maxOfOrNull {
                val (minutes, seconds, fraction) = it.destructured
                (minutes.toLongOrNull() ?: 0) * 60_000 + (seconds.toLongOrNull() ?: 0) * 1000 +
                    (fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0)
            } ?: 0)
        }
        if (durationSeconds.isFinite() && durationSeconds >= 60 &&
            lastTime < minOf(30_000.0, durationSeconds * 250)) return emptyList()
        return body
    }

    fun cache(entry: LyricsCacheEntry?, title: String, durationSeconds: Double): LyricsCacheEntry? {
        entry ?: return null
        val lyrics = usable(entry.result.lyrics, title, durationSeconds)
        if (lyrics.isEmpty()) return null
        return entry.copy(result = entry.result.copy(lyrics = lyrics))
    }

    private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
}
