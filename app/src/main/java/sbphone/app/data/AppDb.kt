package sbphone.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import sbphone.fold.Match
import sbphone.fold.Ref
import sbphone.fold.SearchQuery
import java.io.File

data class Version(
    val code: String, val name: String, val language: String, val rtl: Boolean,
    val versification: String, val coverage: String, val firstId: Long, val lastId: Long,
)

data class BookInfo(val id: Int, val code: String, val name: String, val short: String, val chapters: Int)

data class Verse(
    val id: Long, val version: String, val book: Int, val chapter: Int, val verse: Int,
    val engKey: Int, val text: String,
) {
    /** Its own number, shown when it differs from the English numbering (Hebrew versification). */
    val ownLabel: String get() = "$chapter:$verse"
    val differsFromEnglish: Boolean get() = Ref.key(book, chapter, verse) != engKey
}

data class Word(
    val id: Long, val engKey: Int, val book: Int, val chapter: Int, val verse: Int, val pos: Int,
    val surface: String, val after: String, val translit: String, val gloss: String,
    val lemma: Int, val morph: String, val parse: String,
)

data class Lemma(
    val id: Int, val language: String, val headword: String, val translit: String, val pron: String,
    val strongs: String, val pos: String, val count: Int,
    /** H1254a style for Hebrew (keeps homographs apart), Strong's for Greek. */
    val number: String,
    /** First gloss of the first lexicon, to tell homographs apart in lists. */
    val hint: String,
) {
    val isHebrew: Boolean get() = language == "hbo" || language == "arc"
    val languageName: String get() = when (language) { "hbo" -> "Hebrew"; "arc" -> "Aramaic"; else -> "Greek" }
}

data class Sense(val source: String, val entry: String, val depth: Int, val label: String, val gloss: String, val text: String, val domain: String, val note: String)
data class Rendering(val english: String, val n: Int)
data class XRef(val toKey: Int, val toEnd: Int, val votes: Int) {
    fun ref(): Ref = Ref.fromKey(toKey).let { if (toEnd > toKey) it.copy(verseEnd = toEnd % 1000) else it }
}
data class SourceInfo(val code: String, val name: String, val kind: String, val license: String, val credit: String, val url: String, val shareAlike: Boolean)

data class SearchResult(val total: Int, val verses: List<Verse>, val matches: List<Match>)

/**
 * The shipped Bible database (app.db), read only. It is copied out of the APK into
 * private storage on first launch and after every app update.
 */
class AppDb private constructor(private val db: SQLiteDatabase) {

    val versions: List<Version> = db.rawQuery("SELECT code, name, language, rtl, versification, coverage, first_id, last_id FROM version ORDER BY ord", null).use { c ->
        buildList { while (c.moveToNext()) add(Version(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getString(5), c.getLong(6), c.getLong(7))) }
    }
    val books: List<BookInfo> = db.rawQuery("SELECT id, code, name, short, chapters FROM book ORDER BY id", null).use { c ->
        buildList { while (c.moveToNext()) add(BookInfo(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4))) }
    }
    /** Every chapter in canon order, as (book, chapter): the pages of the reader. */
    val chapters: List<Pair<Int, Int>> = books.flatMap { b -> (1..b.chapters).map { b.id to it } }
    private val chapterIndex: Map<Pair<Int, Int>, Int> = chapters.withIndex().associate { it.value to it.index }

    fun version(code: String): Version = versions.firstOrNull { it.code == code } ?: versions.first()
    fun book(id: Int): BookInfo = books.first { it.id == id }
    fun pageOf(book: Int, chapter: Int): Int = chapterIndex[book to chapter] ?: chapterIndex[book to 1] ?: 0

    fun versesInChapter(version: String, book: Int, chapter: Int): List<Verse> {
        val lo = Ref.key(book, chapter, 0)
        val hi = Ref.key(book, chapter, 999)
        return verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE eng_key BETWEEN ? AND ? AND version = ? ORDER BY eng_key, id",
            arrayOf(lo.toString(), hi.toString(), version))
    }

    fun versesAt(engKey: Int, version: String? = null): List<Verse> =
        if (version == null) verses("SELECT v.id, v.version, v.book, v.chapter, v.verse, v.eng_key, v.text FROM verse v JOIN version n ON n.code = v.version WHERE v.eng_key = ? ORDER BY n.ord, v.id", arrayOf(engKey.toString()))
        else verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE eng_key = ? AND version = ? ORDER BY id", arrayOf(engKey.toString(), version))

    /** The text of a reference (a verse or a range) in one version, for previews. */
    fun previewText(ref: Ref, version: String): String {
        val lo = Ref.key(ref.book, ref.chapter, ref.verse)
        val hi = if (ref.verseEnd > ref.verse) Ref.key(ref.book, ref.chapter, ref.verseEnd) else lo
        val vs = verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE eng_key BETWEEN ? AND ? AND version = ? ORDER BY eng_key, id",
            arrayOf(lo.toString(), hi.toString(), version))
        return vs.joinToString(" ") { it.text }
    }

    fun words(engKey: Int): List<Word> = words("SELECT id, eng_key, book, chapter, verse, pos, surface, after, translit, gloss, lemma, morph, parse FROM word WHERE eng_key = ? ORDER BY id", arrayOf(engKey.toString()))

    fun lemma(id: Int): Lemma? = lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun lemmasByStrongs(number: String): List<Lemma> {
        val byNumber = lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE number = ? AND number <> strongs ORDER BY count DESC", arrayOf(number))
        if (byNumber.isNotEmpty()) return byNumber
        val exact = lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE strongs = ? ORDER BY count DESC", arrayOf(number))
        if (exact.isNotEmpty()) return exact
        val bare = number.trimEnd { it.isLetter() && it.isLowerCase() }
        return lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE strongs = ? OR strongs LIKE ? ORDER BY count DESC", arrayOf(bare, "$bare%"))
            .filter { it.strongs.removePrefix(bare).all { ch -> ch.isLetter() } }
    }

    /** Original words whose dictionary form matches what was typed, in Hebrew/Greek letters or plain Latin letters ("bara", "logos"). */
    fun lemmasMatching(folded: String, ascii: String): List<Lemma> =
        lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE fold = ? OR ascii = ? OR fold LIKE ? OR ascii LIKE ? ORDER BY (fold = ? OR ascii = ?) DESC, count DESC LIMIT 30",
            arrayOf(folded, ascii, "$folded%", "$ascii%", folded, ascii))

    fun senses(lemma: Int): List<Sense> = db.rawQuery("SELECT source, entry, depth, label, gloss, text, domain, note FROM sense WHERE lemma = ? ORDER BY rowid", arrayOf(lemma.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Sense(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
    }

    fun renderings(lemma: Int): List<Rendering> = db.rawQuery("SELECT english, n FROM rendering WHERE lemma = ? ORDER BY n DESC", arrayOf(lemma.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Rendering(c.getString(0), c.getInt(1))) }
    }

    /** Every place a word occurs: (English verse key, the word's gloss there), in canon order. */
    fun occurrences(lemma: Int): List<Pair<Int, String>> = db.rawQuery("SELECT eng_key, gloss FROM word WHERE lemma = ? ORDER BY id", arrayOf(lemma.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
    }

    fun xrefs(engKey: Int): List<XRef> = db.rawQuery("SELECT to_key, to_end, votes FROM xref WHERE from_key = ? ORDER BY votes DESC LIMIT 80", arrayOf(engKey.toString())).use { c ->
        buildList { while (c.moveToNext()) add(XRef(c.getInt(0), c.getInt(1), c.getInt(2))) }
    }

    val sources: List<SourceInfo> by lazy {
        db.rawQuery("SELECT code, name, kind, license, credit, url, share_alike FROM source ORDER BY ord", null).use { c ->
            buildList { while (c.moveToNext()) add(SourceInfo(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6) == 1)) }
        }
    }

    fun source(code: String): SourceInfo? = sources.firstOrNull { it.code == code }

    fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToNext()) c.getString(0) else null }

    /**
     * Runs a search. Each match is its own FTS query; the AND / OR / NOT logic is done
     * here, so it does not depend on which FTS query syntax the phone's SQLite has.
     */
    fun search(query: SearchQuery, version: String?, limit: Int = 400): SearchResult {
        val range = version?.let { v -> version(v).let { it.firstId..it.lastId } }
        fun ids(m: Match): Set<Long> {
            val out = HashSet<Long>()
            db.rawQuery("SELECT docid FROM verse_fts WHERE verse_fts MATCH ?", arrayOf(m.fts())).use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); if (range == null || id in range) out += id }
            }
            return out
        }
        var result: Set<Long>? = null
        for (clause in query.required.sortedBy { it.size }) {
            val union = HashSet<Long>()
            for (m in clause) union += ids(m)
            result = result?.intersect(union) ?: union
            if (result.isEmpty()) break
        }
        var found = result ?: emptySet()
        for (m in query.excluded) if (found.isNotEmpty()) found = found - ids(m)
        val sorted = found.sorted()
        val page = sorted.take(limit)
        val verses = if (page.isEmpty()) emptyList() else page.chunked(500).flatMap { chunk ->
            verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE id IN (${chunk.joinToString(",")}) ORDER BY id", emptyArray())
        }
        return SearchResult(sorted.size, verses, query.required.flatten())
    }

    private fun verses(sql: String, args: Array<String>): List<Verse> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(Verse(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getString(6))) }
    }

    private fun words(sql: String, args: Array<String>): List<Word> = db.rawQuery(sql, args).use { c ->
        buildList {
            while (c.moveToNext()) add(Word(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getString(6), c.getString(7),
                c.getString(8), c.getString(9), c.getInt(10), c.getString(11), c.getString(12)))
        }
    }

    private fun lemmas(sql: String, args: Array<String>): List<Lemma> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(Lemma(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7), c.getString(8), c.getString(9))) }
    }

    companion object {
        private const val ASSET = "app.db"

        /** Copies the database out of the APK when it is missing or the app was updated, reporting progress 0..1. */
        fun open(context: Context, progress: (Float) -> Unit): AppDb {
            val target = File(context.filesDir, ASSET)
            val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val stamp = "${info.longVersionCode}:${info.lastUpdateTime}"
            if (!target.exists() || prefs.getString("db_stamp", null) != stamp) {
                val tmp = File(context.filesDir, "$ASSET.part")
                // The asset is compressed inside the APK, so its size comes from the builder's sidecar file.
                val total = runCatching { context.assets.open("$ASSET.size").use { it.readBytes().decodeToString().trim().toLong() } }.getOrDefault(-1L)
                context.assets.open(ASSET).use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                if (target.exists()) target.delete()
                check(tmp.renameTo(target)) { "could not place the Bible database" }
                prefs.edit().putString("db_stamp", stamp).apply()
            }
            progress(1f)
            return AppDb(SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS))
        }
    }
}
