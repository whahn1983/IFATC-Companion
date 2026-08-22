package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `IFATCCompanionTests/WeatherTests.swift`.
 *
 * The ride-report cases in that file (`testRideReportEngineNoReports`,
 * `testRideReport…`, `testDestinationWeatherSpoken`, `testPilotRogerAcknowledgement`)
 * drive `RideReportEngine` / `PhraseologyEngine` / `PilotResponseEngine`, which live in
 * other packages; the two that also exercise the analyzer are ported here for their
 * analyzer half, with the phrase assertions left to the ride-report package.
 */
class WeatherTest {

    @Test
    fun rawMETARParsing() {
        val m = MetarParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        assertNotNull(m)
        assertEquals("KMSP", m.icao)
        assertEquals(320, m.windDirection)
        assertEquals(12, m.windSpeed)
        assertEquals(10.0, m.visibilitySM)
        assertEquals(2500, m.ceilingFt)
        assertEquals(18.0, m.temperatureC)
        assertEquals(11.0, m.dewpointC)
        assertEquals(30.12, m.altimeterInHg ?: 0.0, 0.001)
    }

    @Test
    fun rawMETARGustParsing() {
        val m = MetarParser.parseRaw("KDEN 281953Z 02015G24KT 10SM SCT080 24/06 A2998")
        assertEquals(20, m?.windDirection)
        assertEquals(15, m?.windSpeed)
        assertEquals(24, m?.windGust)
    }

    @Test
    fun routeAnalyzerFiltersByCorridorAndAltitude() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0

        val position = Coordinate(40.0, -95.0)
        val end = Coordinate(44.0, -93.0)

        val ahead = PIREP(
            raw = "ahead", coordinate = Coordinate(42.0, -94.0),
            altitudeFt = 35000, turbulence = TurbulenceSeverity.MODERATE,
        )
        val behind = PIREP(
            raw = "behind", coordinate = Coordinate(38.0, -96.0),
            altitudeFt = 35000, turbulence = TurbulenceSeverity.MODERATE,
        )
        val wrongAlt = PIREP(
            raw = "wrongAlt", coordinate = Coordinate(42.0, -94.0),
            altitudeFt = 20000, turbulence = TurbulenceSeverity.LIGHT,
        )
        val smooth = PIREP(
            raw = "smooth", coordinate = Coordinate(42.0, -94.0),
            altitudeFt = 35000, turbulence = TurbulenceSeverity.SMOOTH,
        )

        val items = analyzer.relevantReports(
            pireps = listOf(ahead, behind, wrongAlt, smooth),
            position = position, routeEnd = end, altitudeFt = 35000.0,
        )
        assertEquals(1, items.size)
        assertEquals(TurbulenceSeverity.MODERATE, items.first().severity)
        assertTrue((items.first().distanceAheadNM ?: 0.0) > 0)
    }

    /**
     * Each PIREP is labeled with the route fix nearest to *its own* position — not the
     * fix nearest the aircraft. A report hundreds of miles ahead must name the fix out
     * there, never the fix the aircraft happens to be abeam of.
     */
    @Test
    fun nearFixTracksPirepLocationNotAircraft() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0

        val aircraft = Coordinate(40.0, -95.0)
        val end = Coordinate(48.0, -95.0)
        // A severe PIREP ~360 NM ahead (6° of latitude), at a distant fix.
        val farCoord = Coordinate(46.0, -95.0)
        val pirep = PIREP(
            raw = "sev", coordinate = farCoord, altitudeFt = 35000,
            turbulence = TurbulenceSeverity.SEVERE, aircraftType = "B38M",
        )
        val fixes = listOf(
            WeatherRouteAnalyzer.NamedFix("INDIE", aircraft),  // abeam the aircraft
            WeatherRouteAnalyzer.NamedFix("ZENOB", farCoord),  // out at the PIREP
        )

        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = aircraft, routeEnd = end,
            altitudeFt = 35000.0, routeFixes = fixes,
        )
        assertEquals(1, items.size)
        assertEquals(
            "ZENOB", items.first().nearFix,
            "the fix must describe where the turbulence is, not where the aircraft is",
        )
        assertNotEquals("INDIE", items.first().nearFix)
    }

    /**
     * When no route fix lies within the proximity threshold of the PIREP, no fix is named
     * — the report keeps only "… miles ahead" rather than borrowing a distant fix.
     */
    @Test
    fun noNearFixWhenNoRouteFixIsClose() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0
        analyzer.config.fixProximityNM = 50.0

        val aircraft = Coordinate(40.0, -95.0)
        val end = Coordinate(48.0, -95.0)
        val pirep = PIREP(
            raw = "sev", coordinate = Coordinate(43.0, -95.0), altitudeFt = 35000,
            turbulence = TurbulenceSeverity.SEVERE, aircraftType = "B38M",
        )
        // Both fixes are ~180 NM (3°) from the PIREP — well beyond the 50 NM threshold.
        val fixes = listOf(
            WeatherRouteAnalyzer.NamedFix("INDIE", aircraft),
            WeatherRouteAnalyzer.NamedFix("ZENOB", end),
        )

        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = aircraft, routeEnd = end,
            altitudeFt = 35000.0, routeFixes = fixes,
        )
        assertEquals(1, items.size)
        assertNull(items.first().nearFix, "no fix is named when none is genuinely near the PIREP")
    }

    /**
     * A PIREP at cruise altitude is relevant when evaluated against the planned
     * cruise level, but not when evaluated against a much lower climb altitude —
     * this is why the ride model keys route reports off the flight-plan cruise
     * altitude (within tolerance) rather than the live altitude while climbing.
     */
    @Test
    fun reportsFilteredAgainstCruiseAltitudeWithinTolerance() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0

        val position = Coordinate(40.0, -95.0)
        val end = Coordinate(44.0, -93.0)
        val atCruise = PIREP(
            raw = "cruise", coordinate = Coordinate(42.0, -94.0), altitudeFt = 35000,
            turbulence = TurbulenceSeverity.MODERATE,
        )

        // Referenced against the planned cruise level → kept (within ±5000).
        val atCruiseRef = analyzer.relevantReports(
            pireps = listOf(atCruise), position = position, routeEnd = end, altitudeFt = 35000.0,
        )
        assertEquals(1, atCruiseRef.size)

        // Referenced against a 12,000 ft climb altitude → dropped (outside ±5000).
        val atClimbRef = analyzer.relevantReports(
            pireps = listOf(atCruise), position = position, routeEnd = end, altitudeFt = 12000.0,
        )
        assertTrue(atClimbRef.isEmpty())
    }

    @Test
    fun emptyPirepsProducesNoItems() {
        val analyzer = WeatherRouteAnalyzer()
        val items = analyzer.relevantReports(
            pireps = emptyList(),
            position = Coordinate(40.0, -95.0),
            routeEnd = Coordinate(44.0, -93.0),
            altitudeFt = 35000.0,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun turbulenceSeverityParsing() {
        assertEquals(TurbulenceSeverity.MODERATE, TurbulenceSeverity.parse("MOD"))
        assertEquals(TurbulenceSeverity.SEVERE, TurbulenceSeverity.parse("SEV"))
        // contains LGT
        assertEquals(TurbulenceSeverity.LIGHT, TurbulenceSeverity.parse("LGT CHOP"))
        assertEquals(TurbulenceSeverity.LIGHT_CHOP, TurbulenceSeverity.parse("CHOP"))
    }

    /**
     * Real AWC `pirep?format=json` shape: flight level is `fltLvl` (camelCase) and
     * turbulence is a code *string* in `tbInt1` (not an Int). Locks the parser to it.
     */
    @Test
    fun pirepParserMatchesRealAWCJSON() {
        val json = """
        [
          {"obsTime":1783796520,"lat":38.04,"lon":-87.53,"fltLvl":0,"fltLvlType":"DURD",
           "tbInt1":"","acType":"E55P","rawOb":"EVV UA /OV EVV/TM 1902/FLDURD/TP E55P/SK BKN020"},
          {"obsTime":1783796460,"lat":27.03,"lon":-81.80,"fltLvl":190,"fltLvlType":"OTHER",
           "tbInt1":"NEG","acType":"E50P","rawOb":"RSW UA /OV RSW360030/TM 1901/FL190/TP E50P/SK SKC/TB NEG"},
          {"obsTime":1783796400,"lat":43.55,"lon":-116.19,"fltLvl":110,"fltLvlType":"OTHER",
           "tbInt1":"MOD","acType":"E75L","rawOb":"BOI UA /OV SPUUD4 STAR/TM 1900/FL110/TP E75L/TB MOD TURB 110-090 DURD"}
        ]
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val pireps = PirepParser.parseJson(json)
        assertEquals(3, pireps.size)

        // fltLvl (camelCase) → feet; a 0 / during-descent level stays unknown (null).
        assertNull(pireps[0].altitudeFt, "fltLvl 0 (DURD) is unknown, not sea level")
        assertEquals(19000, pireps[1].altitudeFt)
        assertEquals(11000, pireps[2].altitudeFt)

        // tbInt1 is a code string: NEG → smooth (filtered out), MOD → moderate.
        assertEquals(TurbulenceSeverity.SMOOTH, pireps[1].turbulence)
        assertEquals(TurbulenceSeverity.MODERATE, pireps[2].turbulence)

        assertEquals(43.55, pireps[2].coordinate?.latitude ?: 0.0, 1e-6)
        assertNotNull(pireps[2].timeMillis, "obsTime epoch parses to a date")
    }

    private fun rideItem(
        sev: TurbulenceSeverity,
        altFt: Int,
        type: String = "B738",
    ): RideReportItem = RideReportItem(
        severity = sev, altitudeBand = null, distanceAheadNM = 30.0, bearing = 0.0,
        nearFix = null, sourceRaw = "", reportedAltitudeFt = altFt, aircraftType = type,
    )

    @Test
    fun smootherAltitudePicksNearestSmootherLevelInBand() {
        val analyzer = WeatherRouteAnalyzer()
        // Moderate at FL350; a smooth report at FL390 (4000 ft away) and a light one at
        // FL330 (2000 ft away). The nearest smoother level wins even though FL390 is
        // smoother — least altitude change to reach a better ride.
        val items = listOf(
            rideItem(TurbulenceSeverity.MODERATE, 35000),
            rideItem(TurbulenceSeverity.SMOOTH, 39000),
            rideItem(TurbulenceSeverity.LIGHT, 33000),
        )
        val s = analyzer.smootherAltitude(
            items = items, referenceAltFt = 35000, currentSeverity = TurbulenceSeverity.MODERATE,
        )
        assertEquals(33000, s?.altitudeFt, "prefers the nearest smoother level")
        assertEquals(false, s?.higher)
    }

    @Test
    fun smootherAltitudeBreaksSeparationTieBySmootherRide() {
        val analyzer = WeatherRouteAnalyzer()
        // Two candidates equidistant from FL350: light at FL370 (+2000) and smooth at
        // FL330 (-2000). Equal altitude change → the smoother of the two wins.
        val items = listOf(
            rideItem(TurbulenceSeverity.MODERATE, 35000),
            rideItem(TurbulenceSeverity.LIGHT, 37000),
            rideItem(TurbulenceSeverity.SMOOTH, 33000),
        )
        val s = analyzer.smootherAltitude(
            items = items, referenceAltFt = 35000, currentSeverity = TurbulenceSeverity.MODERATE,
        )
        assertEquals(33000, s?.altitudeFt, "at equal separation, prefers the smoother ride")
        assertEquals(TurbulenceSeverity.SMOOTH, s?.severity)
    }

    @Test
    fun smootherAltitudeIsDataDrivenAndBandBounded() {
        val analyzer = WeatherRouteAnalyzer()
        // Nothing smoother than the current level → no suggestion (never invented).
        assertNull(
            analyzer.smootherAltitude(
                items = listOf(rideItem(TurbulenceSeverity.MODERATE, 35000)),
                referenceAltFt = 35000, currentSeverity = TurbulenceSeverity.MODERATE,
            ),
        )
        // A smooth report above the cruise band (FL450) is out of range → not suggested.
        assertNull(
            analyzer.smootherAltitude(
                items = listOf(rideItem(TurbulenceSeverity.SMOOTH, 45000)),
                referenceAltFt = 35000, currentSeverity = TurbulenceSeverity.MODERATE,
            ),
        )
        // Smooth at your own level isn't a level change.
        assertNull(
            analyzer.smootherAltitude(
                items = listOf(rideItem(TurbulenceSeverity.SMOOTH, 35000)),
                referenceAltFt = 35000, currentSeverity = TurbulenceSeverity.MODERATE,
            ),
        )
    }

    /**
     * Without a live aircraft fix the analysis falls back to the departure airport, so the
     * along-track distance is origin-relative. It must be flagged and NOT presented as
     * "… miles ahead" (the distance-from-origin bug); instead the report falls back to a
     * route-relative phrase ("along your route near LIT").
     *
     * The phrase half of the guard belongs to the ride-report package; here we lock the
     * analyzer output it reads — the flag, the still-computed distance, and the fix label.
     */
    @Test
    fun rideReportUsesRouteFallbackWhenPositionIsNotLiveAircraft() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0

        val departure = Coordinate(30.0, -95.0)
        val end = Coordinate(44.0, -93.0)
        val pirepCoord = Coordinate(37.0, -94.0)
        val pirep = PIREP(
            raw = "sev", coordinate = pirepCoord, altitudeFt = 36000,
            turbulence = TurbulenceSeverity.SEVERE, aircraftType = "A319",
        )

        // A route fix at the PIREP's own position labels it "near LIT"; a far fix does not.
        val fixes = listOf(
            WeatherRouteAnalyzer.NamedFix("LIT", pirepCoord),
            WeatherRouteAnalyzer.NamedFix("FAR", departure),
        )
        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = departure, routeEnd = end,
            altitudeFt = 36000.0, routeFixes = fixes, positionIsLiveAircraft = false,
        )
        assertEquals(1, items.size)
        assertEquals(false, items.first().distanceIsFromAircraft)
        // The distance is still computed (the turbulence model weights by it) — only the
        // presentation is suppressed.
        assertTrue((items.first().distanceAheadNM ?: 0.0) > 0)
        assertEquals("LIT", items.first().nearFix)
    }

    /** With a live aircraft fix the distance is aircraft-relative and IS presented. */
    @Test
    fun rideReportShowsAircraftRelativeDistanceWithLivePosition() {
        val analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100.0
        analyzer.config.altitudeBandFt = 5000.0

        val aircraft = Coordinate(40.0, -94.5)
        val end = Coordinate(44.0, -93.0)
        val pirep = PIREP(
            raw = "sev", coordinate = Coordinate(42.0, -94.0), altitudeFt = 36000,
            turbulence = TurbulenceSeverity.SEVERE, aircraftType = "A319",
        )

        // positionIsLiveAircraft defaults to true.
        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = aircraft, routeEnd = end, altitudeFt = 36000.0,
        )
        assertEquals(true, items.first().distanceIsFromAircraft)
    }

    /** The raw METAR the destination-weather call speaks is decoded here. */
    @Test
    fun destinationWeatherMetarDecodes() {
        val metar = MetarParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        assertEquals(320, metar?.windDirection)
        assertEquals(12, metar?.windSpeed)
    }
}
