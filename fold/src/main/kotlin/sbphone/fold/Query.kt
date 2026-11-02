package sbphone.fold

/**
 * A typed search, read into a tree.
 *
 * Syntax:
 * - words (all required): `grace peace`; `AND` may be written but is the default
 * - "exact phrases", prefixes: `believ*`
 * - either: `faith OR hope`, also `faith | hope`
 * - leave out: `-hate`, also `NOT hate`
 * - grouping: `(faith OR hope) love`
 * - original-language terms, matched on the Hebrew and Greek words of a verse:
 *   `strongs:H1254` (or a bare `H1254`, `G3056` inside a longer search),
 *   `lemma:ברא`, `lemma:logos`, `morph:Vqp3ms`, `morph:V-AAI-3S`, `morph:Vqp*`
 *
 * A Strong's number alone (`H7225`) is a word lookup, not a verse search.
 * Mistakes are reported with the character position where they occur.
 */
data class SearchQuery(val root: Node) {
    /** Every text match in the tree, for highlighting found words. */
    val textMatches: List<Match> get() = collect(root).filterIsInstance<Node.Text>().filter { !it.negated }.map { it.match }
    /** True when any term must be matched against the Hebrew and Greek words rather than a version's text. */
    val hasWordTerms: Boolean get() = collect(root).any { it is Node.WordTerm }

    private fun collect(n: Node): List<Node> = when (n) {
        is Node.And -> n.items.flatMap { collect(it) }
        is Node.Or -> n.items.flatMap { collect(it) }
        is Node.Not -> collect(n.item).map { if (it is Node.Text) it.copy(negated = true) else it }
        else -> listOf(n)
    }
}

sealed interface Node {
    /** A folded word, phrase or prefix matched against a version's text. */
    data class Text(val match: Match, val negated: Boolean = false) : Node
    /** A term matched against the original-language words: field is strongs, lemma or morph. */
    data class WordTerm(val field: String, val value: String) : Node
    data class And(val items: List<Node>) : Node
    data class Or(val items: List<Node>) : Node
    data class Not(val item: Node) : Node
}

/** One FTS match: a folded word, a folded phrase, or a folded prefix. */
data class Match(val words: List<String>, val prefix: Boolean) {
    /** The FTS4 MATCH expression for this match alone (standard query syntax only). */
    fun fts(): String = when {
        words.size > 1 -> "\"" + words.joinToString(" ") + (if (prefix) "*" else "") + "\""
        prefix -> words[0] + "*"
        else -> words[0]
    }
}

sealed interface QueryParse {
    data class Text(val query: SearchQuery) : QueryParse
    data class Strongs(val language: String, val number: String) : QueryParse
    /** [position] is the 0-based character where the problem is, or -1. */
    data class Error(val message: String, val position: Int = -1) : QueryParse
}

object QueryParser {

    private val strongsAlone = Regex("""^\s*(?:strongs:)?([HhGg])0*(\d{1,5})([a-z]?)\s*$""")
    private val strongsTerm = Regex("""^(?:strongs:)?([HhGg])0*(\d{1,5})([a-z]?)$""", RegexOption.IGNORE_CASE)
    private val fields = setOf("strongs", "lemma", "morph")

    fun parse(input: String): QueryParse {
        strongsAlone.matchEntire(input)?.let { m ->
            val lang = if (m.groupValues[1].uppercase() == "H") "hbo" else "grc"
            return QueryParse.Strongs(lang, m.groupValues[1].uppercase() + m.groupValues[2] + m.groupValues[3])
        }
        val tokens = try { tokenize(input) } catch (e: QueryError) { return QueryParse.Error(e.message!!, e.position) }
        if (tokens.isEmpty()) return QueryParse.Error("Type a word to search for.")
        val p = Parser(tokens, input.length)
        val root = try {
            val n = p.or()
            if (!p.done) p.fail(if (p.peek.kind == Kind.RParen) "This ) has no ( before it." else "Unexpected \"${p.peek.text}\".")
            n
        } catch (e: QueryError) {
            return QueryParse.Error(e.message!!, e.position)
        }
        if (!hasPositive(root)) return QueryParse.Error("Add a word to look for, not only words to leave out.")
        return QueryParse.Text(SearchQuery(root))
    }

    private fun hasPositive(n: Node): Boolean = when (n) {
        is Node.Text, is Node.WordTerm -> true
        is Node.Not -> false
        is Node.And -> n.items.any { hasPositive(it) }
        is Node.Or -> n.items.all { hasPositive(it) }
    }

    private class QueryError(message: String, val position: Int) : Exception(message)

    private enum class Kind { Word, Phrase, LParen, RParen, Or, And, Not }

    private data class Token(val kind: Kind, val text: String, val position: Int, val prefix: Boolean = false)

    private class Parser(val tokens: List<Token>, val end: Int) {
        var i = 0
        val done get() = i >= tokens.size
        val peek get() = tokens[i]

        fun fail(message: String, at: Int = if (done) end else peek.position): Nothing = throw QueryError(message, at)

        // or := and ( OR and )*
        fun or(): Node {
            val items = mutableListOf(and())
            while (!done && peek.kind == Kind.Or) {
                val at = peek.position
                i++
                if (done || peek.kind == Kind.RParen || peek.kind == Kind.Or) fail("OR needs a word after it.", at)
                items += and()
            }
            return if (items.size == 1) items[0] else Node.Or(items)
        }

        // and := unary ( [AND] unary )*
        fun and(): Node {
            val items = mutableListOf(unary())
            while (!done && peek.kind != Kind.Or && peek.kind != Kind.RParen) {
                if (peek.kind == Kind.And) {
                    val at = peek.position
                    i++
                    if (done || peek.kind == Kind.RParen || peek.kind == Kind.Or) fail("AND needs a word after it.", at)
                }
                items += unary()
            }
            return if (items.size == 1) items[0] else Node.And(items)
        }

        // unary := NOT unary | primary
        fun unary(): Node {
            if (done) fail("A word is missing here.")
            if (peek.kind == Kind.Not) {
                val at = peek.position
                i++
                if (done) fail("NOT needs a word after it.", at)
                return Node.Not(unary())
            }
            return primary()
        }

        // primary := ( or ) | term
        fun primary(): Node {
            val t = peek
            return when (t.kind) {
                Kind.LParen -> {
                    i++
                    if (!done && peek.kind == Kind.RParen) fail("These parentheses are empty.", t.position)
                    val n = or()
                    if (done || peek.kind != Kind.RParen) fail("This ( is never closed.", t.position)
                    i++
                    n
                }
                Kind.RParen -> fail("This ) has no ( before it.")
                Kind.Or -> fail("OR needs a word before it.")
                Kind.And -> fail("AND needs a word before it.")
                Kind.Phrase -> { i++; text(t) }
                Kind.Word -> { i++; term(t) }
                Kind.Not -> fail("NOT is misplaced here.")
            }
        }

        private fun text(t: Token): Node {
            val words = Fold.words(t.text)
            if (words.isEmpty()) fail("Nothing searchable in \"${t.text}\".", t.position)
            return Node.Text(Match(words, t.prefix))
        }

        private fun term(t: Token): Node {
            strongsTerm.matchEntire(t.text)?.let { m ->
                return Node.WordTerm("strongs", m.groupValues[1].uppercase() + m.groupValues[2] + m.groupValues[3])
            }
            val colon = t.text.indexOf(':')
            if (colon > 0) {
                val field = t.text.substring(0, colon).lowercase()
                val value = t.text.substring(colon + 1)
                if (field in fields) {
                    if (value.isEmpty() || value == "*") fail("$field: needs something after the colon.", t.position + colon)
                    return Node.WordTerm(field, if (field == "lemma") value.trimEnd('*') else value)
                }
            }
            return text(t)
        }
    }

    private fun tokenize(input: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        val s = input
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out += Token(Kind.LParen, "(", i); i++ }
                c == ')' -> { out += Token(Kind.RParen, ")", i); i++ }
                c == '|' -> { out += Token(Kind.Or, "|", i); i++ }
                c == '-' && i + 1 < s.length && !s[i + 1].isWhitespace() && (out.isEmpty() || s[i - 1].isWhitespace() || s[i - 1] == '(') -> {
                    out += Token(Kind.Not, "-", i); i++
                }
                c == '"' || c == '“' -> {
                    val close = s.indexOfAny(charArrayOf('"', '”'), i + 1)
                    if (close < 0) throw QueryError("This quotation mark is never closed.", i)
                    var prefix = false
                    var next = close + 1
                    if (next < s.length && s[next] == '*') { prefix = true; next++ }
                    out += Token(Kind.Phrase, s.substring(i + 1, close), i, prefix)
                    i = next
                }
                else -> {
                    val start = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] !in "()|\"“") i++
                    val word = s.substring(start, i)
                    out += when (word) {
                        "OR" -> Token(Kind.Or, word, start)
                        "AND" -> Token(Kind.And, word, start)
                        "NOT" -> Token(Kind.Not, word, start)
                        else -> {
                            val prefix = word.endsWith("*") && !word.contains(':')
                            Token(Kind.Word, if (prefix) word.trimEnd('*') else word, start, prefix)
                        }
                    }
                }
            }
        }
        return out
    }
}
