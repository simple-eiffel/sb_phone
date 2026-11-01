package sbphone.fold

/** One book of the 66-book canon. [id] follows canon order, 1 = Genesis ... 66 = Revelation. */
data class Book(
    val id: Int,
    val code: String,
    val name: String,
    val short: String,
    val aliases: List<String>,
) {
    val isOldTestament: Boolean get() = id <= 39
}

object Books {

    val all: List<Book> = listOf(
        b(1, "GEN", "Genesis", "Gen", "ge", "gn"),
        b(2, "EXO", "Exodus", "Exod", "ex", "exo"),
        b(3, "LEV", "Leviticus", "Lev", "le", "lv"),
        b(4, "NUM", "Numbers", "Num", "nu", "nm", "nb"),
        b(5, "DEU", "Deuteronomy", "Deut", "dt", "de"),
        b(6, "JOS", "Joshua", "Josh", "jsh"),
        b(7, "JDG", "Judges", "Judg", "jdgs", "jg"),
        b(8, "RUT", "Ruth", "Ruth", "rth", "ru"),
        b(9, "1SA", "1 Samuel", "1 Sam", "1sm", "1s", "isam", "1sam"),
        b(10, "2SA", "2 Samuel", "2 Sam", "2sm", "2s", "iisam", "2sam"),
        b(11, "1KI", "1 Kings", "1 Kgs", "1kg", "1k", "ikgs", "1kgs"),
        b(12, "2KI", "2 Kings", "2 Kgs", "2kg", "2k", "iikgs", "2kgs"),
        b(13, "1CH", "1 Chronicles", "1 Chr", "1chron", "ichr"),
        b(14, "2CH", "2 Chronicles", "2 Chr", "2chron", "iichr"),
        b(15, "EZR", "Ezra", "Ezra", "ezr"),
        b(16, "NEH", "Nehemiah", "Neh", "ne"),
        b(17, "EST", "Esther", "Esth", "es"),
        b(18, "JOB", "Job", "Job", "jb"),
        b(19, "PSA", "Psalms", "Ps", "psalm", "pslm", "psa", "psm", "pss"),
        b(20, "PRO", "Proverbs", "Prov", "pr", "prv"),
        b(21, "ECC", "Ecclesiastes", "Eccl", "ec", "eccles", "qoh"),
        b(22, "SNG", "Song of Songs", "Song", "song of solomon", "sos", "so", "canticles", "cant"),
        b(23, "ISA", "Isaiah", "Isa", "is"),
        b(24, "JER", "Jeremiah", "Jer", "je", "jr"),
        b(25, "LAM", "Lamentations", "Lam", "la"),
        b(26, "EZK", "Ezekiel", "Ezek", "eze", "ezk"),
        b(27, "DAN", "Daniel", "Dan", "da", "dn"),
        b(28, "HOS", "Hosea", "Hos", "ho"),
        b(29, "JOL", "Joel", "Joel", "jl"),
        b(30, "AMO", "Amos", "Amos", "am"),
        b(31, "OBA", "Obadiah", "Obad", "ob"),
        b(32, "JON", "Jonah", "Jonah", "jnh"),
        b(33, "MIC", "Micah", "Mic", "mc"),
        b(34, "NAM", "Nahum", "Nah", "na"),
        b(35, "HAB", "Habakkuk", "Hab", "hb"),
        b(36, "ZEP", "Zephaniah", "Zeph", "zp"),
        b(37, "HAG", "Haggai", "Hag", "hg"),
        b(38, "ZEC", "Zechariah", "Zech", "zc"),
        b(39, "MAL", "Malachi", "Mal", "ml"),
        b(40, "MAT", "Matthew", "Matt", "mt"),
        b(41, "MRK", "Mark", "Mark", "mk", "mrk", "mr"),
        b(42, "LUK", "Luke", "Luke", "lk", "lu"),
        b(43, "JHN", "John", "John", "jn", "jhn", "joh"),
        b(44, "ACT", "Acts", "Acts", "ac"),
        b(45, "ROM", "Romans", "Rom", "ro", "rm"),
        b(46, "1CO", "1 Corinthians", "1 Cor", "icor", "1cor"),
        b(47, "2CO", "2 Corinthians", "2 Cor", "iicor", "2cor"),
        b(48, "GAL", "Galatians", "Gal", "ga"),
        b(49, "EPH", "Ephesians", "Eph", "ephes"),
        b(50, "PHP", "Philippians", "Phil", "php", "pp"),
        b(51, "COL", "Colossians", "Col"),
        b(52, "1TH", "1 Thessalonians", "1 Thess", "1th", "ithess", "1thes"),
        b(53, "2TH", "2 Thessalonians", "2 Thess", "2th", "iithess", "2thes"),
        b(54, "1TI", "1 Timothy", "1 Tim", "1tm", "itim"),
        b(55, "2TI", "2 Timothy", "2 Tim", "2tm", "iitim"),
        b(56, "TIT", "Titus", "Titus", "ti"),
        b(57, "PHM", "Philemon", "Phlm", "philem", "phm", "pm"),
        b(58, "HEB", "Hebrews", "Heb", "he"),
        b(59, "JAS", "James", "Jas", "jm", "ja"),
        b(60, "1PE", "1 Peter", "1 Pet", "1pt", "1p", "ipet", "1pet"),
        b(61, "2PE", "2 Peter", "2 Pet", "2pt", "2p", "iipet", "2pet"),
        b(62, "1JN", "1 John", "1 John", "1jn", "1jhn", "ijn", "1jo"),
        b(63, "2JN", "2 John", "2 John", "2jn", "2jhn", "iijn", "2jo"),
        b(64, "3JN", "3 John", "3 John", "3jn", "3jhn", "iiijn", "3jo"),
        b(65, "JUD", "Jude", "Jude", "jud", "jd"),
        b(66, "REV", "Revelation", "Rev", "re", "rv", "apocalypse", "revelations"),
    )

    private val byId = all.associateBy { it.id }
    private val byCode = all.associateBy { it.code }

    fun byId(id: Int): Book = byId.getValue(id)
    fun byIdOrNull(id: Int): Book? = byId[id]
    fun byCode(code: String): Book? = byCode[code.uppercase()]

    private fun b(id: Int, code: String, name: String, short: String, vararg aliases: String) =
        Book(id, code, name, short, aliases.toList())
}
