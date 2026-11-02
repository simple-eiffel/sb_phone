package sbphone.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import sbphone.fold.Fold
import sbphone.fold.Match
import sbphone.fold.Node
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
data class Lane(val id: String, val title: String, val group: String)
data class TlEvent(val id: String, val lane: String, val title: String, val kind: String, val importance: Int, val summary: String, val status: String, val earliest: Int, val latest: Int, val label: String)
data class TlClaim(val label: String, val chronology: String, val heldBy: String, val basis: String, val lean: Boolean, val note: String, val citation: String)
data class Place(val id: String, val name: String, val kind: String, val minZoom: Int, val lon: Float, val lat: Float)
/** An empire's outline: lon,lat pairs. Years are astronomical (0 = 1 BC). */
data class Area(val name: String, val yearFrom: Int, val yearTo: Int, val ring: FloatArray)

data class EnglishSense(val source: String, val year: String, val senseNo: String, val pos: String, val text: String, val cite: String)

/** The original words behind one English word, and how many times the KJV translates each that way. */
data class Behind(val lemma: Lemma, val n: Int)

/** An English word: its dictionary form, the KJV forms of it, the words behind it, its meanings, and Wright's citations. */
data class EnglishEntry(val lemma: String, val forms: List<String>, val behind: List<Behind>, val senses: List<EnglishSense>, val shifts: List<Int>)

data class SourceInfo(val code: String, val name: String, val kind: String, val license: String, val credit: String, val url: String, val shareAlike: Boolean)

/**
 * [byVerse]: the search had original-language terms, so [total] counts verses (English numbering)
 * and [wordHits] lists the Hebrew or Greek words that matched in each verse.
 */
data class SearchResult(
    val total: Int, val verses: List<Verse>, val matches: List<Match>,
    val byVerse: Boolean = false, val wordHits: Map<Int, List<String>> = emptyMap(),
)

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

    /** How the KJV translates a word: English dictionary forms with how many times, from the KJV word links. */
    fun renderings(lemma: Int): List<Rendering> = db.rawQuery(
        "SELECT coalesce(f.lemma, k.fold) AS w, COUNT(*) AS n FROM kjv_word k LEFT JOIN eng_form f ON f.fold = k.fold WHERE k.lemma = ? GROUP BY w ORDER BY n DESC",
        arrayOf(lemma.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Rendering(c.getString(0), c.getInt(1))) }
    }

    /** Every place a word occurs: (English verse key, the word's gloss there), in canon order. */
    fun occurrences(lemma: Int): List<Pair<Int, String>> = db.rawQuery("SELECT eng_key, gloss FROM word WHERE lemma = ? ORDER BY id", arrayOf(lemma.toString())).use { c ->
        buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
    }

    /** The Hebrew/Greek word(s) a KJV word translates: the [nth] occurrence of folded word [fold] in the verse. */
    fun kjvLemmas(engKey: Int, fold: String, nth: Int): List<Lemma> =
        lemmas("SELECT id, language, headword, translit, pron, strongs, pos, count, number, hint FROM lemma WHERE id IN (SELECT lemma FROM kjv_word WHERE eng_key = ? AND fold = ? AND nth = ?) ORDER BY count DESC",
            arrayOf(engKey.toString(), fold, nth.toString()))

    /** (fold, nth) of every KJV word in a verse that links to an original word. */
    fun kjvLinked(engKey: Int): Set<Pair<String, Int>> = db.rawQuery("SELECT DISTINCT fold, nth FROM kjv_word WHERE eng_key = ?", arrayOf(engKey.toString())).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
    }

    /** Words in this verse that Wright (1884) lists as having changed meaning since the KJV. */
    fun shiftedIn(engKey: Int): Set<String> = db.rawQuery("SELECT fold FROM eng_shift WHERE eng_key = ?", arrayOf(engKey.toString())).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** An English word with all its KJV forms ("love": love, loved, loveth…), from the folded form typed or tapped. */
    fun englishEntry(fold: String): EnglishEntry {
        val lemma = db.rawQuery("SELECT lemma FROM eng_form WHERE fold = ?", arrayOf(fold)).use { if (it.moveToNext()) it.getString(0) else fold }
        val forms = (db.rawQuery("SELECT fold FROM eng_form WHERE lemma = ? ORDER BY fold", arrayOf(lemma)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } } + fold).distinct()
        val marks = forms.joinToString(",") { "?" }
        val args = forms.toTypedArray()
        val behind = db.rawQuery(
            "SELECT l.id, l.language, l.headword, l.translit, l.pron, l.strongs, l.pos, l.count, l.number, l.hint, COUNT(*) AS n FROM kjv_word k JOIN lemma l ON l.id = k.lemma WHERE k.fold IN ($marks) GROUP BY l.id ORDER BY n DESC",
            args).use { c ->
            buildList { while (c.moveToNext()) add(Behind(Lemma(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7), c.getString(8), c.getString(9)), c.getInt(10))) }
        }
        // Dictionaries are keyed by headword, so look up the dictionary form first, then the forms.
        val senses = (listOf(lemma) + forms).distinct().flatMap { f ->
            db.rawQuery("SELECT source, year, sense_no, pos, text, cite FROM eng_sense WHERE fold = ? ORDER BY rowid", arrayOf(f)).use { c ->
                buildList { while (c.moveToNext()) add(EnglishSense(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))) }
            }
        }.distinct()
        val shifts = db.rawQuery("SELECT DISTINCT eng_key FROM eng_shift WHERE fold IN ($marks) ORDER BY eng_key", args).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        return EnglishEntry(lemma, forms, behind, senses, shifts)
    }

    // ---- timeline (the author's drafts) and atlas ----

    fun lanes(): List<Lane> = db.rawQuery("SELECT id, title, grp FROM tl_lane ORDER BY sort", null).use { c -> buildList { while (c.moveToNext()) add(Lane(c.getString(0), c.getString(1), c.getString(2))) } }

    fun events(lane: String? = null): List<TlEvent> = db.rawQuery(
        "SELECT id, lane, title, kind, importance, summary, status, earliest, latest, label FROM tl_event" + (if (lane != null) " WHERE lane = ?" else "") + " ORDER BY earliest, latest",
        if (lane != null) arrayOf(lane) else null).use { c ->
        buildList { while (c.moveToNext()) add(TlEvent(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5), c.getString(6), c.getInt(7), c.getInt(8), c.getString(9))) }
    }

    fun claims(event: String): List<TlClaim> = db.rawQuery(
        "SELECT c.label, c.chronology, c.held_by, c.basis, c.lean, c.note, COALESCE(t.text, '') FROM tl_claim c LEFT JOIN tl_citation t ON t.id = c.citation WHERE c.event = ? ORDER BY c.ordinal",
        arrayOf(event)).use { c ->
        buildList { while (c.moveToNext()) add(TlClaim(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getString(5), c.getString(6))) }
    }

    fun places(event: String? = null): List<Place> = db.rawQuery(
        if (event == null) "SELECT id, name, kind, min_zoom, lon, lat FROM tl_place"
        else "SELECT p.id, p.name, p.kind, p.min_zoom, p.lon, p.lat FROM tl_place p JOIN tl_event_place e ON e.place = p.id WHERE e.event = ?",
        if (event == null) null else arrayOf(event)).use { c ->
        buildList { while (c.moveToNext()) add(Place(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getDouble(4).toFloat(), c.getDouble(5).toFloat())) }
    }

    /** Land and lake outlines for the base map, decoded once. */
    val land: List<Pair<String, FloatArray>> by lazy {
        db.rawQuery("SELECT kind, ring FROM atlas_land", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to floats(c.getBlob(1))) } }
    }

    /** The empires standing in an astronomical [year]. */
    fun areas(year: Int): List<Area> = db.rawQuery("SELECT name, year_from, year_to, ring FROM atlas_area WHERE year_from <= ? AND year_to >= ?", arrayOf(year.toString(), year.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Area(c.getString(0), c.getInt(1), c.getInt(2), floats(c.getBlob(3)))) }
    }

    private fun floats(b: ByteArray): FloatArray {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buf.remaining()).also { buf.get(it) }
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
    fun search(query: SearchQuery, version: String?, limit: Int = 400): SearchResult =
        if (query.hasWordTerms) searchByVerse(query, version ?: "KJV", limit) else searchText(query, version, limit)

    /** Text-only search: results are verse rows of one version, or of every version. */
    private fun searchText(query: SearchQuery, version: String?, limit: Int): SearchResult {
        val range = version?.let { v -> version(v).let { it.firstId..it.lastId } }
        val found = evaluate(query.root) { node ->
            val m = (node as Node.Text).match
            val out = HashSet<Long>()
            db.rawQuery("SELECT docid FROM verse_fts WHERE verse_fts MATCH ?", arrayOf(m.fts())).use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); if (range == null || id in range) out += id }
            }
            out
        }
        val sorted = found.sorted()
        val page = sorted.take(limit)
        val verses = if (page.isEmpty()) emptyList() else page.chunked(500).flatMap { chunk ->
            verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE id IN (${chunk.joinToString(",")}) ORDER BY id", emptyArray())
        }
        return SearchResult(sorted.size, verses, query.textMatches)
    }

    /**
     * A search with original-language terms (strongs:, lemma:, morph:). Every term yields a set of
     * verses in English numbering: text terms from [version]'s text, word terms from the Hebrew and
     * Greek words. The verses are shown in [version], with the original words that matched.
     */
    private fun searchByVerse(query: SearchQuery, version: String, limit: Int): SearchResult {
        val wordTerms = mutableListOf<Pair<String, Array<String>>>()
        val found = evaluate(query.root) { node ->
            val out = HashSet<Long>()
            when (node) {
                is Node.Text -> db.rawQuery("SELECT v.eng_key FROM verse_fts JOIN verse v ON v.id = verse_fts.docid WHERE verse_fts MATCH ? AND v.version = ?",
                    arrayOf(node.match.fts(), version)).use { c -> while (c.moveToNext()) out += c.getLong(0) }
                is Node.WordTerm -> {
                    val cond = wordCondition(node)
                    wordTerms += cond
                    db.rawQuery("SELECT DISTINCT eng_key FROM word WHERE ${cond.first}", cond.second).use { c -> while (c.moveToNext()) out += c.getLong(0) }
                }
                else -> Unit
            }
            out
        }
        val sorted = found.sorted()
        val page = sorted.take(limit)
        if (page.isEmpty()) return SearchResult(0, emptyList(), query.textMatches, byVerse = true)
        val keys = page.joinToString(",")
        val verses = verses("SELECT id, version, book, chapter, verse, eng_key, text FROM verse WHERE eng_key IN ($keys) AND version = ? ORDER BY eng_key, id", arrayOf(version))
            .ifEmpty { verses("SELECT v.id, v.version, v.book, v.chapter, v.verse, v.eng_key, v.text FROM verse v JOIN version n ON n.code = v.version WHERE v.eng_key IN ($keys) AND n.code = (CASE WHEN v.book <= 39 THEN 'WLC' ELSE 'WH' END) ORDER BY v.eng_key, v.id", emptyArray()) }
        // The original words that matched, per verse, for display under each result.
        val hits = HashMap<Int, MutableList<String>>()
        for ((cond, args) in wordTerms.distinctBy { it.first + it.second.joinToString() }) {
            db.rawQuery("SELECT eng_key, surface FROM word WHERE eng_key IN ($keys) AND ($cond) ORDER BY id", args).use { c ->
                while (c.moveToNext()) hits.getOrPut(c.getInt(0)) { mutableListOf() }.let { l -> val s = c.getString(1); if (s !in l) l += s }
            }
        }
        return SearchResult(sorted.size, verses, query.textMatches, byVerse = true, wordHits = hits)
    }

    private fun wordCondition(t: Node.WordTerm): Pair<String, Array<String>> = when (t.field) {
        "strongs" -> "lemma IN (SELECT id FROM lemma WHERE number = ? OR strongs = ?)" to arrayOf(t.value, t.value)
        "lemma" -> {
            val f = Fold.fold(t.value)
            "lemma IN (SELECT id FROM lemma WHERE fold = ? OR ascii = ?)" to arrayOf(f, t.value.lowercase())
        }
        else -> if ('*' in t.value || '?' in t.value) "morph GLOB ?" to arrayOf(t.value) else "morph = ?" to arrayOf(t.value)
    }

    /** AND / OR / NOT over sets from [leaf]. A NOT only ever narrows the AND it stands in. */
    private fun evaluate(n: Node, leaf: (Node) -> Set<Long>): Set<Long> = when (n) {
        is Node.Text, is Node.WordTerm -> leaf(n)
        is Node.Or -> n.items.fold(HashSet<Long>()) { acc, item -> acc.apply { addAll(evaluate(item, leaf)) } }
        is Node.And -> {
            val positives = n.items.filter { it !is Node.Not }
            var result: Set<Long>? = null
            for (p in positives) {
                val s = evaluate(p, leaf)
                result = result?.intersect(s) ?: s
                if (result.isEmpty()) break
            }
            var found = result ?: emptySet()
            for (neg in n.items.filterIsInstance<Node.Not>()) if (found.isNotEmpty()) found = found - evaluate(neg.item, leaf)
            found
        }
        is Node.Not -> emptySet()
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
