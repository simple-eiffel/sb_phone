package sbphone.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import sbphone.fold.Fold
import sbphone.fold.Ref
import java.io.File

/** One document in a pack: a commentary section, a framework, an essay, a book chapter … */
data class PackDoc(val id: Long, val kind: String, val title: String, val collection: String, val status: String, val path: String, val words: Int)

/** A search or verse hit in a pack, with a short excerpt. */
data class PackHit(val doc: PackDoc, val excerpt: String)

/**
 * A private data pack loaded from a file the user chose. It never ships in the app; it lives
 * only in this phone's private storage. Format "sb_phone pack" (see the pack builder):
 * pack_meta, doc, doc_ref (verse keys a doc covers or cites), doc_fts (folded text).
 */
class PackDb private constructor(val file: File, private val db: SQLiteDatabase) {

    val meta: Map<String, String> = db.rawQuery("SELECT key, value FROM pack_meta", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    val title: String get() = meta["title"] ?: file.nameWithoutExtension

    fun kinds(): List<Pair<String, Int>> = db.rawQuery("SELECT kind, COUNT(*) FROM doc GROUP BY kind ORDER BY kind", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
    }

    fun docs(kind: String): List<PackDoc> = docs("SELECT id, kind, title, collection, status, path, words FROM doc WHERE kind = ? ORDER BY collection, sort, title", arrayOf(kind))

    fun doc(id: Long): PackDoc? = docs("SELECT id, kind, title, collection, status, path, words FROM doc WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun body(id: Long): String = db.rawQuery("SELECT body FROM doc WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToNext()) it.getString(0) else "" }

    /** Commentary sections covering a verse, then other documents that cite it. */
    fun forVerse(engKey: Int): List<PackHit> = db.rawQuery(
        """SELECT d.id, d.kind, d.title, d.collection, d.status, d.path, d.words, substr(d.body, 1, 400)
           FROM doc_ref r JOIN doc d ON d.id = r.doc WHERE r.eng_key = ?
           ORDER BY CASE d.kind WHEN 'commentary' THEN 0 ELSE 1 END, r.covers DESC, d.kind, d.title LIMIT 200""",
        arrayOf(engKey.toString())).use { c ->
        buildList { while (c.moveToNext()) add(PackHit(PackDoc(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6)), c.getString(7))) }
    }

    /** Full-text search over titles and bodies; every word required. */
    fun search(text: String, limit: Int = 200): List<PackHit> {
        val words = Fold.words(text)
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "$it*" }
        // The index holds folded text, so the excerpt is cut from the original around the first typed word.
        val first = text.trim().split(Regex("\\s+")).first().lowercase().trim('"', '*')
        return db.rawQuery(
            """SELECT d.id, d.kind, d.title, d.collection, d.status, d.path, d.words,
                      instr(lower(d.body), ?) AS at, substr(d.body, max(1, instr(lower(d.body), ?) - 70), 300)
               FROM doc_fts JOIN doc d ON d.id = doc_fts.docid WHERE doc_fts MATCH ? LIMIT $limit""",
            arrayOf(first, first, match)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    // Start the excerpt on a word boundary, a few words before the match.
                    val raw = c.getString(8)
                    val text = if (c.getInt(7) > 70) "…" + raw.substringAfter(' ', raw).replace(Regex("\\s+"), " ") else raw.replace(Regex("\\s+"), " ")
                    add(PackHit(PackDoc(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6)), text))
                }
            }
        }
    }

    /** Verses a document covers or cites, in canon order. */
    fun refsOf(doc: Long): List<Ref> = db.rawQuery("SELECT DISTINCT eng_key FROM doc_ref WHERE doc = ? ORDER BY eng_key", arrayOf(doc.toString())).use { c ->
        buildList { while (c.moveToNext()) add(Ref.fromKey(c.getInt(0))) }
    }

    private fun docs(sql: String, args: Array<String>): List<PackDoc> = db.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(PackDoc(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getInt(6))) }
    }

    fun close() = db.close()

    companion object {
        const val FORMAT = "sb_phone pack"

        private fun dir(context: Context) = File(context.filesDir, "packs").apply { mkdirs() }

        /** Opens every pack already loaded on this phone. Unreadable files are skipped. */
        fun openAll(context: Context): List<PackDb> = dir(context).listFiles { f -> f.extension == "db" }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching { open(f) }.getOrNull()
        }

        private fun open(f: File): PackDb {
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            val format = runCatching { db.rawQuery("SELECT value FROM pack_meta WHERE key = 'format'", null).use { if (it.moveToNext()) it.getString(0) else null } }.getOrNull()
            if (format != FORMAT) { db.close(); error("not an SB Phone pack") }
            return PackDb(f, db)
        }

        /**
         * Copies a chosen file into private storage after checking it is an SB Phone pack.
         * A pack with the same id replaces the earlier one. Returns the loaded pack.
         */
        fun load(context: Context, uri: Uri): PackDb {
            val tmp = File(dir(context), "incoming.tmp")
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
            val probe = try { open(tmp) } catch (e: Exception) { tmp.delete(); throw IllegalArgumentException("This file is not an SB Phone pack.") }
            val id = (probe.meta["id"] ?: "pack").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "pack" }
            probe.close()
            val target = File(dir(context), "$id.db")
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "could not place the pack" }
            return open(target)
        }

        fun remove(pack: PackDb) {
            pack.close()
            pack.file.delete()
        }
    }
}
