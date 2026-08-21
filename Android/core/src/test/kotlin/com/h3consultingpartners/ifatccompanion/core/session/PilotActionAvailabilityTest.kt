package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The response buttons are what a pilot actually touches, and the rule behind them is
 * easy to get subtly wrong — offering the push under Clearance, or "ready for departure"
 * on Ground. Lifting it out of the view model is what makes it testable at all.
 */
class PilotActionAvailabilityTest {

    private fun inputs(
        workingFacility: ATCFacility,
        atcState: ATCState = ATCState.CONNECTED_IDLE,
        phase: FlightPhase = FlightPhase.PREFLIGHT,
        aircraftState: AircraftState = AircraftState.empty,
        hasDeparted: Boolean = false,
        companionStandby: Boolean = false,
        monitoringTower: Boolean = false,
        pushbackOnGround: Boolean = false,
        hasSmootherAltitudeSuggestion: Boolean = false,
    ) = PilotActionAvailability.Inputs(
        workingFacility = workingFacility,
        atcState = atcState,
        phase = phase,
        aircraftState = aircraftState,
        isPreDeparture = !hasDeparted,
        hasDeparted = hasDeparted,
        companionStandby = companionStandby,
        monitoringTower = monitoringTower,
        pushbackOnGround = pushbackOnGround,
        hasSmootherAltitudeSuggestion = hasSmootherAltitudeSuggestion,
    )

    @Test
    fun aStaffedControllerSilencesEveryButton() {
        // The companion must never talk over a real controller, so it offers the pilot
        // nothing to say while standing by.
        val actions = PilotActionAvailability.availableActions(
            inputs(ATCFacility.CENTER, hasDeparted = true, companionStandby = true),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun clearanceOffersOnlyTheClearanceAndNeverThePush() {
        // The clearance ends by telling the pilot which frequency to tune for the push,
        // so the Pushback button belongs to that frequency — not to Clearance.
        val actions = PilotActionAvailability.availableActions(inputs(ATCFacility.CLEARANCE))
        assertEquals(setOf(PilotAction.CLEARANCE), actions)
        assertFalse(PilotAction.PUSHBACK in actions)
    }

    @Test
    fun clearanceOffersNothingOnceIssued() {
        val actions = PilotActionAvailability.availableActions(
            inputs(ATCFacility.CLEARANCE, atcState = ATCState.CLEARANCE),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun rampOffersThePushBeforeItAndStartAndTaxiAfter() {
        assertEquals(
            setOf(PilotAction.PUSHBACK),
            PilotActionAvailability.availableActions(
                inputs(ATCFacility.RAMP, atcState = ATCState.CLEARANCE),
            ),
        )
        assertEquals(
            setOf(PilotAction.ENGINE_START, PilotAction.TAXI),
            PilotActionAvailability.availableActions(
                inputs(ATCFacility.RAMP, atcState = ATCState.PUSHBACK),
            ),
        )
    }

    @Test
    fun groundOffersOnlyTheTaxi() {
        // "Ready for departure" addresses Tower while holding short, so it is never a
        // Ground button.
        val actions = PilotActionAvailability.availableActions(
            inputs(ATCFacility.GROUND, atcState = ATCState.CLEARANCE),
        )
        assertEquals(setOf(PilotAction.TAXI), actions)
    }

    @Test
    fun groundAlsoOffersThePushAtAFieldWithNoRampLayer() {
        val actions = PilotActionAvailability.availableActions(
            inputs(ATCFacility.GROUND, atcState = ATCState.CLEARANCE, pushbackOnGround = true),
        )
        assertEquals(setOf(PilotAction.PUSHBACK, PilotAction.TAXI), actions)
    }

    @Test
    fun towerOffersReadyAndTakeoffAndAddsCheckInWhenMonitoring() {
        assertEquals(
            setOf(PilotAction.READY, PilotAction.TAKEOFF),
            PilotActionAvailability.availableActions(inputs(ATCFacility.TOWER)),
        )
        assertEquals(
            setOf(PilotAction.READY, PilotAction.CHECK_IN, PilotAction.TAKEOFF),
            PilotActionAvailability.availableActions(
                inputs(ATCFacility.TOWER, monitoringTower = true),
            ),
        )
    }

    @Test
    fun centerSurfacesTheSmootherAltitudeOnlyWhileOneIsSuggested() {
        val without = PilotActionAvailability.availableActions(
            inputs(ATCFacility.CENTER, atcState = ATCState.CRUISE, hasDeparted = true),
        )
        assertFalse(PilotAction.ACCEPT_SMOOTHER_ALTITUDE in without)

        val with = PilotActionAvailability.availableActions(
            inputs(
                ATCFacility.CENTER,
                atcState = ATCState.CRUISE,
                hasDeparted = true,
                hasSmootherAltitudeSuggestion = true,
            ),
        )
        assertTrue(PilotAction.ACCEPT_SMOOTHER_ALTITUDE in with)
    }

    @Test
    fun goAroundIsOfferedOnlyAirborneInboundToLandOnTower() {
        val airborne = AircraftState(onGround = false)
        for (state in listOf(ATCState.FINAL, ATCState.LANDING)) {
            assertTrue(
                PilotAction.GO_AROUND in PilotActionAvailability.availableActions(
                    inputs(
                        ATCFacility.TOWER,
                        atcState = state,
                        aircraftState = airborne,
                        hasDeparted = true,
                    ),
                ),
                "go around must be offered at $state",
            )
        }
        // On the ground it is hidden, even on Tower.
        assertFalse(
            PilotAction.GO_AROUND in PilotActionAvailability.availableActions(
                inputs(
                    ATCFacility.TOWER,
                    atcState = ATCState.LANDING,
                    aircraftState = AircraftState(onGround = true),
                    hasDeparted = true,
                ),
            ),
        )
        // And it never appears during the departure.
        assertFalse(
            PilotAction.GO_AROUND in PilotActionAvailability.availableActions(
                inputs(ATCFacility.TOWER, aircraftState = airborne, hasDeparted = false),
            ),
        )
    }

    @Test
    fun theArrivalRampOffersOnlyTheGateCall() {
        val actions = PilotActionAvailability.availableActions(
            inputs(
                ATCFacility.RAMP,
                atcState = ATCState.GROUND_ARRIVAL,
                phase = FlightPhase.TAXI_IN,
                hasDeparted = true,
            ),
        )
        assertEquals(setOf(PilotAction.TO_GATE), actions)
    }

    @Test
    fun aFinishedFlightOffersNothing() {
        val actions = PilotActionAvailability.availableActions(
            inputs(
                ATCFacility.GROUND,
                atcState = ATCState.PARKED,
                phase = FlightPhase.PARKED,
                hasDeparted = true,
            ),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun theRampButtonIsLiveForThePushAndTheGateButNeverOnceParked() {
        assertTrue(
            PilotActionAvailability.canContactRamp(
                ATCState.CONNECTED_IDLE, FlightPhase.PREFLIGHT, hasDeparted = false,
            ),
        )
        assertTrue(
            PilotActionAvailability.canContactRamp(
                ATCState.GROUND_ARRIVAL, FlightPhase.TAXI_IN, hasDeparted = true,
            ),
        )
        assertFalse(
            PilotActionAvailability.canContactRamp(
                ATCState.PARKED, FlightPhase.PARKED, hasDeparted = true,
            ),
        )
    }
}
