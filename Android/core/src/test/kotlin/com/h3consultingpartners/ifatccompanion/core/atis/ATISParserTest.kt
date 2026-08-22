package com.h3consultingpartners.ifatccompanion.core.atis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The D-ATIS feed is public and un-versioned, so the parser has to be defensive about
 * every shape it has been seen to return. These lock the "no data is ever invented"
 * contract: an unrecognised payload yields null, which the app reads as "this field has
 * no ATIS" and hides the feature.
 */
class ATISParserTest {

    private val now = 1_700_000_000_000L

    @Test
    fun parsesACombinedReport() {
        val body = """
            [{"airport":"KSEA","type":"combined","code":"S",
              "datis":"SEATTLE TACOMA INTL ATIS INFO S 2153Z. 16008KT 10SM FEW035. ADVS YOU HAVE INFO S."}]
        """.trimIndent()
        val atis = ATISParser.parse(body, airport = "KSEA", nowMillis = now)!!
        assertEquals("KSEA", atis.airport)
        assertEquals(1, atis.parts.size)
        assertEquals(AirportATIS.Kind.COMBINED, atis.parts[0].kind)
        assertEquals("S", atis.parts[0].letter)
        assertEquals(now, atis.fetchedAtMillis)
    }

    /** A field publishing separate arrival and departure ATIS carries two letters. */
    @Test
    fun parsesSeparateArrivalAndDepartureParts() {
        val body = """
            [{"airport":"KATL","type":"arr","code":"B","datis":"ATLANTA ARR INFO B."},
             {"airport":"KATL","type":"dep","code":"C","datis":"ATLANTA DEP INFO C."}]
        """.trimIndent()
        val atis = ATISParser.parse(body, airport = "KATL", nowMillis = now)!!
        assertEquals("B", atis.letter(arrival = true))
        assertEquals("C", atis.letter(arrival = false))
    }

    /**
     * A field that publishes only one part still resolves for both phases — the
     * fallback chain is preferred kind, then combined, then anything.
     */
    @Test
    fun asinglePartResolvesForBothPhases() {
        val body = """[{"airport":"KBOI","type":"arr","code":"D","datis":"BOISE ARR INFO D."}]"""
        val atis = ATISParser.parse(body, airport = "KBOI", nowMillis = now)!!
        assertEquals("D", atis.letter(arrival = true))
        assertEquals("D", atis.letter(arrival = false))
    }

    /** The feed answers an unknown field with an error object, not an array. */
    @Test
    fun anErrorObjectIsNotAnAtis() {
        assertNull(ATISParser.parse("""{"error":"not found"}""", "KXYZ", now))
    }

    @Test
    fun anEmptyArrayIsNotAnAtis() {
        assertNull(ATISParser.parse("[]", "KXYZ", now))
    }

    @Test
    fun malformedJsonIsNotAnAtis() {
        assertNull(ATISParser.parse("<html>502 Bad Gateway</html>", "KXYZ", now))
    }

    /** A part with no text is skipped; a payload of only such parts yields nothing. */
    @Test
    fun blankTextIsNotAnAtis() {
        assertNull(ATISParser.parse("""[{"airport":"KXYZ","type":"combined","datis":"   "}]""", "KXYZ", now))
    }

    // region Information code

    @Test
    fun theExplicitCodeFieldWins() {
        assertEquals("Q", ATISParser.infoLetter("Q", "…ADVS YOU HAVE INFORMATION ROMEO."))
    }

    @Test
    fun aPhoneticCodeWordResolvesToItsLetter() {
        assertEquals("A", ATISParser.infoLetter("ALPHA", ""))
    }

    /** A code arriving with punctuation around it ("A.", "(A)") still resolves. */
    @Test
    fun aDecoratedCodeResolvesToItsLetter() {
        assertEquals("A", ATISParser.infoLetter("A.", ""))
        assertEquals("A", ATISParser.infoLetter("(A)", ""))
    }

    /** With no code field, the closing "advise you have information X" is read. */
    @Test
    fun theClosingInformationPhraseIsTheFallback() {
        assertEquals("R", ATISParser.infoLetter(null, "KLAX ATIS INFO R 1953Z … ADVS YOU HAVE INFORMATION ROMEO."))
    }

    /**
     * The *last* mention wins: an ATIS body can quote an earlier letter ("INFORMATION
     * QUEBEC IS NO LONGER CURRENT") before closing with the current one.
     */
    @Test
    fun theLastInformationMentionWins() {
        val text = "INFORMATION QUEBEC NO LONGER CURRENT. ADVS YOU HAVE INFORMATION ROMEO."
        assertEquals("R", ATISParser.infoLetter(null, text))
    }

    @Test
    fun anUnrecognizableCodeIsNoLetter() {
        assertNull(ATISParser.infoLetter("", "SEATTLE TACOMA INTL 2153Z 16008KT."))
    }

    /** A letter that isn't a single character is not a usable information code. */
    @Test
    fun aMultiCharacterLetterIsRejectedByTheModel() {
        val atis = AirportATIS(
            airport = "KSEA",
            parts = listOf(AirportATIS.Part(AirportATIS.Kind.COMBINED, "AB", "…")),
            fetchedAtMillis = now,
        )
        assertNull(atis.letter(arrival = true))
    }

    // endregion
}
