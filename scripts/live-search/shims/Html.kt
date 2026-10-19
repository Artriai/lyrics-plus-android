package android.text
object Html {
    const val FROM_HTML_MODE_LEGACY = 0
    fun fromHtml(value: String): CharSequence = org.jsoup.parser.Parser.unescapeEntities(value, false)
    fun fromHtml(value: String, flags: Int): CharSequence = fromHtml(value)
}
