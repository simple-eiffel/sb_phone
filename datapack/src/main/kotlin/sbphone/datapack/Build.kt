package sbphone.datapack

import sbphone.fold.Books
import sbphone.fold.Fold
import sbphone.fold.Ref
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import org.sqlite.SQLiteConfig

/** Builds app.db from the vault databases. Source databases are opened read-only. */
class Build(private val biblePath: String, private val concPath: String, private val outPath: String) {

    private fun openReadOnly(path: String): Connection {
        val cfg = SQLiteConfig()
        cfg.setReadOnly(true)
        return cfg.createConnection("jdbc:sqlite:$path")
    }

    fun run(): BuildReport {
        val out = java.io.File(outPath)
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        openReadOnly(biblePath).use { bible ->
            openReadOnly(concPath).use { conc ->
                val sources = Allowlist.check(bible, conc)
                log("rights gate: ${sources.size} sources pass")
                DriverManager.getConnection("jdbc:sqlite:$outPath").use { db ->
                    db.createStatement().use { it.execute("PRAGMA journal_mode = OFF") }
                    db.createStatement().use { it.execute("PRAGMA synchronous = OFF") }
                    db.autoCommit = false
                    Schema.create(db)
                    writeSources(db, sources)
                    val hebMap = versificationMap(conc, "WLC-MACULA")
                    val grkMap = versificationMap(conc, "NT-MACULA")
                    log("versification maps: Hebrew ${hebMap.count { it.key != it.value }} differ, Greek ${grkMap.count { it.key != it.value }} differ")
                    writeBooks(db, bible)
                    writeVerses(db, bible, hebMap)
                    val lemmaCounts = writeWords(db, bible, conc, hebMap, grkMap)
                    writeLemmas(db, conc, lemmaCounts)
                    writeSenses(db, conc, lemmaCounts.keys)
                    writeHints(db)
                    writeRenderings(db, conc, lemmaCounts.keys)
                    writeXrefs(db, bible)
                    writeMeta(db)
                    db.commit()
                    db.autoCommit = true
                    db.createStatement().use { it.execute("INSERT INTO verse_fts(verse_fts) VALUES('optimize')") }
                    db.createStatement().use { it.execute("ANALYZE") }
                    db.createStatement().use { it.execute("VACUUM") }
                }
                return Verify(biblePath, outPath).run(bible)
            }
        }
    }

    // ---- sources, books -------------------------------------------------------------

    private fun writeSources(db: Connection, sources: List<Source>) {
        db.prepareStatement("INSERT INTO source(code, name, kind, license, credit, url, share_alike, ord) VALUES (?,?,?,?,?,?,?,?)").use { st ->
            sources.forEachIndexed { i, s ->
                st.setString(1, s.code); st.setString(2, s.name); st.setString(3, s.kind); st.setString(4, s.license)
                st.setString(5, s.credit); st.setString(6, s.url); st.setInt(7, if (s.shareAlike) 1 else 0); st.setInt(8, i)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun writeBooks(db: Connection, bible: Connection) {
        // Chapter and verse counts in English numbering, from the KJV.
        val counts = sortedMapOf<Int, MutableMap<Int, Int>>()
        bible.prepareStatement(
            "SELECT v.book_id, v.chapter, MAX(v.verse) FROM bible_verses v JOIN bible_versions n ON n.id = v.version_id WHERE n.abbreviation = 'KJV' GROUP BY 1, 2"
        ).use { st -> st.executeQuery().use { rs -> while (rs.next()) counts.getOrPut(rs.getInt(1)) { sortedMapOf() }[rs.getInt(2)] = rs.getInt(3) } }
        check(counts.size == 66) { "KJV should cover 66 books, found ${counts.size}" }
        db.prepareStatement("INSERT INTO book(id, code, name, short, chapters) VALUES (?,?,?,?,?)").use { bk ->
            db.prepareStatement("INSERT INTO chapter(book, chapter, verses) VALUES (?,?,?)").use { ch ->
                for ((book, chapters) in counts) {
                    val b = Books.byId(book)
                    bk.setInt(1, book); bk.setString(2, b.code); bk.setString(3, b.name); bk.setString(4, b.short); bk.setInt(5, chapters.size)
                    bk.addBatch()
                    for ((c, n) in chapters) { ch.setInt(1, book); ch.setInt(2, c); ch.setInt(3, n); ch.addBatch() }
                }
                bk.executeBatch(); ch.executeBatch()
            }
        }
    }

    // ---- versification ---------------------------------------------------------------

    /**
     * Maps each verse of a MACULA corpus (its own numbering) to the English (KJV) verse its
     * words are translated in, by majority of the eBible kjv2006 Strong's links. Verses with
     * no links take the offset of the nearest linked verse in the same chapter (this places
     * Hebrew psalm titles with English verse 1). Derived data; identity where they agree.
     */
    private fun versificationMap(conc: Connection, corpus: String): Map<Int, Int> {
        val votes = HashMap<Int, HashMap<Int, Int>>()
        val all = sortedSetOf<Int>()
        conc.prepareStatement(
            """SELECT wa.book_id, ua.div1, ua.div2 FROM unit ua JOIN work_book wa ON wa.work_id = ua.work_id
               JOIN corpus c ON c.corpus_id = ua.corpus_id WHERE c.code = ?"""
        ).use { st ->
            st.setString(1, corpus)
            st.executeQuery().use { rs -> while (rs.next()) key(rs.getInt(1), rs.getString(2), rs.getString(3))?.let { all += it } }
        }
        conc.prepareStatement(
            """SELECT wa.book_id, ua.div1, ua.div2, wb.book_id, ub.div1, ub.div2, COUNT(*)
               FROM token a
               JOIN link l ON l.from_token = a.token_id AND l.kind = 'translation:ENG-KJV'
               JOIN token b ON b.token_id = l.to_token
               JOIN unit ua ON ua.unit_id = a.unit_id JOIN work_book wa ON wa.work_id = ua.work_id
               JOIN unit ub ON ub.unit_id = b.unit_id JOIN work_book wb ON wb.work_id = ub.work_id
               WHERE a.corpus_id = (SELECT corpus_id FROM corpus WHERE code = ?)
               GROUP BY 1, 2, 3, 4, 5, 6"""
        ).use { st ->
            st.setString(1, corpus)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val from = key(rs.getInt(1), rs.getString(2), rs.getString(3)) ?: continue
                    val to = key(rs.getInt(4), rs.getString(5), rs.getString(6)) ?: continue
                    votes.getOrPut(from) { HashMap() }.merge(to, rs.getInt(7), Int::plus)
                }
            }
        }
        val linked = votes.mapValues { (_, v) -> v.maxBy { it.value }.key }
        val result = HashMap<Int, Int>()
        val byChapter = all.groupBy { it / 1000 }
        for ((_, verses) in byChapter) {
            for (k in verses) {
                linked[k]?.let { result[k] = it; return@let }
                if (k in result) continue
                val anchor = verses.filter { it < k && it in linked }.maxOrNull()
                    ?: verses.filter { it > k && it in linked }.minOrNull()
                if (anchor == null) { result[k] = k; continue }
                val target = linked.getValue(anchor)
                val verse = (target % 1000) + (k - anchor)
                result[k] = (target / 1000) * 1000 + verse.coerceAtLeast(1)
            }
        }
        return result
    }

    private fun key(book: Int, chapter: String?, verse: String?): Int? {
        val c = chapter?.toIntOrNull() ?: return null
        val v = verse?.toIntOrNull() ?: return null
        return Ref.key(book, c, v)
    }

    // ---- verses ----------------------------------------------------------------------

    private fun writeVerses(db: Connection, bible: Connection, hebMap: Map<Int, Int>) {
        var id = 0L
        db.prepareStatement("INSERT INTO verse(id, version, book, chapter, verse, eng_key, text) VALUES (?,?,?,?,?,?,?)").use { ins ->
            db.prepareStatement("INSERT INTO verse_fts(docid, folded) VALUES (?,?)").use { fts ->
                db.prepareStatement("INSERT INTO version(code, name, language, rtl, versification, coverage, ord, first_id, last_id, verses) VALUES (?,?,?,?,?,?,?,?,?,?)").use { ver ->
                    Allowlist.versions.forEachIndexed { ord, v ->
                        val first = id + 1
                        var n = 0
                        bible.prepareStatement(
                            "SELECT v.book_id, v.chapter, v.verse, v.text FROM bible_verses v JOIN bible_versions n ON n.id = v.version_id WHERE n.abbreviation = ? AND v.book_id BETWEEN 1 AND 66 ORDER BY v.book_id, v.chapter, v.verse"
                        ).use { st ->
                            st.setString(1, v.code)
                            st.executeQuery().use { rs ->
                                while (rs.next()) {
                                    id++; n++
                                    val k = Ref.key(rs.getInt(1), rs.getInt(2), rs.getInt(3))
                                    val eng = if (v.versification == "heb") hebMap[k] ?: k else k
                                    val text = rs.getString(4) ?: ""
                                    ins.setLong(1, id); ins.setString(2, v.code); ins.setInt(3, rs.getInt(1)); ins.setInt(4, rs.getInt(2))
                                    ins.setInt(5, rs.getInt(3)); ins.setInt(6, eng); ins.setString(7, text)
                                    ins.addBatch()
                                    val folded = Fold.fold(text)
                                    if (folded.isNotEmpty()) { fts.setLong(1, id); fts.setString(2, folded); fts.addBatch() }
                                    if (id % 5000 == 0L) { ins.executeBatch(); fts.executeBatch() }
                                }
                            }
                        }
                        ins.executeBatch(); fts.executeBatch()
                        check(n > 0) { "version ${v.code} has no verses" }
                        ver.setString(1, v.code); ver.setString(2, v.name); ver.setString(3, v.language); ver.setInt(4, if (v.rtl) 1 else 0)
                        ver.setString(5, v.versification); ver.setString(6, v.coverage); ver.setInt(7, ord)
                        ver.setLong(8, first); ver.setLong(9, id); ver.setInt(10, n)
                        ver.executeUpdate()
                        log("version ${v.code}: $n verses")
                    }
                }
            }
        }
    }

    // ---- words -----------------------------------------------------------------------

    private fun writeWords(db: Connection, bible: Connection, conc: Connection, hebMap: Map<Int, Int>, grkMap: Map<Int, Int>): Map<Int, Int> {
        val glosses = HashMap<String, String>(700_000)
        bible.prepareStatement("SELECT xml_id, gloss, english FROM macula_hebrew").use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val g = rs.getString(2)?.takeIf { it.isNotBlank() }?.replace('.', ' ') ?: rs.getString(3) ?: ""
                    glosses[rs.getString(1)] = g
                }
            }
        }
        bible.prepareStatement("SELECT xml_id, gloss, english FROM macula_greek").use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) glosses[rs.getString(1)] = rs.getString(2)?.takeIf { it.isNotBlank() } ?: rs.getString(3) ?: ""
            }
        }
        val lemmaCounts = HashMap<Int, Int>()
        var id = 0L
        var missingGloss = 0
        db.prepareStatement(
            "INSERT INTO word(id, eng_key, book, chapter, verse, pos, surface, after, translit, gloss, lemma, morph, parse) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ins ->
            for ((corpus, map) in listOf("WLC-MACULA" to hebMap, "NT-MACULA" to grkMap)) {
                conc.prepareStatement(
                    """SELECT wb.book_id, u.div1, u.div2, t.pos, t.surface, t.after, t.translit, t.lemma_id, t.morph, t.src_id,
                              t.pos_tag, t.stem, t.form, t.person, t.gender, t.number, t.gstate, t.tense, t.voice, t.mood, t.gcase, t.degree
                       FROM token t JOIN unit u ON u.unit_id = t.unit_id JOIN work_book wb ON wb.work_id = u.work_id
                       WHERE t.corpus_id = (SELECT corpus_id FROM corpus WHERE code = ?)
                       ORDER BY wb.book_id, CAST(u.div1 AS INTEGER), CAST(u.div2 AS INTEGER), t.pos, t.token_id"""
                ).use { st ->
                    st.setString(1, corpus)
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val k = key(rs.getInt(1), rs.getString(2), rs.getString(3)) ?: continue
                            id++
                            val lemma = rs.getInt(8)
                            if (!rs.wasNull()) lemmaCounts.merge(lemma, 1, Int::plus)
                            val gloss = glosses[rs.getString(10)] ?: run { missingGloss++; "" }
                            ins.setLong(1, id); ins.setInt(2, map[k] ?: k)
                            ins.setInt(3, rs.getInt(1)); ins.setInt(4, k / 1000 % 1000); ins.setInt(5, k % 1000); ins.setInt(6, rs.getInt(4))
                            ins.setString(7, rs.getString(5)); ins.setString(8, rs.getString(6) ?: ""); ins.setString(9, rs.getString(7) ?: "")
                            ins.setString(10, gloss); ins.setInt(11, lemma); ins.setString(12, rs.getString(9) ?: "")
                            ins.setString(13, Parsing.describe(rs, 11))
                            ins.addBatch()
                            if (id % 5000 == 0L) ins.executeBatch()
                        }
                    }
                }
                ins.executeBatch()
                log("words $corpus: done (running total $id)")
            }
        }
        log("words: $id, lemmas used ${lemmaCounts.size}, words without a MACULA gloss $missingGloss")
        return lemmaCounts
    }

    // ---- lemmas, senses, renderings --------------------------------------------------

    private fun writeLemmas(db: Connection, conc: Connection, counts: Map<Int, Int>) {
        val strongs = HashMap<Int, String>()
        conc.prepareStatement("SELECT lemma_id, value FROM lemma_xref WHERE scheme = 'strongs' ORDER BY lemma_id, n DESC").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) strongs.putIfAbsent(rs.getInt(1), rs.getString(2)) }
        }
        var n = 0
        val oshb = Regex("""^0*(\d+[a-z]?)$""")
        db.prepareStatement("INSERT INTO lemma(id, language, headword, translit, pron, strongs, number, pos, count, fold, ascii) VALUES (?,?,?,?,?,?,?,?,?,?,?)").use { ins ->
            conc.prepareStatement("SELECT lemma_id, language, headword, translit, pron, pos, translit_ascii, key FROM lemma WHERE language IN ('hbo', 'arc', 'grc')").use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt(1)
                        val count = counts[id] ?: continue
                        val head = rs.getString(3) ?: continue
                        val strong = strongs[id] ?: ""
                        val key = rs.getString(8) ?: ""
                        val number = if (rs.getString(2) != "grc") oshb.matchEntire(key)?.let { "H" + it.groupValues[1] } ?: strong else strong
                        ins.setInt(1, id); ins.setString(2, rs.getString(2)); ins.setString(3, head); ins.setString(4, rs.getString(4) ?: "")
                        ins.setString(5, rs.getString(5) ?: ""); ins.setString(6, strong); ins.setString(7, number); ins.setString(8, rs.getString(6) ?: "")
                        ins.setInt(9, count); ins.setString(10, Fold.fold(head)); ins.setString(11, (rs.getString(7) ?: "").lowercase())
                        ins.addBatch(); n++
                    }
                }
            }
            ins.executeBatch()
        }
        log("lemmas: $n")
    }

    private fun writeSenses(db: Connection, conc: Connection, lemmas: Set<Int>) {
        var n = 0
        val inList = Allowlist.lexicons.joinToString(",") { "'$it'" }
        db.prepareStatement("INSERT INTO sense(lemma, source, entry, seq, depth, label, gloss, text, domain, note) VALUES (?,?,?,?,?,?,?,?,?,?)").use { ins ->
            conc.prepareStatement("SELECT lemma_id, source, entry, seq, depth, label, gloss, text, domain, note FROM lex_sense WHERE source IN ($inList) ORDER BY lemma_id, source, entry, seq").use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (rs.getInt(1) !in lemmas) continue
                        ins.setInt(1, rs.getInt(1)); ins.setString(2, rs.getString(2)); ins.setString(3, rs.getString(3) ?: "")
                        ins.setInt(4, rs.getInt(4)); ins.setInt(5, rs.getInt(5)); ins.setString(6, rs.getString(6) ?: "")
                        ins.setString(7, rs.getString(7) ?: ""); ins.setString(8, rs.getString(8) ?: "")
                        ins.setString(9, rs.getString(9) ?: ""); ins.setString(10, rs.getString(10) ?: "")
                        ins.addBatch(); n++
                        if (n % 5000 == 0) ins.executeBatch()
                    }
                }
            }
            ins.executeBatch()
        }
        log("senses: $n")
    }

    /** A short gloss per word, from the first lexicon in a fixed order that has one. */
    private fun writeHints(db: Connection) {
        val order = listOf("BDB1906", "AS1922", "SDBH", "SDGNT", "TBESH", "ML1889", "LSJ")
        val pick = order.joinToString(", ") { "(SELECT gloss FROM sense s WHERE s.lemma = lemma.id AND s.source = '$it' AND s.gloss <> '' ORDER BY rowid LIMIT 1)" }
        db.createStatement().use { it.executeUpdate("UPDATE lemma SET hint = coalesce($pick, '')") }
    }

    private fun writeRenderings(db: Connection, conc: Connection, lemmas: Set<Int>) {
        var n = 0
        db.prepareStatement("INSERT INTO rendering(lemma, english, n) VALUES (?,?,?)").use { ins ->
            conc.prepareStatement("SELECT lemma_id, english, n FROM lemma_english WHERE corpus_id = (SELECT corpus_id FROM corpus WHERE code = 'ENG-KJV') ORDER BY lemma_id, n DESC").use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (rs.getInt(1) !in lemmas) continue
                        ins.setInt(1, rs.getInt(1)); ins.setString(2, rs.getString(2)); ins.setInt(3, rs.getInt(3))
                        ins.addBatch(); n++
                    }
                }
            }
            ins.executeBatch()
        }
        log("KJV renderings: $n")
    }

    // ---- cross-references, meta ------------------------------------------------------

    private fun writeXrefs(db: Connection, bible: Connection) {
        var n = 0
        db.prepareStatement("INSERT INTO xref(from_key, to_key, to_end, votes) VALUES (?,?,?,?)").use { ins ->
            bible.prepareStatement(
                "SELECT from_book_id, from_chapter, from_verse, to_book_id, to_chapter, to_verse_start, to_verse_end, votes FROM cross_references WHERE from_book_id BETWEEN 1 AND 66 AND to_book_id BETWEEN 1 AND 66"
            ).use { st ->
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val toStart = rs.getInt(6)
                        val toEnd = rs.getInt(7)
                        ins.setInt(1, Ref.key(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                        ins.setInt(2, Ref.key(rs.getInt(4), rs.getInt(5), toStart))
                        ins.setInt(3, if (toEnd > toStart) Ref.key(rs.getInt(4), rs.getInt(5), toEnd) else 0)
                        ins.setInt(4, rs.getInt(8))
                        ins.addBatch(); n++
                        if (n % 5000 == 0) ins.executeBatch()
                    }
                }
            }
            ins.executeBatch()
        }
        log("cross-references: $n")
    }

    private fun writeMeta(db: Connection) {
        val meta = mapOf(
            "schema" to Schema.VERSION.toString(),
            "built_on" to LocalDateTime.now().withNano(0).toString(),
            "builder" to "sb_phone datapack",
            "vmap_basis" to "English (KJV) verse numbers for Hebrew and Greek words are derived from the eBible.org kjv2006 Strong's links (majority per verse).",
        )
        db.prepareStatement("INSERT INTO meta(key, value) VALUES (?,?)").use { st ->
            for ((k, v) in meta) { st.setString(1, k); st.setString(2, v); st.addBatch() }
            st.executeBatch()
        }
    }
}

fun log(message: String) = println("[datapack] $message")

/** Spells MACULA's grammar fields out in plain English, in a fixed order. */
object Parsing {
    private val hebrewForms = mapOf(
        "qatal" to "perfect (qatal)", "yiqtol" to "imperfect (yiqtol)", "wayyiqtol" to "narrative past (wayyiqtol)",
        "weqatal" to "consecutive perfect (weqatal)",
    )
    private val persons = mapOf("first" to "1st person", "second" to "2nd person", "third" to "3rd person")

    /** Columns from [first]: pos_tag, stem, form, person, gender, number, gstate, tense, voice, mood, gcase, degree. */
    fun describe(rs: java.sql.ResultSet, first: Int): String {
        fun col(i: Int) = rs.getString(first + i)?.takeIf { it.isNotBlank() }
        val parts = listOfNotNull(
            col(0), col(1), col(2)?.let { hebrewForms[it] ?: it }, col(7), col(8), col(9),
            col(3)?.let { persons[it] ?: it }, col(10), col(4), col(5), col(6), col(11),
        )
        return parts.joinToString(", ")
    }
}
