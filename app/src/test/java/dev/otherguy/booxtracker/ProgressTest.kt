package dev.otherguy.booxtracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test fun fractionAndNormalizedUnits() {
        assertEquals("42", Progress.parse("4200/10000").percent)
        assertEquals("50", Progress.parse("1.5/3").percent)
        assertEquals("0", Progress.parse("0/100").percent)
    }

    @Test fun unknownNeverBecomesZero() {
        listOf(null, "", "oops", "1/0", "-1/10", "11/10", "1/-2", "NaN/2").forEach { assertNull(Progress.parse(it).percent) }
        assertEquals("zero denominator", Progress.parse("1/0").problem)
    }

    @Test fun rawAndLargeValuesSurvive() {
        assertEquals(" 1 / 2 ", Progress.parse(" 1 / 2 ").raw)
        assertEquals("50", Progress.parse("999999999999999999999999/1999999999999999999999998").percent)
    }
}
