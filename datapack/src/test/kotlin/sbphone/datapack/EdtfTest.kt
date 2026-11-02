package sbphone.datapack

import kotlin.test.Test
import kotlin.test.assertEquals

class EdtfTest {
    @Test fun approximateBcInterval() {
        val s = Edtf.parse("-1299~/-0899~")!!
        assertEquals(-1299, s.earliest); assertEquals(-899, s.latest)
        assertEquals("c. 1300–c. 900 BC", s.label)
    }

    @Test fun exactAdDay() {
        val s = Edtf.parse("0014-08-19")!!
        assertEquals(14, s.earliest); assertEquals("19 August AD 14", s.label)
    }

    @Test fun astronomicalYears() {
        assertEquals("1 BC", Edtf.year(0)); assertEquals("2 BC", Edtf.year(-1)); assertEquals("AD 30", Edtf.year(30))
    }

    @Test fun uncertainSingleYear() {
        assertEquals("c. 587 BC (?)", Edtf.parse("-0586%")!!.label)
    }

    @Test fun unspecifiedDigits() {
        val s = Edtf.parse("-19XX")!!
        assertEquals(-1999, s.earliest); assertEquals(-1900, s.latest)
        assertEquals("2000–1901 BC", s.label)
    }

    @Test fun crossingEra() {
        assertEquals("c. 6 BC – AD 30", Edtf.parse("-0005~/0030")!!.label)
    }

    @Test fun prehistoricYears() {
        // Written as plain BC figures from years-before-present (Manot: 60,200 BP = 58,250 BC).
        val s = Edtf.parse("Y-58250/Y-47250")!!
        assertEquals(-58249, s.earliest); assertEquals(-47249, s.latest)
        assertEquals("58,250–47,250 BC", s.label)
        assertEquals("c. 193,050 BC", Edtf.parse("Y-193050~")!!.label)
        assertEquals("10,950–9551 BC", Edtf.parse("Y-10950/-9550")!!.label)
    }

    @Test fun rejectsNonsense() {
        assertEquals(null, Edtf.parse("sometime"))
    }
}
