package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `IFATCCompanionTests/LiveATCDetectorTests.swift`.
 *
 * The companion must stand aside for a human controller — but only for the one on the
 * pilot's *own* radio. Connect publishes no map of which airport each controller works,
 * so these pin the asymmetry that makes the rule location-aware: session-wide staffing
 * signals mean "someone is controlling", while only the tuned COM1 frequency name means
 * "stand by".
 */
class LiveATCDetectorTest {

    private val detector = LiveATCDetector()

    @Test
    fun soloIsNotStaffed() {
        val s = detector.status(
            atcActive = false,
            controllerName = null,
            facilityCount = 0,
            online = false,
            serverName = null,
        )
        assertFalse(s.humanControllerActive)
        assertFalse(s.companionShouldStandBy)
        assertFalse(s.multiplayerOnline)
    }

    @Test
    fun facilityCountMeansHumanPresentButNotStandbyUntilTuned() {
        // A human is controlling somewhere in the session, but until the pilot tunes a
        // controller frequency the companion keeps working — we can't confirm that
        // controller is on the pilot's frequency / at the pilot's field.
        val s = detector.status(
            atcActive = null,
            controllerName = null,
            facilityCount = 2,
            online = true,
            serverName = "Expert",
        )
        assertTrue(s.humanControllerActive)
        assertFalse(s.companionShouldStandBy)
        assertTrue(s.multiplayerOnline)
        assertEquals("Expert", s.serverName)
    }

    @Test
    fun controllerNameIsPresenceOnly() {
        // The manifest exposes a controller username but no facility/frequency, so it's
        // a presence signal only — not enough to stand by on its own.
        val s = detector.status(
            atcActive = null,
            controllerName = "j_vonl",
            facilityCount = null,
            online = true,
            serverName = null,
        )
        assertTrue(s.humanControllerActive)
        assertEquals("j_vonl", s.controllerName)
        assertFalse(s.companionShouldStandBy)
    }

    @Test
    fun tunedToStaffedFrequencyStandsBy() {
        val s = detector.status(
            atcActive = null,
            controllerName = "j_vonl",
            facilityCount = 1,
            online = true,
            serverName = "Expert",
            tunedFrequencyName = "KSFO Tower",
        )
        assertTrue(s.companionShouldStandBy)
        assertEquals(ATCFacility.TOWER, s.tunedFacility)
    }

    @Test
    fun tunedFrequencyAloneImpliesHumanOnFrequency() {
        // Even when the standalone staffing flags don't resolve on this IF version,
        // a named controller frequency in the tuned COM means a human is on the air.
        val s = detector.status(
            atcActive = null,
            controllerName = null,
            facilityCount = null,
            online = null,
            serverName = null,
            tunedFrequencyName = "Ground",
        )
        assertTrue(s.companionShouldStandBy)
        assertTrue(s.humanControllerActive)
        assertEquals(ATCFacility.GROUND, s.tunedFacility)
    }

    @Test
    fun tunedToUnicomDoesNotStandBy() {
        val s = detector.status(
            atcActive = null,
            controllerName = "j_vonl",
            facilityCount = 1,
            online = true,
            serverName = "Expert",
            tunedFrequencyName = "Unicom",
        )
        assertFalse(s.companionShouldStandBy)
        assertNull(s.tunedFacility)
    }

    @Test
    fun tunedToAtisDoesNotStandBy() {
        // ATIS is an automated broadcast, not a human controller.
        val s = detector.status(
            atcActive = null,
            controllerName = null,
            facilityCount = 1,
            online = true,
            serverName = "Expert",
            tunedFrequencyName = "KBOS ATIS",
        )
        assertFalse(s.companionShouldStandBy)
    }

    @Test
    fun unicomControllerNameIsNotHuman() {
        val s = detector.status(
            atcActive = false,
            controllerName = "UNICOM",
            facilityCount = 0,
            online = true,
            serverName = "Casual",
        )
        assertFalse(s.humanControllerActive)
        assertTrue(s.multiplayerOnline)
    }
}
