package com.h3consultingpartners.ifatccompanion.core.weather.deviation

import com.h3consultingpartners.ifatccompanion.core.weather.METAR
import com.h3consultingpartners.ifatccompanion.core.weather.RideReportItem
import com.h3consultingpartners.ifatccompanion.core.weather.SIGMET
import com.h3consultingpartners.ifatccompanion.core.weather.TurbulenceSeverity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ported from `IFATCCompanionTests/TurbulenceModelTests.swift`. */
class TurbulenceModelTest {

    private val model = TurbulenceModel()

    private fun item(severity: TurbulenceSeverity, distance: Double, age: Double? = null): RideReportItem =
        RideReportItem(
            severity = severity,
            altitudeBand = null,
            distanceAheadNM = distance,
            bearing = null,
            nearFix = null,
            sourceRaw = "test",
            ageMinutes = age,
        )

    @Test
    fun noSignalsIsSmooth() {
        val a = model.assess(items = emptyList(), sigmets = emptyList(), metar = null, altitudeFt = 35000.0)
        assertEquals(TurbulenceSeverity.SMOOTH, a.severity)
        assertTrue(abs(a.index - 0.0) < 0.0001)
    }

    @Test
    fun closeModeratePirepRaisesIndex() {
        val a = model.assess(items = listOf(item(TurbulenceSeverity.MODERATE, distance = 0.0)), altitudeFt = 35000.0)
        assertTrue(a.index > 0.5)
        assertTrue(a.contributors.contains("pilot reports"))
    }

    @Test
    fun distantReportIsWeightedDown() {
        val near = model.weightedScore(item(TurbulenceSeverity.MODERATE, distance = 0.0))
        val far = model.weightedScore(item(TurbulenceSeverity.MODERATE, distance = 240.0))
        assertTrue(near > far)
    }

    @Test
    fun oldReportIsWeightedDown() {
        val fresh = model.weightedScore(item(TurbulenceSeverity.SEVERE, distance = 10.0, age = 0.0))
        val stale = model.weightedScore(item(TurbulenceSeverity.SEVERE, distance = 10.0, age = 180.0))
        assertTrue(fresh > stale)
    }

    @Test
    fun convectiveSigmetDominates() {
        val sigmet = SIGMET(raw = "CONVECTIVE SIGMET", hazard = "CONVECTIVE", severity = null, area = emptyList())
        val a = model.assess(
            items = emptyList(),
            sigmets = listOf(sigmet),
            metar = null,
            altitudeFt = 35000.0,
        )
        assertTrue(a.severity.rawValue >= TurbulenceSeverity.MODERATE.rawValue)
        assertTrue(a.contributors.contains("convective SIGMET"))
    }

    @Test
    fun lowLevelWindShearAddsAtLowAltitude() {
        val metar = METAR(icao = "KMSP", raw = "")
        metar.windSpeed = 18
        metar.windGust = 30
        val low = model.assess(items = emptyList(), metar = metar, altitudeFt = 3000.0)
        val high = model.assess(items = emptyList(), metar = metar, altitudeFt = 35000.0)
        assertTrue(low.index > high.index)
        assertTrue(low.contributors.contains("surface wind shear"))
    }

    @Test
    fun severityThresholds() {
        assertEquals(TurbulenceSeverity.SMOOTH, model.severity(0.0))
        assertEquals(TurbulenceSeverity.LIGHT_CHOP, model.severity(0.25))
        assertEquals(TurbulenceSeverity.LIGHT, model.severity(0.45))
        assertEquals(TurbulenceSeverity.MODERATE, model.severity(0.7))
        assertEquals(TurbulenceSeverity.SEVERE, model.severity(0.95))
    }
}
