package sbphone.fold

/**
 * A typed search, read into clauses that are all required (AND). Each clause is one or
 * more alternatives (OR). Excluded clauses must not match.
 *
 * Syntax: words (all required), "exact phrases", prefix* , A OR B (also A | B), -word.
 * A Strong's number on its own (H7225, G3056, strongs:H1254) is a word lookup, not a
 * text search.
 */
data class SearchQuery(
    val required: List<List<Match>>,
    val excluded: List<Match>,
) {
    val isEmpty: Boolean get() = required.isEmpty()
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
    data class Error(val message: String) : QueryParse
}

object QueryParser {

    private val strongs = Regex("""^\s*(?:strongs:)?([HhGg])0*(\d{1,5})([a-z]?)\s*$""")

    fun parse(input: String): QueryParse {
        strongs.matchEntire(input)?.let { m ->
            val lang = if (m.groupValues[1].uppercase() == "H") "hbo" else "grc"
            return QueryParse.Strongs(lang, m.groupValues[1].uppercase() + m.groupValues[2] + m.groupValues[3])
        }
        val tokens = tokenize(input) ?: return QueryParse.Error("A quotation mark is not closed.")
        val required = mutableListOf<MutableList<Match>>()
        val excluded = mutableListOf<Match>()
        var joinNext = false
        for (t in tokens) {
            if (t.isOr) {
                if (required.isEmpty()) return QueryParse.Error("OR needs a word before it.")
                joinNext = true
                continue
            }
            val match = toMatch(t) ?: continue
            if (t.negated) {
                excluded += match
                joinNext = false
            } else if (joinNext) {
                required.last() += match
                joinNext = false
            } else {
                required += mutableListOf(match)
            }
        }
        if (joinNext) return QueryParse.Error("OR needs a word after it.")
        if (required.isEmpty()) {
            return QueryParse.Error(if (excluded.isEmpty()) "Type a word to search for." else "Add a word to look for, not only words to leave out.")
        }
        return QueryParse.Text(SearchQuery(required, excluded))
    }

    private data class Token(val text: String, val quoted: Boolean, val negated: Boolean, val isOr: Boolean)

    private fun toMatch(t: Token): Match? {
        val prefix = t.text.endsWith("*")
        val words = Fold.words(t.text.removeSuffix("*"))
        if (words.isEmpty()) return null
        return Match(words, prefix)
    }

    private fun tokenize(input: String): List<Token>? {
        val out = mutableListOf<Token>()
        var i = 0
        val s = input.trim()
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '|' -> { out += Token("", false, false, true); i++ }
                else -> {
                    var negated = false
                    if (c == '-' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                        negated = true
                        i++
                    }
                    if (i < s.length && (s[i] == '"' || s[i] == '“')) {
                        val close = s.indexOfAny(charArrayOf('"', '”'), i + 1)
                        if (close < 0) return null
                        var text = s.substring(i + 1, close)
                        i = close + 1
                        if (i < s.length && s[i] == '*') { text += "*"; i++ }
                        out += Token(text, true, negated, false)
                    } else {
                        val start = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '|') i++
                        val word = s.substring(start, i)
                        if (word == "OR" && !negated) out += Token("", false, false, true)
                        else out += Token(word, false, negated, false)
                    }
                }
            }
        }
        return out
    }
}
