package com.lyricsplus.android.lyrics

/** Extracts the timed lyric body from a decrypted QQ Music QRC XML payload. */
object QrcPayloadParser {
    private val lyricContentRegex = Regex(
        pattern = """<Lyric_1\b[^>]*\bLyricContent\s*=\s*\"([\s\S]*?)\"""",
        option = RegexOption.IGNORE_CASE
    )
    private val numericEntityRegex = Regex("""&#(x[0-9a-fA-F]+|[0-9]+);""")

    fun extract(decryptedPayload: String): String {
        if (!decryptedPayload.contains("<QrcInfos", ignoreCase = true)) {
            return decryptedPayload
        }

        val encoded = lyricContentRegex.find(decryptedPayload)?.groupValues?.get(1)
            ?: error("QRC XML does not contain LyricContent")
        return decodeXmlEntities(encoded)
    }

    private fun decodeXmlEntities(value: String): String {
        val numericDecoded = numericEntityRegex.replace(value) { match ->
            val raw = match.groupValues[1]
            val codePoint = if (raw.startsWith('x', ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }

            codePoint
                ?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }

        return numericDecoded
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
