package com.h3consultingpartners.ifatccompanion.core.mock

import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.PilotAction
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalkthroughDump {
    @Test
    fun dump() = runTest {
        val clock = MutableClock(1_700_000_000_000L)
        val mock = MockSimulatorFeed(scope = this, clock = clock)
        val settings = AppSettings(
            voiceEnabled = false, mockMode = true,
            initialClimbAltitudeFt = 5000, traconCeilingFL = 180,
        )
        val m = FlightSessionCoordinator(scope = this, clock = clock, settingsProvider = { settings })
        m.ingestFlightPlan(
            FlightPlan(
                airline = "United", flightNumber = "598", departure = "KIAH",
                destination = "KMSP", cruiseAltitude = 37000, star = "KKILR",
                approach = "ILS 30L", waypoints = mock.route.waypoints,
            ),
        )
        fun feed(p: FlightPhase, n: Int = 1) = repeat(n) { m.ingestAircraftState(mock.stateFor(p)) }
        m.performPilotAction(PilotAction.CLEARANCE); m.readBack()
        m.performPilotAction(PilotAction.PUSHBACK); m.readBack()
        m.performPilotAction(PilotAction.ENGINE_START); m.readBack()
        m.performPilotAction(PilotAction.TAXI); m.readBack()
        m.performPilotAction(PilotAction.TAXI); m.readBack()
        m.performPilotAction(PilotAction.READY); m.readBack()
        feed(FlightPhase.TAKEOFF); m.readBack()
        feed(FlightPhase.INITIAL_CLIMB); m.readBack()
        feed(FlightPhase.CLIMB); m.readBack()
        feed(FlightPhase.CRUISE)
        feed(FlightPhase.DESCENT); m.readBack()
        feed(FlightPhase.APPROACH, 2); m.readBack()
        feed(FlightPhase.LANDING, 2); m.readBack()
        feed(FlightPhase.TAXI_IN); m.readBack()
        feed(FlightPhase.PARKED)
        m.state.value.transcript.forEach { tx ->
            val who = if (tx.sender.name == "PILOT") "PILOT" else tx.sender.name
            println("WALK|$who|${tx.facility.title}|${tx.displayText}")
        }
    }
}
