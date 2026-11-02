package sbphone.datapack

/**
 * Reads the EDTF dates used by the timeline sidecars: a date or an interval "a/b"; each side a
 * signed astronomical year (0 = 1 BC, -1 = 2 BC) with optional -MM and -DD, unspecified digits X,
 * and qualifiers ~ (about), ? (uncertain), % (both). Returns the earliest and latest astronomical
 * years and a plain label such as "c. 1300–900 BC" or "19 August AD 14".
 */
object Edtf {
    data class Span(val earliest: Int, val latest: Int, val label: String, val approximate: Boolean, val uncertain: Boolean)

    private data class Side(val lo: Int, val hi: Int, val month: Int, val day: Int, val approx: Boolean, val uncertain: Boolean, val open: Boolean)

    // "Y" marks a year of more than four digits (prehistory): Y-193050.
    private val side = Regex("""^Y?(-?)(\d{1,4}|[\dX]{4}|(?<=Y-?)\d{5,})(?:-(\d{2}))?(?:-(\d{2}))?([~?%]?)$""")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun parse(text: String): Span? {
        val parts = text.trim().split('/')
        val sides = parts.map { readSide(it.trim()) ?: return null }
        val a = sides.first()
        val b = sides.last()
        val earliest = if (a.open) b.lo else a.lo
        val latest = if (b.open) a.hi else b.hi
        val approx = sides.any { it.approx }
        val uncertain = sides.any { it.uncertain }
        val label = if (sides.size == 1 || (a.lo == b.lo && a.hi == b.hi && !a.open && !b.open)) one(a) else range(a, b)
        return Span(earliest, latest, label, approx, uncertain)
    }

    private fun readSide(s: String): Side? {
        if (s == ".." || s.isEmpty()) return Side(0, 0, 0, 0, false, false, true)
        val m = side.matchEntire(s) ?: return null
        val neg = m.groupValues[1] == "-"
        val digits = m.groupValues[2]
        val loDigits = digits.replace('X', '0').toInt()
        val hiDigits = digits.replace('X', '9').toInt()
        // The timeline's prehistoric "Y" years are converted from years before present and written
        // as plain BC figures (Manot: 60,200 BP = 58,250 BC is written Y-58250), so shift them by one
        // into astronomical years; that makes them display exactly as written.
        val shift = if (s.startsWith("Y") && neg) 1 else 0
        val (lo, hi) = if (neg) (-hiDigits + shift) to (-loDigits + shift) else loDigits to hiDigits
        val q = m.groupValues[5]
        return Side(lo, hi, m.groupValues[3].toIntOrNull() ?: 0, m.groupValues[4].toIntOrNull() ?: 0, q == "~" || q == "%", q == "?" || q == "%", false)
    }

    /** An astronomical year as people write it: 1300 BC, AD 14, 1066. */
    fun year(y: Int): String = when {
        y <= 0 -> "${n(1 - y)} BC"
        y < 1000 -> "AD $y"
        else -> "$y"
    }

    /** Thousands separators only for prehistoric figures (10,000 and up), not for 1300 BC. */
    private fun n(v: Int): String = if (v >= 10_000) String.format(java.util.Locale.US, "%,d", v) else v.toString()

    private fun one(s: Side): String {
        val base = when {
            s.lo != s.hi -> if (s.hi <= 0) "${n(1 - s.lo)}–${n(1 - s.hi)} BC" else "${s.lo}–${s.hi}"
            s.day > 0 -> "${s.day} ${months[s.month - 1]} ${year(s.lo)}"
            s.month > 0 -> "${months[s.month - 1]} ${year(s.lo)}"
            else -> year(s.lo)
        }
        return (if (s.approx) "c. " else "") + base + if (s.uncertain) " (?)" else ""
    }

    private fun range(a: Side, b: Side): String {
        if (a.open) return "until " + one(b)
        if (b.open) return "from " + one(a)
        val aL = if (a.approx) "c. " else ""
        val bL = if (b.approx) "c. " else ""
        val q = if (a.uncertain || b.uncertain) " (?)" else ""
        return when {
            a.lo <= 0 && b.hi <= 0 -> "$aL${n(1 - a.lo)}–$bL${n(1 - b.hi)} BC$q"
            a.lo <= 0 -> "$aL${n(1 - a.lo)} BC – ${bL}AD ${b.hi}$q"
            else -> "$aL${year(a.lo)}–$bL${b.hi}$q"
        }
    }
}
