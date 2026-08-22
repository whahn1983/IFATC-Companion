package com.h3consultingpartners.ifatccompanion.core.atis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The active-runway scan drives which runways the simulated background chatter uses, so
 * a mis-read here puts traffic on a runway the field isn't using. These lock the coded
 * grammar against real published D-ATIS wording.
 */
class ATISRunwayParserTest {

    private fun parse(text: String, kind: AirportATIS.Kind = AirportATIS.Kind.COMBINED) =
        ATISRunwayParser.parse(text, kind)

    @Test
    fun departureAndArrivalKeywordsSplitTheRunways() {
        val r = parse("ILS RWY 24R APCH IN USE. DEPG RWY 25R.")
        assertEquals(listOf("25R"), r.departures)
        assertEquals(listOf("24R"), r.arrivals)
    }

    @Test
    fun aCombinedPhraseMarksBothOperations() {
        val r = parse("LDG AND DEPG RWY 13.")
        assertEquals(listOf("13"), r.departures)
        assertEquals(listOf("13"), r.arrivals)
    }

    @Test
    fun connectorsAndRepeatedKeywordsAreSkipped() {
        val r = parse("DEPG RWY 24R AND RWY 25L, 25R.")
        assertEquals(listOf("24R", "25L", "25R"), r.departures)
    }

    /** Leading zeros are dropped so "04L" compares equal to the map's "4L". */
    @Test
    fun runwayIdentsAreCanonical() {
        assertEquals(listOf("4L"), parse("DEPG RWY 04L.").departures)
        assertEquals("9", ATISRunwayParser.canonical("09"))
        assertEquals("4L", ATISRunwayParser.canonical("04l"))
    }

    /** Some feeds publish the keyword flush against the designator. */
    @Test
    fun aFlushRunwayKeywordIsStillRead() {
        assertEquals(listOf("8R"), parse("DEPG RY8R.").departures)
    }

    /** An arrival-only ATIS defaults an un-keyworded runway to arrivals. */
    @Test
    fun theAtisKindSuppliesTheDefaultOperation() {
        val arrival = parse("RWY 16C IN USE.", AirportATIS.Kind.ARRIVAL)
        assertEquals(listOf("16C"), arrival.arrivals)
        assertTrue(arrival.departures.isEmpty())

        val departure = parse("RWY 16C IN USE.", AirportATIS.Kind.DEPARTURE)
        assertEquals(listOf("16C"), departure.departures)
        assertTrue(departure.arrivals.isEmpty())
    }

    /**
     * The regression this rule exists for: a runway named only in an outage or
     * surface-condition report is not a runway in use, and must not inherit the
     * combined-ATIS "both operations" default.
     */
    @Test
    fun anOutageReportIsNotARunwayInUse() {
        assertTrue(parse("RWY 9L PAPI OTS.").isEmpty)
        assertTrue(parse("RWY 22L COND CODE 5 5 5 AT 1630Z.").isEmpty)
        assertTrue(parse("RWY 15 CLSD.").isEmpty)
    }

    /**
     * One documented edge of the iOS scanner, carried across unchanged: the outage
     * report "RWY 22R LOC OTS" names an approach aid whose abbreviation ("LOC") is
     * itself an arrival keyword, so the status scan stops at it and the runway is read
     * as in use. Locked here so the divergence is a decision rather than a surprise —
     * `IFATCCompanion/ATIS/ATISRunwayParser.swift` behaves identically, and chatter
     * naming a runway the field is in fact using is the benign direction to be wrong in.
     */
    @Test
    fun anOutageNamedForAnApproachAidStillReadsAsInUse() {
        val r = parse("RWY 22R LOC OTS.")
        assertEquals(listOf("22R"), r.departures)
        assertEquals(listOf("22R"), r.arrivals)
    }

    /** An explicitly keyworded group is trusted even when a status word follows. */
    @Test
    fun anExplicitKeywordSurvivesATrailingStatusWord() {
        val r = parse("ILS RWY 22R APCH IN USE. RWY 22R LOC OTS.")
        assertEquals(listOf("22R"), r.arrivals)
    }

    /** Each group in a clause re-derives its own context. */
    @Test
    fun contextIsConsumedPerGroup() {
        val r = parse("DEPG RWY 1L LDG RWY 19R")
        assertEquals(listOf("1L"), r.departures)
        assertEquals(listOf("19R"), r.arrivals)
    }

    /** Across parts, results merge de-duplicated in first-seen order. */
    @Test
    fun partsMergeInFirstSeenOrder() {
        val atis = AirportATIS(
            airport = "KATL",
            parts = listOf(
                AirportATIS.Part(AirportATIS.Kind.DEPARTURE, "C", "DEPG RWYS 8R, 9L."),
                AirportATIS.Part(AirportATIS.Kind.ARRIVAL, "B", "ILS RWY 8L APCH IN USE. LDG RWY 9R."),
            ),
            fetchedAtMillis = 0,
        )
        val r = ATISRunwayParser.activeRunways(atis)
        assertEquals(listOf("8R", "9L"), r.departures)
        assertEquals(listOf("8L", "9R"), r.arrivals)
    }

    @Test
    fun textWithNoRunwayGrammarYieldsNothing() {
        assertTrue(parse("SEATTLE TACOMA INTL ATIS INFO S 2153Z. 16008KT 10SM FEW035.").isEmpty)
    }

    /** A number outside 1…36 is not a runway. */
    @Test
    fun anOutOfRangeDesignatorIsNotARunway() {
        assertTrue(parse("DEPG RWY 41.").isEmpty)
    }
}
