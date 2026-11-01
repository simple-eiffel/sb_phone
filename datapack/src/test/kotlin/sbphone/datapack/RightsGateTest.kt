package sbphone.datapack

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The gate must refuse when any allowlisted item is not marked distributable in its register. */
class RightsGateTest {

    private fun registers(
        versionClass: (String) -> String = { "open" },
        lexiconClass: (String) -> String = { "open" },
        corpusClass: (String) -> String = { "attribution" },
    ): Pair<Connection, Connection> {
        val bible = DriverManager.getConnection("jdbc:sqlite::memory:")
        bible.createStatement().use { st ->
            st.execute("CREATE TABLE source_provenance(target TEXT, kind TEXT, source_name TEXT, source_url TEXT, license TEXT, attribution TEXT, ship_class TEXT)")
        }
        bible.prepareStatement("INSERT INTO source_provenance VALUES (?,?,?,?,?,?,?)").use { ins ->
            for (v in Allowlist.versions) {
                ins.setString(1, v.code); ins.setString(2, "version"); ins.setString(3, v.name); ins.setString(4, "u"); ins.setString(5, "PD"); ins.setString(6, "credit"); ins.setString(7, versionClass(v.code)); ins.executeUpdate()
            }
            for (t in Allowlist.bibleTables) {
                ins.setString(1, t); ins.setString(2, "table"); ins.setString(3, t); ins.setString(4, "u"); ins.setString(5, "CC BY 4.0"); ins.setString(6, "credit"); ins.setString(7, "attribution"); ins.executeUpdate()
            }
        }
        val conc = DriverManager.getConnection("jdbc:sqlite::memory:")
        conc.createStatement().use { st ->
            st.execute("CREATE TABLE corpus(code TEXT, name TEXT, license TEXT, ship_class TEXT, source_url TEXT)")
            st.execute("CREATE TABLE meaning_source(code TEXT, name TEXT, year TEXT, license TEXT, ship_class TEXT, url TEXT, credit TEXT)")
        }
        conc.prepareStatement("INSERT INTO corpus VALUES (?,?,?,?,?)").use { ins ->
            for (c in Allowlist.corpora) { ins.setString(1, c); ins.setString(2, c); ins.setString(3, "CC BY 4.0"); ins.setString(4, corpusClass(c)); ins.setString(5, "u"); ins.executeUpdate() }
        }
        conc.prepareStatement("INSERT INTO meaning_source VALUES (?,?,?,?,?,?,?)").use { ins ->
            for (l in Allowlist.lexicons + Allowlist.englishDictionaries) { ins.setString(1, l); ins.setString(2, l); ins.setString(3, "1900"); ins.setString(4, "CC BY 4.0"); ins.setString(5, lexiconClass(l)); ins.setString(6, "u"); ins.setString(7, "credit"); ins.executeUpdate() }
        }
        return bible to conc
    }

    @Test fun passesWhenEveryRegisterAgrees() {
        val (b, c) = registers()
        val sources = Allowlist.check(b, c)
        // versions + three credited tables (MACULA Hebrew, MACULA Greek, cross-references) + KJV links + lexicons + English dictionaries
        assertEquals(Allowlist.versions.size + 3 + 1 + Allowlist.lexicons.size + Allowlist.englishDictionaries.size, sources.size)
    }

    @Test fun refusesADowngradedVersion() {
        val (b, c) = registers(versionClass = { if (it == "KJV") "personal_only" else "open" })
        val e = assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
        assertTrue(e.message!!.contains("version KJV"))
    }

    @Test fun refusesAnUnknownLicense() {
        val (b, c) = registers(versionClass = { if (it == "Wyc") "unknown" else "open" })
        assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
    }

    @Test fun refusesARestrictedLexicon() {
        val (b, c) = registers(lexiconClass = { if (it == "LSJ") "internal" else "open" })
        val e = assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
        assertTrue(e.message!!.contains("lexicon LSJ"))
    }

    @Test fun refusesARestrictedCorpus() {
        val (b, c) = registers(corpusClass = { if (it == "NT-MACULA") "restricted_nc (RESEARCH ONLY)" else "attribution" })
        assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
    }

    @Test fun refusesAMissingRegisterRow() {
        val (b, c) = registers()
        b.createStatement().use { it.execute("DELETE FROM source_provenance WHERE target = 'TgJ'") }
        val e = assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
        assertTrue(e.message!!.contains("TgJ"))
    }

    @Test fun neverAllowlistsTheRestrictedTexts() {
        val never = setOf("LXX", "TR", "Byz", "SP", "TgO", "TgN", "TgW", "Peshitta", "NWT2013", "NWT1984")
        assertTrue(Allowlist.versions.none { it.code in never })
        assertTrue("STEP" !in Allowlist.lexicons)
        assertTrue("SEED-KJVSHIFT" !in Allowlist.englishDictionaries)
    }

    @Test fun refusesARestrictedEnglishDictionary() {
        val (b, c) = registers(lexiconClass = { if (it == "WEBSTER1828") "internal" else "open" })
        val e = assertFailsWith<RightsGateFailure> { Allowlist.check(b, c) }
        assertTrue(e.message!!.contains("WEBSTER1828"))
    }
}
