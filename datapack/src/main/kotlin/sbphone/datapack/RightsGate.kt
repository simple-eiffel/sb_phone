package sbphone.datapack

import java.sql.Connection

/**
 * The rights gate. Nothing ships by default: an item ships only if it is named here AND
 * its source register still marks it distributable. A downgrade in the vault register
 * fails the build.
 */

/** A Bible version that may ship. [code] is the bible.db `bible_versions.abbreviation`. */
data class ShippedVersion(
    val code: String,
    val name: String,
    val language: String,
    val rtl: Boolean,
    /** "heb" for Hebrew (MT) verse numbering, "eng" for English. */
    val versification: String,
    val coverage: String,
)

data class Source(
    val code: String,
    val name: String,
    val kind: String,
    val license: String,
    val credit: String,
    val url: String,
    val shareAlike: Boolean,
)

class RightsGateFailure(message: String) : Exception(message)

object Allowlist {

    val versions: List<ShippedVersion> = listOf(
        ShippedVersion("KJV", "King James Version (1769 text)", "en", false, "eng", "OT, NT"),
        ShippedVersion("BSB", "Berean Standard Bible", "en", false, "eng", "OT, NT"),
        ShippedVersion("ASV", "American Standard Version (1901)", "en", false, "eng", "OT, NT"),
        ShippedVersion("YLT", "Young's Literal Translation (1898)", "en", false, "eng", "OT, NT"),
        ShippedVersion("WLC", "Westminster Leningrad Codex (Hebrew)", "he", true, "heb", "OT"),
        ShippedVersion("MapM", "Miqra according to the Masorah (Hebrew)", "he", true, "heb", "OT"),
        ShippedVersion("WH", "Westcott-Hort 1881 (Greek)", "el", false, "eng", "NT"),
        ShippedVersion("TgJ", "Targum Jonathan (Aramaic)", "arc", true, "heb", "Pentateuch (Pseudo-Jonathan), Prophets"),
        ShippedVersion("Tyn", "Tyndale (1526/1530)", "en", false, "eng", "NT, Pentateuch, Jonah"),
        ShippedVersion("Wyc", "Wycliffe (c. 1395, Later Version)", "en", false, "eng", "NT"),
        ShippedVersion("Vul", "Clementine Vulgate (Latin)", "la", false, "eng", "NT"),
    )

    /** bible.db tables that ship (as rows reshaped by the builder). */
    val bibleTables = listOf("macula_hebrew", "macula_greek", "cross_references", "bible_books")

    /** conc.db corpora whose rows ship: the two MACULA corpora and the KJV renderings. */
    val corpora = listOf("WLC-MACULA", "NT-MACULA", "ENG-KJV")

    /** conc.db lexicon sources that ship. */
    val lexicons = listOf("BDB1906", "TBESH", "SDBH", "AS1922", "ML1889", "LSJ", "SDGNT")

    private val okBibleClasses = setOf("open", "attribution", "share_alike")

    /** Checks every allowlisted item against its register and returns the credit records. */
    fun check(bible: Connection, conc: Connection): List<Source> {
        val failures = mutableListOf<String>()
        val sources = mutableListOf<Source>()

        fun bibleRow(target: String, kind: String): Map<String, String>? =
            bible.prepareStatement(
                "SELECT source_name, source_url, license, attribution, ship_class FROM source_provenance WHERE target = ? AND kind = ?"
            ).use { st ->
                st.setString(1, target); st.setString(2, kind)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else mapOf(
                        "name" to (rs.getString(1) ?: ""), "url" to (rs.getString(2) ?: ""),
                        "license" to (rs.getString(3) ?: ""), "credit" to (rs.getString(4) ?: ""),
                        "ship" to (rs.getString(5) ?: ""),
                    )
                }
            }

        for (v in versions) {
            val row = bibleRow(v.code, "version")
            when {
                row == null -> failures += "version ${v.code}: no source_provenance row"
                row["ship"] !in okBibleClasses -> failures += "version ${v.code}: ship_class '${row["ship"]}' is not distributable"
                row["credit"].isNullOrBlank() -> failures += "version ${v.code}: no attribution text"
                else -> sources += Source(v.code, v.name, "Bible text", row.getValue("license"), row.getValue("credit"), row.getValue("url"), row["ship"] == "share_alike")
            }
        }
        for (t in bibleTables) {
            val row = bibleRow(t, "table")
            when {
                row == null -> failures += "table $t: no source_provenance row"
                row["ship"] !in okBibleClasses -> failures += "table $t: ship_class '${row["ship"]}' is not distributable"
                t == "bible_books" -> Unit
                else -> sources += Source(t, row.getValue("name"), if (t == "cross_references") "Cross-references" else "Word data",
                    row.getValue("license"), row.getValue("credit"), row.getValue("url"), row["ship"] == "share_alike")
            }
        }
        for (c in corpora) {
            conc.prepareStatement("SELECT name, license, ship_class, source_url FROM corpus WHERE code = ?").use { st ->
                st.setString(1, c)
                st.executeQuery().use { rs ->
                    if (!rs.next()) failures += "corpus $c: not in conc.db"
                    else {
                        val ship = rs.getString(3) ?: ""
                        if (okBibleClasses.none { ship.startsWith(it) }) failures += "corpus $c: ship_class '$ship' is not distributable"
                        if (c == "ENG-KJV") sources += Source("KJV-links", "KJV renderings of each Hebrew and Greek word (eBible.org kjv2006 Strong's tags)",
                            "Renderings", rs.getString(2) ?: "", "King James Version Strong's tagging, eBible.org (eng-kjv2006), public domain.", rs.getString(4) ?: "", false)
                    }
                }
            }
        }
        for (l in lexicons) {
            conc.prepareStatement("SELECT name, year, license, ship_class, url, credit FROM meaning_source WHERE code = ?").use { st ->
                st.setString(1, l)
                st.executeQuery().use { rs ->
                    if (!rs.next()) failures += "lexicon $l: not in conc.db"
                    else {
                        val ship = rs.getString(4) ?: ""
                        val credit = rs.getString(6) ?: ""
                        if (ship != "open") failures += "lexicon $l: ship_class '$ship' is not 'open'"
                        else if (credit.isBlank()) failures += "lexicon $l: no credit text"
                        else {
                            val license = rs.getString(3) ?: ""
                            sources += Source(l, "${rs.getString(1)} (${rs.getString(2)})", "Lexicon", license, credit, rs.getString(5) ?: "", license.contains("SA"))
                        }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) throw RightsGateFailure("Rights gate refused the build:\n  " + failures.joinToString("\n  "))
        return sources
    }
}
