package com.h3consultingpartners.ifatccompanion.core.chatter

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the background radio-chatter generator and the frequency-switch decision.
 *
 * Ported from `IFATCCompanionTests/ChatterTests.swift`. The voice-filter cases
 * (`testEnglishHumanVoicesAreEnglishAndNotNovelty`, `testChatterPoolIsLimitedOrFallsBack`)
 * exercise `VoiceCatalog`, which is the platform TTS layer and lives in `:app`; the
 * controller-voice and Live-Activity settings cases belong to the settings package.
 */
class ChatterTest {

    /**
     * Concatenate many exchanges for a facility so keyword assertions are robust to the
     * per-call randomness. [runwayIdents] seeds the generator's real-runway pool, and
     * [departures] / [arrivals] the ATIS-active departure/arrival pools.
     */
    private fun corpus(
        facility: ATCFacility,
        samples: Int = 80,
        runwayIdents: List<String> = emptyList(),
        departures: List<String> = emptyList(),
        arrivals: List<String> = emptyList(),
    ): String {
        val gen = ChatterScriptGenerator(
            runwayIdents = runwayIdents,
            departureRunwayIdents = departures,
            arrivalRunwayIdents = arrivals,
        )
        // Seeded so the frequency-bounding assertions are stable.
        val rng = Random(42)
        val text = StringBuilder()
        repeat(samples) {
            for (line in gen.exchange(facility, rng)) {
                text.append(" ").append(line.spokenText.lowercase())
            }
        }
        return text.toString()
    }

    // region Shape

    @Test
    fun everyFacilityProducesNonEmptyLines() {
        val gen = ChatterScriptGenerator()
        for (facility in ATCFacility.entries) {
            val rng = Random(7)
            repeat(20) {
                val lines = gen.exchange(facility, rng)
                assertTrue(lines.isNotEmpty(), "$facility produced no lines")
                for (line in lines) {
                    assertTrue(line.spokenText.trim().isNotEmpty(), "$facility produced an empty line")
                }
            }
        }
    }

    @Test
    fun callsignsUseRealAirlineNames() {
        val text = corpus(ATCFacility.CENTER)
        val anyAirline = listOf(
            "united", "american", "delta", "southwest", "jetblue", "alaska", "air canada", "fedex",
        ).any { text.contains(it) }
        assertTrue(anyAirline, "expected real airline radio names in the chatter")
        // The raw ICAO designators should never be spoken verbatim.
        assertFalse(text.contains(" ual "))
        assertFalse(text.contains(" dal "))
    }

    // endregion

    // region Frequency bounding

    @Test
    fun centerWorksEnrouteConceptsNotGroundOrTower() {
        val text = corpus(ATCFacility.CENTER)
        val enroute = listOf("chop", "climb", "descend", "contact", "arrival", "direct")
            .any { text.contains(it) }
        assertTrue(enroute, "Center chatter should be en-route work")
        assertFalse(text.contains("taxi to runway"), "Center must not issue taxi")
        assertFalse(text.contains("cleared for takeoff"), "Center must not clear takeoffs")
    }

    @Test
    fun groundWorksSurfaceNotTakeoff() {
        val text = corpus(ATCFacility.GROUND)
        val surface = listOf("taxi", "hold short", "runway").any { text.contains(it) }
        assertTrue(surface, "Ground chatter should be surface movement")
        assertFalse(text.contains("cleared for takeoff"), "Ground must not clear takeoffs")
        assertFalse(text.contains("descend via"), "Ground must not descend traffic")
    }

    @Test
    fun towerWorksRunwayOperations() {
        val text = corpus(ATCFacility.TOWER)
        val runwayOps = listOf("cleared for takeoff", "cleared to land", "line up and wait", "final")
            .any { text.contains(it) }
        assertTrue(runwayOps, "Tower chatter should be runway operations")
    }

    @Test
    fun approachWorksVectorsAndApproaches() {
        val text = corpus(ATCFacility.APPROACH)
        val approachWork = listOf("heading", "approach", "reduce speed", "tower").any { text.contains(it) }
        assertTrue(approachWork, "Approach chatter should be vectors/approaches")
    }

    @Test
    fun clearanceIssuesIFRClearances() {
        val text = corpus(ATCFacility.CLEARANCE)
        assertTrue(
            text.contains("cleared to") || text.contains("squawk"),
            "Clearance chatter should read IFR clearances",
        )
    }

    // endregion

    // region Real-runway grounding

    /**
     * With a real runway pool supplied (the field's OSM runway ends), the surface- and
     * runway-working positions must only ever name runways that exist at the field — never a
     * made-up one like "runway 18" at a field that has only 09/27.
     */
    @Test
    fun runwayReferencesUseTheProvidedFieldRunways() {
        val idents = listOf("09", "27")
        val allowedSpoken = idents.map { Phonetic.runway(it) }.toSet()   // "zero niner", "two seven"
        for (facility in listOf(ATCFacility.GROUND, ATCFacility.TOWER, ATCFacility.APPROACH)) {
            val text = corpus(facility, runwayIdents = idents)
            // The real runways do get referenced.
            assertTrue(
                allowedSpoken.any { text.contains("runway $it") },
                "$facility never referenced a runway from the field's pool",
            )
            // No runway the field doesn't have is ever named.
            for (n in 1..36) {
                val spoken = Phonetic.runway("%02d".format(n))
                if (spoken in allowedSpoken) continue
                assertFalse(
                    text.contains("runway $spoken"),
                    "$facility named runway $n, which isn't at the field",
                )
            }
        }
    }

    /**
     * A single-runway field (both ends in the pool): every Ground runway reference resolves
     * to that runway's ends, never anything else.
     */
    @Test
    fun groundNeverTaxisToARunwayNotAtTheField() {
        val text = corpus(ATCFacility.GROUND, runwayIdents = listOf("16L", "34R"))
        assertTrue(
            text.contains("runway one six left") || text.contains("runway three four right"),
            "expected the field's real runways in the ground chatter",
        )
        assertFalse(text.contains("runway one eight"), "named a runway not at the field")
        assertFalse(text.contains("runway three six"), "named a runway not at the field")
    }

    /**
     * With no pool supplied (no surface loaded yet / no flight plan) the generator keeps its
     * previous behaviour and still produces plausible runway operations.
     */
    @Test
    fun emptyRunwayPoolFallsBackToPlausibleRunways() {
        val text = corpus(ATCFacility.TOWER)
        assertTrue(text.contains("runway"), "tower chatter should still reference runways")
        assertTrue(
            listOf("cleared for takeoff", "cleared to land", "line up and wait").any { text.contains(it) },
            "tower chatter should still work runway operations without a pool",
        )
    }

    // endregion

    // region ATIS-active departure vs arrival runways

    /**
     * When the ATIS gives distinct departure and arrival runways, Tower clears takeoffs on
     * the departure runway and landings on the arrival runway — never the other way around.
     */
    @Test
    fun towerSplitsTakeoffAndLandingByAtisRunways() {
        val text = corpus(
            ATCFacility.TOWER,
            runwayIdents = listOf("24R", "25R"),
            departures = listOf("25R"),
            arrivals = listOf("24R"),
        )
        assertTrue(
            text.contains("cleared for takeoff runway two five right"),
            "takeoffs should use the ATIS departure runway",
        )
        assertFalse(
            text.contains("cleared for takeoff runway two four right"),
            "takeoffs must not use the arrival runway",
        )
        assertTrue(
            text.contains("cleared to land runway two four right"),
            "landings should use the ATIS arrival runway",
        )
        assertFalse(
            text.contains("cleared to land runway two five right"),
            "landings must not use the departure runway",
        )
    }

    /** Ground taxis departing traffic to the ATIS departure runway, never the arrival-only one. */
    @Test
    fun groundUsesTheAtisDepartureRunway() {
        val text = corpus(
            ATCFacility.GROUND,
            runwayIdents = listOf("24R", "25R"),
            departures = listOf("25R"),
            arrivals = listOf("24R"),
        )
        assertTrue(text.contains("runway two five right"), "ground should taxi to the departure runway")
        assertFalse(
            text.contains("runway two four right"),
            "ground must not send departing traffic to the arrival-only runway",
        )
    }

    /** Approach clears traffic for the ATIS arrival runway, never the departure-only one. */
    @Test
    fun approachUsesTheAtisArrivalRunway() {
        val text = corpus(
            ATCFacility.APPROACH,
            runwayIdents = listOf("24R", "25R"),
            departures = listOf("25R"),
            arrivals = listOf("24R"),
        )
        assertTrue(text.contains("runway two four right"), "approach should use the arrival runway")
        assertFalse(
            text.contains("runway two five right"),
            "approach must not clear an approach to the departure-only runway",
        )
    }

    // endregion

    // region Transmission classification

    @Test
    fun isControllerExchangeExcludesATISAndSystem() {
        val atc = ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = ATCFacility.TOWER,
            displayText = "Cleared to land.",
        )
        val pilot = ATCTransmission.create(
            sender = ATCTransmission.Sender.PILOT,
            facility = ATCFacility.TOWER,
            displayText = "Cleared to land.",
        )
        val system = ATCTransmission.create(
            sender = ATCTransmission.Sender.SYSTEM,
            facility = ATCFacility.RAMP,
            displayText = "Flight complete.",
        )
        val atis = ATCTransmission.create(
            sender = ATCTransmission.Sender.SYSTEM,
            facility = ATCFacility.CENTER,
            displayText = "Info Alpha.",
            isATIS = true,
        )
        assertTrue(atc.isControllerExchange)
        assertTrue(pilot.isControllerExchange)
        assertFalse(system.isControllerExchange)
        assertFalse(atis.isControllerExchange)
    }

    // endregion

    // region Mid-exchange frequency switching

    /**
     * Tuning to a different facility while an exchange is on the air abandons that exchange
     * (and any read-back tied to it) so chatter for the new frequency can start.
     */
    @Test
    fun midExchangeSwitchToADifferentFacilityAbandonsExchange() {
        assertTrue(
            AmbientChatterService.shouldAbandonExchange(ATCFacility.TOWER, ATCFacility.GROUND),
        )
        assertTrue(
            AmbientChatterService.shouldAbandonExchange(ATCFacility.CENTER, ATCFacility.APPROACH),
        )
        assertTrue(
            AmbientChatterService.shouldAbandonExchange(ATCFacility.GROUND, ATCFacility.RAMP),
        )
    }

    /** A "change" that stays on the same facility must not interrupt the exchange in progress. */
    @Test
    fun switchToTheSameFacilityDoesNotInterrupt() {
        for (facility in ATCFacility.entries) {
            assertFalse(
                AmbientChatterService.shouldAbandonExchange(facility, facility),
                "$facility → $facility should not interrupt",
            )
        }
    }

    /**
     * A frequency switch in the gap between exchanges (nothing on the air) needs no
     * interruption — the next cycle already reads the newly-tuned facility.
     */
    @Test
    fun switchInTheGapBetweenExchangesDoesNotInterrupt() {
        assertFalse(AmbientChatterService.shouldAbandonExchange(null, ATCFacility.TOWER))
        assertFalse(AmbientChatterService.shouldAbandonExchange(null, ATCFacility.GROUND))
    }

    /** Calling [AmbientChatterService.facilityDidChange] while the chatter isn't running is a safe no-op. */
    @Test
    fun facilityChangeIsANoOpWhenNotRunning() = runTest {
        val radio = RecordingRadio()
        val service = AmbientChatterService(radio, backgroundScope, Random(1))
        service.bindContext(facility = { ATCFacility.TOWER })
        service.facilityDidChange(ATCFacility.GROUND)   // not running — must not start audio
        assertFalse(service.isRunning.value)
        assertEquals(0, radio.startCount)
        assertFalse(radio.stoppedSpeech)
    }

    // endregion

    /** A [ChatterRadio] that records calls instead of making noise. */
    private class RecordingRadio : ChatterRadio {
        var startCount = 0
        var stoppedSpeech = false
        override var isRunning = false
            private set

        override fun activateSession() = Unit

        override fun start() {
            startCount += 1
            isRunning = true
        }

        override fun stop() {
            isRunning = false
        }

        override fun setChatterLevel(level: Double) = Unit
        override fun setDucked(ducked: Boolean) = Unit
        override fun setTransmitting(transmitting: Boolean) = Unit
        override suspend fun speak(line: ChatterLine, facility: ATCFacility) = Unit

        override fun stopSpeech() {
            stoppedSpeech = true
        }

        override fun playKeyClick() = Unit
        override fun playSquelchTail() = Unit
    }
}
