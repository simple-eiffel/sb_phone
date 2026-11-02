package sbphone.datapack

import java.io.File
import java.sql.Connection

/**
 * Larry's rule: private people's names never go into a database that is distributed. The list
 * lives outside every repository (default D:/android/private/private_names.txt). Matching is on
 * whole words, case-insensitive, over every text column of every ordinary table.
 */
object PrivateNames {
    var listFile = File("D:/android/private/private_names.txt")

    private fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}']+")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    fun load(): List<List<String>>? = listFile.takeIf { it.exists() }?.readLines()
        ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }?.map { tokens(it) }?.filter { it.isNotEmpty() }

    /** Returns failures (empty when clean); adds a summary line to [report]. */
    fun scan(db: Connection, report: MutableList<String>): List<String> {
        val names = load()
        if (names == null) { report += "private names: list not found at ${listFile.path} — scan NOT run"; return listOf("private-names list missing: ${listFile.path}") }
        val byLength = names.groupBy { it.size }.mapValues { (_, v) -> v.map { it.joinToString(" ") }.toHashSet() }
        val lengths = byLength.keys.sorted()
        val failures = mutableListOf<String>()
        var values = 0L
        val tables = db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND sql NOT LIKE 'CREATE VIRTUAL%' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%fts%'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        for (t in tables) {
            val cols = db.createStatement().use { st -> st.executeQuery("PRAGMA table_info($t)").use { rs -> buildList { while (rs.next()) if (rs.getString(3).uppercase() == "TEXT") add(rs.getString(2)) } } }
            for (c in cols) {
                db.createStatement().use { st ->
                    st.executeQuery("SELECT $c FROM $t").use { rs ->
                        while (rs.next()) {
                            val v = rs.getString(1) ?: continue
                            values++
                            val w = tokens(v)
                            for (n in lengths) for (i in 0..w.size - n) {
                                val gram = w.subList(i, i + n).joinToString(" ")
                                if (gram in byLength.getValue(n)) failures += "private name \"$gram\" in $t.$c"
                            }
                        }
                    }
                }
            }
        }
        report += "private names: ${names.size} names checked against $values text values in ${tables.size} tables, ${failures.size} found"
        return failures.distinct().take(50)
    }
}
