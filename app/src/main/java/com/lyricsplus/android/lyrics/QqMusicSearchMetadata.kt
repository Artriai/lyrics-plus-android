package com.lyricsplus.android.lyrics

/** Preserve QQ's version labels while removing localized aliases from song names. */
internal object QqMusicSearchMetadata {
    private val localizedAlias = Regex("""\s*[\[(（]([\p{IsHan}\s·・]+)[\])）]\s*""")

    fun title(displayTitle: String): String = localizedAlias.replace(displayTitle) { match ->
        val label = match.groupValues[1]
        // A localized alias such as 火花 must not interrupt matching the
        // Japanese title and its Movie version. Keep edition/performance labels.
        if (listOf("版", "现场", "混音", "伴奏", "翻唱", "重制").any { it in label }) match.value else " "
    }.trim()
}
