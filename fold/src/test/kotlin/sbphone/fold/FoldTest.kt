package sbphone.fold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoldTest {

    @Test fun hebrewPointsAndCantillationDrop() {
        // Final mem folds to mem, so a typed final or ordinary form both match.
        assertEquals("בראשית ברא אלהימ", Fold.fold("בְּרֵאשִׁ֖ית בָּרָ֣א אֱלֹהִ֑ים"))
    }

    @Test fun hebrewFinalsAndSofPasuq() {
        assertEquals("הארצ", Fold.fold("הָאָֽרֶץ׃"))
        assertEquals(Fold.fold("שלום"), Fold.fold("שָׁלוֹם"))
    }

    @Test fun hebrewMaqafSplitsWords() {
        assertEquals("כל אשר", Fold.fold("כָּל־אֲשֶׁר"))
    }

    @Test fun greekAccentsBreathingsAndFinalSigma() {
        assertEquals("ουτωσ γαρ ηγαπησεν ο θεοσ", Fold.fold("Οὕτως γὰρ ἠγάπησεν ὁ θεὸς"))
        assertEquals(Fold.fold("θεος"), Fold.fold("θεὸς"))
    }

    @Test fun englishPunctuationAndApostrophes() {
        assertEquals("in the beginning of gods preparing the heavens and the earth",
            Fold.fold("In the beginning of God's preparing the heavens and the earth--"))
        assertEquals("is", Fold.fold("[is]"))
        assertEquals("oon bigetun", Fold.fold("`oon bigetun"))
    }

    @Test fun latinLigatureKept() {
        assertEquals("vitam æternam", Fold.fold("vitam æternam."))
    }

    @Test fun refsLoose() {
        assertEquals(RefParse.One(Ref(43, 3, 16)), RefParser.parse("jn 3 16"))
        assertEquals(RefParse.One(Ref(43, 3, 16)), RefParser.parse("John 3:16"))
        assertEquals(RefParse.One(Ref(60, 1, 3)), RefParser.parse("1pet 1:3"))
        assertEquals(RefParse.One(Ref(60, 1, 0)), RefParser.parse("1 Peter 1"))
        assertEquals(RefParse.One(Ref(19, 23, 0)), RefParser.parse("ps 23"))
        assertEquals(RefParse.One(Ref(45, 8, 28, 30)), RefParser.parse("Rom 8:28-30"))
        assertEquals(RefParse.One(Ref(22, 2, 1)), RefParser.parse("song of songs 2:1"))
        assertEquals(RefParse.One(Ref(1, 1, 0)), RefParser.parse("genesis"))
    }

    @Test fun ambiguousBookGivesChoice() {
        val p = RefParser.parse("ju 1")
        assertIs<RefParse.Choice>(p)
        assertEquals(setOf(7, 65), p.refs.map { it.book }.toSet())
    }

    @Test fun plainWordsAreNotRefs() {
        assertEquals(RefParse.NotARef, RefParser.parse("love"))
        assertEquals(RefParse.NotARef, RefParser.parse("grace and peace"))
    }

    @Test fun queryClauses() {
        val q = (QueryParser.parse("love \"one another\" -hate faith OR hope") as QueryParse.Text).query
        assertEquals(3, q.required.size)
        assertEquals(listOf("one", "another"), q.required[1][0].words)
        assertEquals(2, q.required[2].size)
        assertEquals(listOf("hate"), q.excluded[0].words)
        assertEquals("\"one another\"", q.required[1][0].fts())
    }

    @Test fun queryPrefixAndHebrew() {
        val q = (QueryParser.parse("μιμ* בָּרָא") as QueryParse.Text).query
        assertEquals("μιμ*", q.required[0][0].fts())
        assertEquals("ברא", q.required[1][0].fts())
    }

    @Test fun strongsLookup() {
        assertEquals(QueryParse.Strongs("hbo", "H7225"), QueryParser.parse("H7225"))
        assertEquals(QueryParse.Strongs("grc", "G3056"), QueryParser.parse("g03056"))
        assertEquals(QueryParse.Strongs("hbo", "H1254"), QueryParser.parse("strongs:H1254"))
    }

    @Test fun queryErrorsAreNamed() {
        assertIs<QueryParse.Error>(QueryParser.parse("\"open"))
        assertIs<QueryParse.Error>(QueryParser.parse("OR love"))
        assertIs<QueryParse.Error>(QueryParser.parse("-hate"))
        assertTrue((QueryParser.parse("love OR") as QueryParse.Error).message.contains("after"))
    }
}
