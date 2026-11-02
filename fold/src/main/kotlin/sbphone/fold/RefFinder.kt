package sbphone.fold

/**
 * Finds Bible references written in running text: "John 3:16", "Rom 8:28-30", "1 John 1:1-3",
 * "Song of Songs 2:1". Only references that read as exactly one book are returned.
 */
object RefFinder {
    private val pattern = Regex("""((?:[1-3]\s?)?[A-Za-z]{2,}\.?(?:\s(?:of\s)?Songs?)?)\s(\d{1,3}):(\d{1,3})(?:\s?[-–]\s?(\d{1,3}))?""")

    data class Found(val range: IntRange, val text: String, val ref: Ref)

    fun findAll(text: String): List<Found> = pattern.findAll(text).mapNotNull { m ->
        val parsed = RefParser.parse(m.value)
        if (parsed is RefParse.One && parsed.ref.verse > 0) Found(m.range, m.value, parsed.ref) else null
    }.toList()
}
