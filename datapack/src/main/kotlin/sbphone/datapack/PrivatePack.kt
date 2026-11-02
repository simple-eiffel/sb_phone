package sbphone.datapack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.sqlite.SQLiteConfig
import sbphone.fold.Books
import sbphone.fold.Fold
import sbphone.fold.Ref
import sbphone.fold.RefFinder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime

/**
 * The author's PRIVATE pack: commentary, frameworks, books and essays, read straight from the
 * vault's build manifests. It is written outside the repository, never shipped in the app, and
 * loaded by hand on the author's own phone. Restricted quotations ("research only") are never read.
 *
 * Format "sb_phone pack": pack_meta, doc, doc_ref (verses a doc covers = 1, or cites = 0), doc_fts.
 */
class PrivatePack(private val vault: File, private val biblePath: String, private val outPath: String) {

    private val mapper = ObjectMapper()
    private val rix = File(vault, "Rix")
    private lateinit var verseCounts: Map<Pair<Int, Int>, Int>

    private data class Doc(val kind: String, val title: String, val collection: String, val status: String, val path: String, val body: String, val sort: Int, val covers: List<Int> = emptyList())

    fun run(): List<String> {
        val report = mutableListOf<String>()
        verseCounts = SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:$biblePath").use { bible ->
            bible.prepareStatement("SELECT v.book_id, v.chapter, MAX(v.verse) FROM bible_verses v JOIN bible_versions n ON n.id = v.version_id WHERE n.abbreviation = 'KJV' GROUP BY 1, 2").use { st ->
                st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getInt(1) to rs.getInt(2), rs.getInt(3)) } }
            }
        }
        val docs = commentary(report) + frameworks(report) + texts("book", File(rix, "Books/_build"), "*.book.json", report) +
            texts("essay", File(rix, "Substack Essays/_build"), "*.essay.json", report)
        val out = File(outPath)
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        DriverManager.getConnection("jdbc:sqlite:$outPath").use { db ->
            db.autoCommit = false
            db.createStatement().use { st ->
                st.execute("CREATE TABLE pack_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                st.execute("CREATE TABLE doc(id INTEGER PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, collection TEXT NOT NULL, status TEXT NOT NULL, path TEXT NOT NULL, words INTEGER NOT NULL, sort INTEGER NOT NULL, body TEXT NOT NULL)")
                st.execute("CREATE INDEX doc_kind ON doc(kind, collection, sort)")
                st.execute("CREATE TABLE doc_ref(doc INTEGER NOT NULL, eng_key INTEGER NOT NULL, covers INTEGER NOT NULL)")
                st.execute("CREATE INDEX doc_ref_key ON doc_ref(eng_key)")
                st.execute("CREATE VIRTUAL TABLE doc_fts USING fts4(content=\"\", folded)")
            }
            writeMeta(db, docs.size)
            var refs = 0
            val ins = db.prepareStatement("INSERT INTO doc(id, kind, title, collection, status, path, words, sort, body) VALUES (?,?,?,?,?,?,?,?,?)")
            val ref = db.prepareStatement("INSERT INTO doc_ref(doc, eng_key, covers) VALUES (?,?,?)")
            val fts = db.prepareStatement("INSERT INTO doc_fts(docid, folded) VALUES (?,?)")
            docs.forEachIndexed { i, d ->
                val id = i + 1L
                ins.setLong(1, id); ins.setString(2, d.kind); ins.setString(3, d.title); ins.setString(4, d.collection); ins.setString(5, d.status)
                ins.setString(6, d.path); ins.setInt(7, d.body.split(Regex("\\s+")).count { it.isNotEmpty() }); ins.setInt(8, d.sort); ins.setString(9, d.body)
                ins.addBatch()
                val covered = d.covers.toSet()
                for (k in covered) { ref.setLong(1, id); ref.setInt(2, k); ref.setInt(3, 1); ref.addBatch(); refs++ }
                for (k in RefFinder.findAll(d.body).flatMap { expand(it.ref) }.toSet() - covered) { ref.setLong(1, id); ref.setInt(2, k); ref.setInt(3, 0); ref.addBatch(); refs++ }
                fts.setLong(1, id); fts.setString(2, Fold.fold(d.title + " " + d.body)); fts.addBatch()
                if (i % 200 == 0) { ins.executeBatch(); ref.executeBatch(); fts.executeBatch() }
            }
            ins.executeBatch(); ref.executeBatch(); fts.executeBatch()
            ins.close(); ref.close(); fts.close()
            db.commit()
            db.autoCommit = true
            db.createStatement().use { it.execute("INSERT INTO doc_fts(doc_fts) VALUES('optimize')") }
            db.createStatement().use { it.execute("VACUUM") }
            report += "pack: ${docs.size} documents, $refs verse links"
        }
        report += "wrote $outPath: ${File(outPath).length() / 1_048_576} MB (PRIVATE: keep it out of every repository)"
        return report
    }

    private fun writeMeta(db: Connection, n: Int) {
        val meta = mapOf(
            "format" to "sb_phone pack", "id" to "rix-materials", "title" to "My materials",
            "built_on" to LocalDateTime.now().withNano(0).toString(),
            "note" to "Private: Larry Rix's commentary, frameworks, books and essays, built from the vault ($n documents). Not for distribution.",
        )
        db.prepareStatement("INSERT INTO pack_meta(key, value) VALUES (?,?)").use { st -> for ((k, v) in meta) { st.setString(1, k); st.setString(2, v); st.addBatch() }; st.executeBatch() }
    }

    /** Every verse key in a reference (a range expands; a whole chapter reference expands to the chapter). */
    private fun expand(r: Ref): List<Int> {
        val last = verseCounts[r.book to r.chapter] ?: return emptyList()
        val from = if (r.verse == 0) 1 else r.verse
        val to = when { r.verse == 0 -> last; r.verseEnd > r.verse -> r.verseEnd.coerceAtMost(last); else -> r.verse }
        return (from..to).map { Ref.key(r.book, r.chapter, it) }
    }

    private fun span(book: Int, c1: Int, v1: Int, c2: Int, v2: Int): List<Int> = buildList {
        for (c in c1..c2) {
            val last = verseCounts[book to c] ?: continue
            val from = if (c == c1) v1 else 1
            val to = if (c == c2) v2.coerceAtMost(last) else last
            for (v in from..to) add(Ref.key(book, c, v))
        }
    }

    private fun rel(f: File) = f.relativeTo(vault).path.replace('\\', '/')

    // ---- commentary: one document per section ----

    private fun commentary(report: MutableList<String>): List<Doc> {
        val out = mutableListOf<Doc>()
        val files = File(rix, "Commentaries").listFiles().orEmpty().filter { it.isDirectory }.flatMap { File(it, "_build").listFiles { f -> f.name.endsWith(".sections.json") }.orEmpty().toList() }
        for (f in files.sortedBy { mapper.readTree(it)["meta"]["book_id"].asInt() }) {
            val root = mapper.readTree(f)
            val meta = root["meta"]
            val book = Books.byId(meta["book_id"].asInt())
            root["sections"].forEachIndexed { i, s ->
                // restricted_quotes is deliberately never read.
                val fields = s["fields"]
                val body = buildString {
                    fun para(heading: String?, text: String) { if (text.isBlank()) return; if (heading != null) append("## $heading\n\n"); append(text.trim()).append("\n\n") }
                    para(null, fields.str("teaser"))
                    para("The text", fields.str("text"))
                    para("The common reading", fields.str("common_reading"))
                    para("Commentary", fields.str("commentary"))
                    para("Variants", fields.str("variants"))
                    list("Words", s["terms"]) { t -> "${t.str("script")} (*${t.str("translit")}*, ${t.str("pron")}) — ${t.str("english")}" }
                    list("Questions this answers", s["questions"]) { it.asText() }
                    list("Frameworks", s["frameworks"]) { it.asText() }
                    list("Further reading", s["further_reading"]) { it.asText() }
                    list("Claims", s["claims"]) { c -> "[${c.str("id")}] **${c.str("label")}** — ${c.str("text")}" }
                }
                val a = s["start"]; val b = s["end"]
                out += Doc("commentary", s.str("heading"), book.name, meta.str("status").lowercase(), rel(f), body, i,
                    span(book.id, a["chapter"].asInt(), a["verse"].asInt(), b["chapter"].asInt(), b["verse"].asInt()))
            }
        }
        report += "commentary: ${out.size} sections from ${files.size} books (restricted quotations left out)"
        return out
    }

    private fun StringBuilder.list(heading: String, node: JsonNode?, line: (JsonNode) -> String) {
        if (node == null || node.isEmpty) return
        append("## $heading\n\n")
        for (n in node) append("- ").append(line(n)).append('\n')
        append('\n')
    }

    // ---- frameworks ----

    private fun frameworks(report: MutableList<String>): List<Doc> {
        val files = File(rix, "Frameworks/_build").listFiles { f -> f.name.endsWith(".framework.json") }.orEmpty().sortedBy { it.name }
        val out = files.mapIndexed { i, f ->
            val fw = mapper.readTree(f)
            val name = fw.str("name") + if (fw["mark"]?.asBoolean() == true) "™" else ""
            val body = buildString {
                if (fw.str("hover").isNotBlank()) append(fw.str("hover")).append("\n\n")
                for (sec in fw["sections"].orEmpty()) append("## ").append(sec.str("heading")).append("\n\n").append(sec.str("markdown")).append("\n\n")
                val related = fw["related"].orEmpty().map { it.asText() }
                if (related.isNotEmpty()) append("## Related\n\n").append(related.joinToString("\n") { "- $it" }).append("\n\n")
                if (fw.str("source").isNotBlank()) append("Source note: ").append(fw.str("source")).append('\n')
            }
            Doc("framework", "$name — ${fw.str("title")}", fw.str("tier").replaceFirstChar { it.uppercase() }, fw.str("status"), rel(f), body, i)
        }
        report += "frameworks: ${out.size}"
        return out
    }

    // ---- books and essays: one document per chapter, from the build manifests ----

    private fun texts(kind: String, dir: File, glob: String, report: MutableList<String>): List<Doc> {
        val suffix = glob.removePrefix("*")
        val manifests = dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) }.orEmpty().sortedBy { it.name }
        val out = mutableListOf<Doc>()
        var missing = 0
        for (m in manifests) {
            val man = mapper.readTree(m)
            val root = File(dir, man.str("root")).canonicalFile
            val title = man.str("title")
            man["chapters"].orEmpty().forEachIndexed { i, ch ->
                val file = File(root, ch.str("file"))
                if (!file.exists()) { missing++; return@forEachIndexed }
                val status = ch.str("status").ifBlank { man.str("status") }
                out += Doc(kind, ch.str("title").ifBlank { file.nameWithoutExtension }, title, status, rel(file), file.readText(), i)
            }
        }
        report += "${kind}s: ${out.size} chapters from ${manifests.size} manifests" + if (missing > 0) " ($missing chapter files not found)" else ""
        return out
    }

    private fun JsonNode.str(field: String): String = this[field]?.takeIf { !it.isNull }?.asText() ?: ""
    private fun JsonNode?.orEmpty(): Iterable<JsonNode> = this ?: emptyList()
}
