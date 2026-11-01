package sbphone.fold

import java.text.Normalizer
import java.util.Locale

/**
 * Search folding, shared by the data builder (index time) and the app (query time),
 * so the index and the query can never disagree.
 *
 * - Unicode NFD, then every combining mark is dropped: Hebrew points and cantillation,
 *   Greek accents and breathings, Latin diacritics (æ is kept as a letter).
 * - Lower case (root locale); Greek final sigma becomes sigma; Hebrew final letters
 *   become their ordinary forms.
 * - Apostrophes are removed ("God's" -> "gods"); every other character that is not a
 *   letter or digit becomes a space, and runs of spaces collapse to one.
 */
object Fold {

    private val hebrewFinals = mapOf(
        'ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ',
    )

    private val apostrophes = setOf('\'', '’', '‘', 'ʼ', '`')

    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var pendingSpace = false
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.FORMAT.toInt()
            ) continue
            if (cp < 0x10000 && cp.toChar() in apostrophes) continue
            if (Character.isLetterOrDigit(cp)) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                val lower = String(Character.toChars(cp)).lowercase(Locale.ROOT)
                for (ch in lower) {
                    out.append(
                        when (ch) {
                            'ς' -> 'σ'
                            else -> hebrewFinals[ch] ?: ch
                        }
                    )
                }
            } else {
                pendingSpace = true
            }
        }
        return out.toString()
    }

    /** The folded words of [text], in order. */
    fun words(text: String): List<String> =
        fold(text).split(' ').filter { it.isNotEmpty() }
}
