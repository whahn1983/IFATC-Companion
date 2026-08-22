package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.phraseology.CallsignDigitStyle
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.weather.MetarParser
import com.h3consultingpartners.ifatccompanion.core.weather.PIREP
import com.h3consultingpartners.ifatccompanion.core.weather.RideReportItem
import com.h3consultingpartners.ifatccompanion.core.weather.SmootherAltitude
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherRouteAnalyzer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `RideReportEngine` cases from `IFATCCompanionTests/WeatherTests.swift`, which
 * `WeatherTest.kt` left to this package (it owns the engine). The analyzer-only cases in
 * that file stay there; the two combined ones are repeated here for their phrase half.
 */
class RideReportEngineTest {

    private fun engine(): PhraseologyEngine = PhraseologyEngine(digitStyle = CallsignDigitStyle.INDIVIDUAL)

    @Test
    fun rideReportEngineNoReports() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val tx = ride.rideReport(items = emptyList(), callsign = cs)
        assertTrue(tx.displayText.contains("no significant ride reports"))
    }

    @Test
    fun rideReportRelaysPIREPAtAltitudeAndNamesSmootherLevel() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val lead = RideReportItem(
            severity = TurbulenceSeverity.MODERATE, altitudeBand = 33000..37000, distanceAheadNM = 40.0,
            bearing = 0.0, nearFix = "DSM", sourceRaw = "", ageMinutes = 15.0,
            reportedAltitudeFt = 35000, aircraftType = "B738",
        )
        val assessment = RideAssessment(
            index = 0.6, severity = TurbulenceSeverity.MODERATE, contributors = listOf("pilot reports"),
        )
        val smoother = SmootherAltitude(
            altitudeFt = 39000, severity = TurbulenceSeverity.SMOOTH, aircraftType = "A320", higher = true,
        )
        val tx = ride.rideReport(
            assessment = assessment, items = listOf(lead),
            referenceAltitudeFt = 35000, smoother = smoother, callsign = cs,
        )
        assertTrue(tx.displayText.contains("moderate turbulence"))
        assertTrue(tx.displayText.contains("FL350"), "relays the report's own altitude")
        assertTrue(tx.displayText.contains("near DSM"))
        assertTrue(tx.displayText.contains("FL390"), "names the specific smoother level")
        assertTrue(tx.displayText.lowercase().contains("climb"))
    }

    @Test
    fun rideReportFallsBackToGenericOfferWithoutSmootherData() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val lead = RideReportItem(
            severity = TurbulenceSeverity.MODERATE, altitudeBand = null, distanceAheadNM = 25.0,
            bearing = 0.0, nearFix = null, sourceRaw = "", reportedAltitudeFt = 35000,
        )
        val assessment = RideAssessment(
            index = 0.6, severity = TurbulenceSeverity.MODERATE, contributors = emptyList(),
        )
        val tx = ride.rideReport(
            assessment = assessment, items = listOf(lead),
            referenceAltitudeFt = 35000, smoother = null, callsign = cs,
        )
        assertTrue(tx.displayText.contains("higher or lower"), "generic offer when no level is supported")
    }

    /**
     * Without a live aircraft fix the analysis falls back to the departure airport, so the
     * along-track distance is origin-relative. It must be flagged and NOT presented as
     * "… miles ahead" (the distance-from-origin bug); instead the report falls back to a
     * route-relative phrase ("along your route near LIT").
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
            turbulence = TurbulenceSeverity.SEVERE, icing = null, timeMillis = null, aircraftType = "A319",
        )

        // A route fix at the PIREP's own position labels it "near LIT"; a far fix does not.
        val fixes = listOf(
            WeatherRouteAnalyzer.NamedFix(name = "LIT", coordinate = pirepCoord),
            WeatherRouteAnalyzer.NamedFix(name = "FAR", coordinate = departure),
        )
        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = departure, routeEnd = end,
            altitudeFt = 36000.0, routeFixes = fixes, positionIsLiveAircraft = false,
        )
        assertTrue(items.size == 1)
        assertFalse(items.first().distanceIsFromAircraft)
        // The distance is still computed (the turbulence model weights by it) — only the
        // presentation is suppressed.
        assertTrue((items.first().distanceAheadNM ?: 0.0) > 0)

        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "1678", fallback = "")
        val assessment = RideAssessment(
            index = 0.9, severity = TurbulenceSeverity.SEVERE, contributors = listOf("pilot reports"),
        )
        val tx = ride.rideReport(
            assessment = assessment, items = items, referenceAltitudeFt = 36000,
            smoother = null, callsign = cs,
        )
        assertTrue(tx.displayText.contains("severe turbulence"))
        assertFalse(
            tx.displayText.contains("miles ahead"),
            "no origin-relative distance is presented without a live aircraft fix",
        )
        assertFalse(tx.spokenText.contains("miles ahead"))
        assertTrue(
            tx.displayText.contains("along your route near LIT"),
            "falls back to a route-relative phrase",
        )
        assertTrue(tx.spokenText.contains("along your route"))
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
            turbulence = TurbulenceSeverity.SEVERE, icing = null, timeMillis = null, aircraftType = "A319",
        )

        // positionIsLiveAircraft defaults to true.
        val items = analyzer.relevantReports(
            pireps = listOf(pirep), position = aircraft, routeEnd = end, altitudeFt = 36000.0,
        )
        assertTrue(items.first().distanceIsFromAircraft)

        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "1678", fallback = "")
        val assessment = RideAssessment(
            index = 0.9, severity = TurbulenceSeverity.SEVERE, contributors = emptyList(),
        )
        val tx = ride.rideReport(
            assessment = assessment, items = items, referenceAltitudeFt = 36000,
            smoother = null, callsign = cs,
        )
        assertTrue(
            tx.displayText.contains("miles ahead"),
            "a live aircraft fix yields an aircraft-relative distance",
        )
    }

    /**
     * A SIGMET (or the wind-shear proxy) can raise the composite ride index above every
     * PIREP. When the report still references a specific PIREP, ATC relays *that pilot
     * report's* severity — it must not override it with the SIGMET-driven composite severity.
     */
    @Test
    fun rideReportRelaysPIREPSeverityNotSIGMETElevatedSeverity() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        // The only relevant PIREP is light chop; a route SIGMET pushed the composite to severe.
        val lead = RideReportItem(
            severity = TurbulenceSeverity.LIGHT_CHOP, altitudeBand = null, distanceAheadNM = 40.0,
            bearing = 0.0, nearFix = null, sourceRaw = "", ageMinutes = 10.0,
            reportedAltitudeFt = 35000, aircraftType = "B738",
        )
        val assessment = RideAssessment(
            index = 0.85, severity = TurbulenceSeverity.SEVERE,
            contributors = listOf("pilot reports", "SIGMET convective turbulence"),
        )
        val tx = ride.rideReport(
            assessment = assessment, items = listOf(lead),
            referenceAltitudeFt = 35000, smoother = null, callsign = cs,
        )
        assertTrue(tx.displayText.contains("light chop"), "relays the referenced PIREP's own severity")
        assertFalse(tx.displayText.contains("severe"), "does not override with the SIGMET-driven severity")
        assertFalse(tx.spokenText.contains("severe"))
    }

    /**
     * With no PIREP to reference, the advisory rests on SIGMET data alone, so the composite
     * (SIGMET-driven) severity is the one ATC speaks.
     */
    @Test
    fun rideReportUsesSIGMETSeverityWhenNoPIREP() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val assessment = RideAssessment(
            index = 0.85, severity = TurbulenceSeverity.SEVERE,
            contributors = listOf("SIGMET convective turbulence"),
        )
        val tx = ride.rideReport(
            assessment = assessment, items = emptyList(),
            referenceAltitudeFt = 35000, smoother = null, callsign = cs,
        )
        assertTrue(
            tx.displayText.contains("severe turbulence"),
            "SIGMET severity is used when there is no relevant PIREP",
        )
    }

    @Test
    fun destinationWeatherSpoken() {
        val engine = engine()
        val ride = RideReportEngine(engine)
        val cs = engine.callsign(airline = "United", flightNumber = "598", fallback = "")
        val metar = assertNotNull(MetarParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012"))
        val tx = ride.destinationWeather(metar = metar, callsign = cs, icaoCode = "KMSP")
        assertTrue(tx.spokenText.contains("wind three two zero at one two"))
        assertTrue(tx.displayText.contains("Minneapolis"))
    }
}
