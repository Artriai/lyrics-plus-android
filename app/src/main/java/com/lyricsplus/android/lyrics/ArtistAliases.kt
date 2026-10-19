package com.lyricsplus.android.lyrics

/** Variants come only from artist metadata, never track titles or descriptions. */
internal object ArtistAliases {
    private val parentheticalName = Regex("""^(.+?)\s*[（(]([^()（）]+)[)）]$""")

    fun names(name: String, displayName: String = "", aliases: List<String> = emptyList()): List<String> =
        (listOf(name, displayName) + aliases + listOfNotNull(
            parentheticalName.matchEntire(displayName)?.groupValues?.get(1),
            parentheticalName.matchEntire(displayName)?.groupValues?.get(2)
        )).map(String::trim).filter(String::isNotBlank).distinct()

    /** Keep every credited performer in a variant; one duet member is not an exact duet match. */
    fun credits(artists: List<List<String>>): List<String> {
        if (artists.isEmpty() || artists.any { it.isEmpty() }) return emptyList()
        return artists.fold(listOf("")) { prefixes, names ->
            prefixes.flatMap { prefix -> names.map { "$prefix $it".trim() } }.distinct().take(32)
        }
    }
}
