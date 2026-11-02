package sbphone.datapack

import java.sql.Connection

/** The app.db schema. The app reads exactly these tables; Verify refuses any other. */
object Schema {
    const val VERSION = 1

    val tables = listOf(
        "meta", "source", "version", "book", "chapter", "verse", "verse_fts",
        "word", "lemma", "sense", "xref",
        "kjv_word", "eng_form", "eng_sense", "eng_shift",
    )

    private val ddl = listOf(
        "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE source(code TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, license TEXT NOT NULL, credit TEXT NOT NULL, url TEXT NOT NULL, share_alike INTEGER NOT NULL, ord INTEGER NOT NULL)",
        "CREATE TABLE version(code TEXT PRIMARY KEY REFERENCES source(code), name TEXT NOT NULL, language TEXT NOT NULL, rtl INTEGER NOT NULL, versification TEXT NOT NULL, coverage TEXT NOT NULL, ord INTEGER NOT NULL, first_id INTEGER NOT NULL, last_id INTEGER NOT NULL, verses INTEGER NOT NULL)",
        "CREATE TABLE book(id INTEGER PRIMARY KEY, code TEXT NOT NULL, name TEXT NOT NULL, short TEXT NOT NULL, chapters INTEGER NOT NULL)",
        "CREATE TABLE chapter(book INTEGER NOT NULL, chapter INTEGER NOT NULL, verses INTEGER NOT NULL, PRIMARY KEY(book, chapter)) WITHOUT ROWID",
        // eng_key = the verse in English (KJV) numbering: book*1000000 + chapter*1000 + verse.
        "CREATE TABLE verse(id INTEGER PRIMARY KEY, version TEXT NOT NULL, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, eng_key INTEGER NOT NULL, text TEXT NOT NULL)",
        "CREATE INDEX verse_eng ON verse(eng_key, version)",
        // Contentless FTS4 over folded text; docid = verse.id. Android's built-in SQLite has FTS4.
        "CREATE VIRTUAL TABLE verse_fts USING fts4(content=\"\", folded)",
        "CREATE TABLE word(id INTEGER PRIMARY KEY, eng_key INTEGER NOT NULL, book INTEGER NOT NULL, chapter INTEGER NOT NULL, verse INTEGER NOT NULL, pos INTEGER NOT NULL, surface TEXT NOT NULL, after TEXT NOT NULL, translit TEXT NOT NULL, gloss TEXT NOT NULL, lemma INTEGER NOT NULL, morph TEXT NOT NULL, parse TEXT NOT NULL)",
        "CREATE INDEX word_eng ON word(eng_key)",
        "CREATE INDEX word_lemma ON word(lemma)",
        "CREATE INDEX word_morph ON word(morph)",
        // number = the displayed number: OSHB extended Strong's for Hebrew/Aramaic (H1254a keeps homographs apart), Strong's for Greek.
        // hint = the first gloss of the first lexicon that has one (BDB / Abbott-Smith first), to tell homographs apart in lists.
        "CREATE TABLE lemma(id INTEGER PRIMARY KEY, language TEXT NOT NULL, headword TEXT NOT NULL, translit TEXT NOT NULL, pron TEXT NOT NULL, strongs TEXT NOT NULL, number TEXT NOT NULL, hint TEXT NOT NULL DEFAULT '', pos TEXT NOT NULL, count INTEGER NOT NULL, fold TEXT NOT NULL, ascii TEXT NOT NULL)",
        "CREATE INDEX lemma_strongs ON lemma(strongs)",
        "CREATE INDEX lemma_number ON lemma(number)",
        "CREATE INDEX lemma_fold ON lemma(fold)",
        "CREATE INDEX lemma_ascii ON lemma(ascii)",
        "CREATE TABLE sense(lemma INTEGER NOT NULL, source TEXT NOT NULL REFERENCES source(code), entry TEXT NOT NULL, seq INTEGER NOT NULL, depth INTEGER NOT NULL, label TEXT NOT NULL, gloss TEXT NOT NULL, text TEXT NOT NULL, domain TEXT NOT NULL, note TEXT NOT NULL)",
        "CREATE INDEX sense_lemma ON sense(lemma)",
        // Each folded KJV word form and its English dictionary form ("loveth" -> "love"), the most common one where the tagger varies.
        "CREATE TABLE eng_form(fold TEXT PRIMARY KEY, lemma TEXT NOT NULL) WITHOUT ROWID",
        "CREATE INDEX eng_form_lemma ON eng_form(lemma)",
        // KJV word -> original word, from the eBible kjv2006 Strong's links. A word is found by its folded
        // form and which occurrence of that form it is in the verse (nth, from 1), so small spelling
        // differences between KJV printings elsewhere in the verse do not shift it.
        "CREATE TABLE kjv_word(eng_key INTEGER NOT NULL, fold TEXT NOT NULL, nth INTEGER NOT NULL, lemma INTEGER NOT NULL)",
        "CREATE INDEX kjv_word_key ON kjv_word(eng_key)",
        "CREATE INDEX kjv_word_fold ON kjv_word(fold)",
        "CREATE INDEX kjv_word_lemma ON kjv_word(lemma)",
        // What an English word meant then (Blount 1661, Webster 1828, Wright 1884) and today (Open English WordNet).
        "CREATE TABLE eng_sense(fold TEXT NOT NULL, word TEXT NOT NULL, source TEXT NOT NULL REFERENCES source(code), year TEXT NOT NULL, sense_no TEXT NOT NULL, pos TEXT NOT NULL, text TEXT NOT NULL, cite TEXT NOT NULL)",
        "CREATE INDEX eng_sense_fold ON eng_sense(fold)",
        // Wright 1884 lists these words, at these verses, as having changed meaning since the KJV.
        "CREATE TABLE eng_shift(fold TEXT NOT NULL, eng_key INTEGER NOT NULL)",
        "CREATE INDEX eng_shift_fold ON eng_shift(fold)",
        "CREATE INDEX eng_shift_key ON eng_shift(eng_key)",
        "CREATE TABLE xref(from_key INTEGER NOT NULL, to_key INTEGER NOT NULL, to_end INTEGER NOT NULL, votes INTEGER NOT NULL)",
        "CREATE INDEX xref_from ON xref(from_key, votes)",
    )

    fun create(db: Connection) {
        db.createStatement().use { st -> for (sql in ddl) st.execute(sql) }
    }
}
