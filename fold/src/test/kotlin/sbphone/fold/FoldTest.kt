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

    private fun tree(s: String) = (QueryParser.parse(s) as QueryParse.Text).query.root
    private fun text(vararg w: String, prefix: Boolean = false) = Node.Text(Match(w.toList(), prefix))

    @Test fun queryAndOrNot() {
        // OR binds the words beside it: love AND "one another" AND NOT hate AND faith, OR hope
        val root = tree("love \"one another\" -hate faith OR hope")
        assertEquals(Node.Or(listOf(
            Node.And(listOf(text("love"), text("one", "another"), Node.Not(text("hate")), text("faith"))),
            text("hope"),
        )), root)
        assertEquals("\"one another\"", Match(listOf("one", "another"), false).fts())
    }

    @Test fun queryParentheses() {
        assertEquals(Node.And(listOf(Node.Or(listOf(text("faith"), text("hope"))), text("love"))), tree("(faith OR hope) love"))
        assertEquals(Node.And(listOf(text("grace"), Node.Not(Node.Or(listOf(text("law"), text("works")))))), tree("grace NOT (law | works)"))
    }

    @Test fun queryPrefixAndHebrew() {
        assertEquals(Node.And(listOf(text("μιμ", prefix = true), text("ברא"))), tree("μιμ* בָּרָא"))
    }

    @Test fun queryWordTerms() {
        assertEquals(Node.And(listOf(Node.WordTerm("strongs", "H1254"), text("heaven"))), tree("H1254 heaven"))
        assertEquals(Node.WordTerm("morph", "V-AAI-3S"), tree("morph:V-AAI-3S"))
        assertEquals(Node.And(listOf(Node.WordTerm("morph", "Vqp*"), Node.WordTerm("lemma", "ברא"))), tree("morph:Vqp* lemma:ברא"))
        assertEquals(Node.WordTerm("lemma", "logos"), tree("lemma:logos"))
        assertTrue((QueryParser.parse("morph:Vqp3ms") as QueryParse.Text).query.hasWordTerms)
    }

    @Test fun queryErrorPositions() {
        assertEquals(QueryParse.Error("This ( is never closed.", 0), QueryParser.parse("(faith OR hope"))
        assertEquals(QueryParse.Error("This ) has no ( before it.", 5), QueryParser.parse("faith) hope"))
        assertEquals(QueryParse.Error("This quotation mark is never closed.", 6), QueryParser.parse("grace \"open"))
        assertEquals(6, (QueryParser.parse("faith OR") as QueryParse.Error).position)
        assertEquals(5, (QueryParser.parse("morph:") as QueryParse.Error).position) // at the colon
    }

    @Test fun hebrewPointingLevels() {
        val full = "בְּרֵאשִׁ֖ית בָּרָ֣א"
        assertEquals("בְּרֵאשִׁית בָּרָא", Pointing.apply(full, PointingLevel.Vowels))
        assertEquals("בראשית ברא", Pointing.apply(full, PointingLevel.Consonants))
        assertEquals(full, Pointing.apply(full, PointingLevel.Full))
        assertEquals("כל־אשר׃", Pointing.apply("כָּל־אֲשֶׁ֥ר׃", PointingLevel.Consonants))
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
        assertIs<QueryParse.Error>(QueryParser.parse("()"))
        assertTrue((QueryParser.parse("love OR") as QueryParse.Error).message.contains("after"))
    }
}
