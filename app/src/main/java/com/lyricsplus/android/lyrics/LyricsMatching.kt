package com.lyricsplus.android.lyrics

import com.lyricsplus.android.data.LyricsLine
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class SongMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Double = 0.0,
    val artistAliases: List<String> = emptyList(),
    val artistCredits: List<List<String>> = emptyList()
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
    private val featuredCredit = Regex(
        """\s*[\[(（]\s*(?:feat\.?|ft\.?)\s+[^\])）]*[\])）]\s*|\s+(?:feat\.?|ft\.?)\s+.+$""",
        RegexOption.IGNORE_CASE
    )
    private val syllableTiming = Regex("""<\d{2}:\d{2}[.:]\d{1,3}>|\(\d+,\d+(?:,\d+)?\)""")
    private val creditSeparator = Regex("""\s*(?:&|、|;|,|／|\s/\s)\s*""")
    private val annotations = Regex("""[\[(（]([^\])）]+)[\])）]|\s+[-–—]\s+(.+)$""")
    private val versions = listOf(
        Regex("""\blive\b|现场|現場|演唱会|演唱會""", RegexOption.IGNORE_CASE),
        Regex("""\bremix\b|\bdj\b|混音""", RegexOption.IGNORE_CASE),
        Regex("""\bacoustic\b|不插电|不插電""", RegexOption.IGNORE_CASE),
        Regex("""\binstrumental\b|\bkaraoke\b|\boff\s*vocal\b|伴奏""", RegexOption.IGNORE_CASE),
        Regex("""\bcover\b|翻唱""", RegexOption.IGNORE_CASE),
        Regex("""\bmovie\s*ver""", RegexOption.IGNORE_CASE),
        Regex("""\boriginal\s*ver""", RegexOption.IGNORE_CASE),
        Regex("""\btv\s*(?:ver|size)|电视版|電視版""", RegexOption.IGNORE_CASE),
        Regex("""\bsped\s*up\b|加速""", RegexOption.IGNORE_CASE),
        Regex("""\bslowed\b|降速""", RegexOption.IGNORE_CASE)
    )

    fun searchTitles(title: String): List<String> =
        listOf(title.trim(), baseTitle(title)).filter { it.isNotBlank() }.distinct()

    private fun baseTitle(title: String): String =
        title.replace(featuredCredit, " ")
            .replace(bracketedRemaster, " ").replace(trailingRemaster, "").trim()

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
        // A word-timing bonus must never rescue an accompaniment, remix or clip
        // of another recording. Only explicit edition annotations count as tags.
        if (versionTags(expected.title) != versionTags(actual.title)) return null
        if (knownDuration(expected.durationSeconds) && knownDuration(actual.durationSeconds) &&
            abs(expected.durationSeconds - actual.durationSeconds) > max(15.0, expected.durationSeconds * 0.10)) return null
        val titleScore = textScore(
            normalized(baseTitle(expected.title)), normalized(baseTitle(actual.title)), 50, 20
        )
        if (titleScore == 0) return null

        var points = titleScore
        var available = 50
        if (expected.artist.isNotBlank()) {
            val expectedArtist = normalized(expected.artist)
            val artistScore = when {
                expectedArtist.isNotBlank() && actual.artistAliases.any { normalized(it) == expectedArtist } -> 40
                sameArtistCredits(expected.artist, actual.artistCredits) -> 40
                else -> textScore(expectedArtist, normalized(actual.artist), 40, 25)
            }
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
        if (knownDuration(expected.durationSeconds) && knownDuration(actual.durationSeconds)) {
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

    private fun knownDuration(seconds: Double): Boolean = seconds > 0 && seconds.isFinite()

    private fun versionTags(title: String): Set<Int> {
        val labels = annotations.findAll(baseTitle(title)).joinToString(" ") { it.groupValues.drop(1).joinToString(" ") }
        return versions.indices.filter { versions[it].containsMatchIn(labels) }.toSet()
    }

    private fun sameArtistCredits(expected: String, actual: List<List<String>>): Boolean {
        val names = expected.split(creditSeparator).map(::normalized).filter(String::isNotBlank)
        if (names.size < 2 || names.size > 8 || names.size != actual.size) return false
        val credits = actual.map { variants -> variants.map(::normalized).toSet() }
        fun assign(index: Int, used: Set<Int>): Boolean {
            if (index == names.size) return true
            return credits.indices.any { it !in used && names[index] in credits[it] && assign(index + 1, used + it) }
        }
        return assign(0, emptySet())
    }

    fun selectionScore(matchScore: Int?, lyrics: List<LyricsLine>): Int =
        (matchScore ?: 0) + if (lyrics.any { syllableTiming.containsMatchIn(it.text) }) SYLLABLE_BONUS else 0
}
