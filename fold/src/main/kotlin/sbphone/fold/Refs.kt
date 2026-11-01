package sbphone.fold

/** A verse address. [verse] 0 means "the whole chapter". */
data class Ref(val book: Int, val chapter: Int, val verse: Int = 0, val verseEnd: Int = 0) {

    fun label(): String {
        val b = Books.byId(book)
        return when {
            verse == 0 -> "${b.name} $chapter"
            verseEnd > verse -> "${b.name} $chapter:$verse-$verseEnd"
            else -> "${b.name} $chapter:$verse"
        }
    }

    fun shortLabel(): String {
        val b = Books.byId(book)
        return when {
            verse == 0 -> "${b.short} $chapter"
            verseEnd > verse -> "${b.short} $chapter:$verse-$verseEnd"
            else -> "${b.short} $chapter:$verse"
        }
    }

    /** One integer per verse: book * 1_000_000 + chapter * 1_000 + verse. */
    val key: Int get() = key(book, chapter, verse)

    companion object {
        fun key(book: Int, chapter: Int, verse: Int): Int = book * 1_000_000 + chapter * 1_000 + verse
        fun fromKey(key: Int): Ref = Ref(key / 1_000_000, (key / 1_000) % 1_000, key % 1_000)
    }
}

/** The outcome of reading a typed reference: one reading, a choice to offer, or nothing. */
sealed interface RefParse {
    data class One(val ref: Ref) : RefParse
    data class Choice(val refs: List<Ref>) : RefParse
    data object NotARef : RefParse
}

/**
 * Reads loose references: "jn 3 16", "John 3:16", "1pet 1:3", "1 Peter 1", "ps 23",
 * "Rom 8:28-30", "song 2". An ambiguous book ("ju 1": Judges or Jude) gives a choice,
 * never a guess.
 */
object RefParser {

    private val pattern = Regex("""^\s*([1-3]?\s*[a-z][a-z .]*?)\s*(\d+)?(?:\s*[:. ]\s*(\d+))?(?:\s*[-–]\s*(\d+))?\s*$""")

    private fun norm(s: String) = s.lowercase().replace(".", "").replace(" ", "")

    private val exact: Map<String, Book> = buildMap {
        for (book in Books.all) {
            put(norm(book.name), book)
            put(norm(book.short), book)
            put(norm(book.code), book)
            for (a in book.aliases) put(norm(a), book)
        }
    }

    fun booksMatching(typed: String): List<Book> {
        val key = norm(typed)
        if (key.isEmpty()) return emptyList()
        exact[key]?.let { return listOf(it) }
        return Books.all.filter { norm(it.name).startsWith(key) }
    }

    fun parse(input: String): RefParse {
        val m = pattern.matchEntire(input.lowercase()) ?: return RefParse.NotARef
        val (bookPart, ch, vs, ve) = m.destructured
        val books = booksMatching(bookPart)
        if (books.isEmpty()) return RefParse.NotARef
        val chapter = ch.toIntOrNull() ?: 1
        val verse = vs.toIntOrNull() ?: 0
        val verseEnd = ve.toIntOrNull() ?: 0
        val refs = books.map { Ref(it.id, chapter, verse, if (verseEnd > verse) verseEnd else 0) }
        return if (refs.size == 1) RefParse.One(refs[0]) else RefParse.Choice(refs)
    }
}
