package sbphone.datapack

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

data class BuildReport(val path: String, val bytes: Long, val sha256: String, val lines: List<String>)

class VerifyFailure(message: String) : Exception(message)

/**
 * Checks the finished app.db against the rights gate. Any failure deletes the output.
 *
 * The fingerprint scan streams EVERY verse of every bible.db version that is not shipped
 * and keeps its distinctive texts: those no shipped version has, word for word, in the
 * source. None of them may appear in app.db. (A verse that, say, Westcott-Hort and the TR
 * share exactly is not distinctive, so it is skipped rather than counted as a leak.)
 */
class Verify(private val biblePath: String, private val outPath: String) {

    fun run(bible: Connection): BuildReport {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        DriverManager.getConnection("jdbc:sqlite:$outPath").use { db ->
            // 1. Only the expected tables.
            val tables = db.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'verse_fts_%'").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
            val extra = tables - Schema.tables.toSet()
            val missing = Schema.tables.toSet() - tables.toSet()
            if (extra.isNotEmpty()) failures += "unexpected tables: $extra"
            if (missing.isNotEmpty()) failures += "missing tables: $missing"

            // 2. Versions and lexicons are exactly the allowlisted ones, each with a credit.
            val shipped = strings(db, "SELECT DISTINCT version FROM verse").toSet()
            val allowed = Allowlist.versions.map { it.code }.toSet()
            if (!allowed.containsAll(shipped)) failures += "verse rows from versions not on the allowlist: ${shipped - allowed}"
            val lexicons = strings(db, "SELECT DISTINCT source FROM sense").toSet()
            if (!Allowlist.lexicons.toSet().containsAll(lexicons)) failures += "senses from sources not on the allowlist: ${lexicons - Allowlist.lexicons.toSet()}"
            val dictionaries = strings(db, "SELECT DISTINCT source FROM eng_sense").toSet()
            if (!Allowlist.englishDictionaries.toSet().containsAll(dictionaries)) failures += "English senses from sources not on the allowlist: ${dictionaries - Allowlist.englishDictionaries.toSet()}"
            val credited = strings(db, "SELECT code FROM source").toSet()
            for (code in shipped + lexicons + dictionaries) if (code !in credited) failures += "$code ships without a credit record"
            if (strings(db, "SELECT code FROM source WHERE trim(credit) = '' OR trim(license) = ''").isNotEmpty()) failures += "a source has an empty credit or license"

            // 3. Fingerprint scan of every text that does not ship.
            val shippedTexts = HashSet<String>(300_000)
            bible.prepareStatement(
                "SELECT v.text FROM bible_verses v JOIN bible_versions n ON n.id = v.version_id WHERE n.abbreviation IN (${allowed.joinToString(",") { "'$it'" }})"
            ).use { st -> st.executeQuery().use { rs -> while (rs.next()) rs.getString(1)?.let { shippedTexts += it } } }
            val appTexts = HashSet<String>(300_000)
            db.createStatement().use { st -> st.executeQuery("SELECT text FROM verse").use { rs -> while (rs.next()) appTexts += rs.getString(1) } }
            val restricted = strings(bible, "SELECT abbreviation FROM bible_versions") - allowed
            for (code in restricted.sorted()) {
                var distinctive = 0
                var leaks = 0
                bible.prepareStatement("SELECT v.text FROM bible_verses v JOIN bible_versions n ON n.id = v.version_id WHERE n.abbreviation = ?").use { st ->
                    st.setString(1, code)
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val t = rs.getString(1) ?: continue
                            if (t.length < 12 || t in shippedTexts) continue
                            distinctive++
                            if (t in appTexts) leaks++
                        }
                    }
                }
                lines += "fingerprint $code: $distinctive distinctive verses checked, $leaks found in app.db"
                if (leaks > 0) failures += "$code: $leaks of its verses are in app.db"
                if (distinctive == 0) failures += "$code: no distinctive verses to fingerprint (the scan proved nothing)"
            }

            // 4. No private person's name anywhere in the shipped database (Larry's rule for anything distributed).
            failures += PrivateNames.scan(db, lines)

            // 5. Counts for the report.
            for (t in listOf("verse", "word", "lemma", "sense", "xref", "kjv_word", "eng_form", "eng_sense", "eng_shift")) {
                lines += "$t: ${strings(db, "SELECT COUNT(*) FROM $t").single()} rows"
            }
        }
        val file = File(outPath)
        if (failures.isNotEmpty()) {
            file.delete()
            throw VerifyFailure("Verification failed; app.db deleted:\n  " + failures.joinToString("\n  "))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return BuildReport(outPath, file.length(), digest.digest().joinToString("") { "%02x".format(it) }, lines)
    }

    private fun strings(db: Connection, sql: String): List<String> =
        db.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
}
