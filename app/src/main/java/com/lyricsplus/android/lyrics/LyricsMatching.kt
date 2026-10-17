package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Double = 0.0
)

/** Shared matching rules for QQ Music, NetEase and LRCLIB. */
object LyricsMatching {
    const val MAX_MATCH_SCORE = 100
    const val SYLLABLE_BONUS = 100

    // Only remove a remaster annotation, never arbitrary subtitles or Live/Remix versions.
    private const val REMASTER = "(?:\\d{4}\\s+)?remaster(?:ed)?(?:\\s+\\d{4})?"
    private val bracketedRemaster = Regex(
        """\s*[\[(（]\s*$REMASTER\s*[\])）]\s*""", RegexOption.IGNORE_CASE
    )
    private val trailingRemaster = Regex(
        """\s+[-–—]\s*$REMASTER\s*$""", RegexOption.IGNORE_CASE
    )
    private val syllableTiming = Regex("""<\d{2}:\d{2}[.:]\d{1,3}>|\(\d+,\d+(?:,\d+)?\)""")

    fun searchTitles(title: String): List<String> =
        listOf(title.trim(), baseTitle(title)).filter { it.isNotBlank() }.distinct()

    private fun baseTitle(title: String): String =
        title.replace(bracketedRemaster, " ").replace(trailingRemaster, "").trim()

    private fun normalized(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun textScore(expected: String, actual: String, exact: Int, partial: Int): Int {
        if (expected.isBlank() || actual.isBlank()) return 0
        if (expected == actual) return exact
        return if (minOf(expected.length, actual.length) >= 3 &&
            (expected.contains(actual) || actual.contains(expected))) partial else 0
    }

    /** Null rejects unrelated candidates before the word-timing bonus is considered. */
    fun score(expected: SongMetadata, actual: SongMetadata): Int? {
        val titleScore = textScore(
            normalized(baseTitle(expected.title)), normalized(baseTitle(actual.title)), 50, 20
        )
        if (titleScore == 0) return null

        var points = titleScore
        var available = 50
        if (expected.artist.isNotBlank()) {
            val artistScore = textScore(normalized(expected.artist), normalized(actual.artist), 40, 25)
            if (artistScore == 0) return null
            points += artistScore
            available += 40
        }
        // QQ's smartbox supplies neither album nor duration. Unknown fields do
        // not reduce its score; compare all sources on the same 0..100 scale.
        if (expected.album.isNotBlank() && actual.album.isNotBlank()) {
            available += 20
            if (normalized(expected.album) == normalized(actual.album)) points += 20
        }
        if (expected.durationSeconds > 0 && actual.durationSeconds > 0) {
            available += 30
            val difference = abs(expected.durationSeconds - actual.durationSeconds)
            points += when {
                difference < 3 -> 30
                difference < 10 -> 10
                else -> 0
            }
        }
        return (points.toDouble() / available * MAX_MATCH_SCORE).roundToInt()
    }

    fun selectionScore(matchScore: Int, lyrics: List<LyricsLine>): Int =
        matchScore + if (lyrics.any { syllableTiming.containsMatchIn(it.text) }) SYLLABLE_BONUS else 0
}
