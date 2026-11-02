package sbphone.fold

/** How much of the Hebrew pointing to show. */
enum class PointingLevel { Full, Vowels, Consonants }

/**
 * Removes Hebrew pointing for display, by level. Full keeps everything; Vowels removes the
 * cantillation (accents) and meteg; Consonants also removes the vowel points, dagesh, shin/sin
 * dots and rafe. Maqaf, paseq, sof pasuq and nun hafukha (punctuation) always stay.
 */
object Pointing {
    private fun isCantillation(c: Char) = c in '֑'..'֯' || c == 'ֽ'
    private fun isPoint(c: Char) = c in 'ְ'..'ּ' || c == 'ֿ' || c == 'ׁ' || c == 'ׂ' ||
        c == 'ׄ' || c == 'ׅ' || c == 'ׇ'

    fun apply(text: String, level: PointingLevel): String = when (level) {
        PointingLevel.Full -> text
        PointingLevel.Vowels -> text.filterNot { isCantillation(it) }
        PointingLevel.Consonants -> text.filterNot { isCantillation(it) || isPoint(it) }
    }
}
