package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherDeviationController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fix that moved further than the aircraft could have flown.
 *
 * A socket that froze and snapped forward — Infinite Flight paused, the phone asleep, the
 * process doze'd — reports a position the aircraft never flew *to*, along a ground track it
 * never flew *across*. Anything derived from crossing that track is fiction: a hand-off to
 * a sector already left behind, a reroute around weather already passed. iOS stands those
 * decisions down for the tick; Android replayed them.
 */
class TelemetryDiscontinuityTest {

    /** Collects the messages, so a test can assert on what the app noticed. */
    private class RecordingSink(private val into: MutableList<String>) : DiagnosticsSink {
        override fun log(category: DiagnosticCategory, level: DiagnosticLevel, message: String) {
            into += message
        }
    }

    private val clock = MutableClock(1_700_000_000_000L)

    private val start = Coordinate(40.0, -95.0)

    private val plan = FlightPlan.empty.copy(
        departure = "KIAH",
        destination = "KMSP",
        airline = "United",
        flightNumber = "598",
        callsign = "United 598",
        cruiseAltitude = 35_000,
    )

    private fun at(coordinate: Coordinate) = AircraftState(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        altitudeMSL = 35_000.0,
        altitudeAGL = 35_000.0,
        groundSpeed = 450.0,
        verticalSpeed = 0.0,
        heading = 0.0,
        onGround = false,
    )

    private val deviation = WeatherDeviationController(
        clock = clock,
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
    )

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = clock,
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
        weatherDeviation = deviation,
    )

    /** Feed a fix one second later at [alongNM] up the track. */
    private fun tick(c: FlightSessionCoordinator, alongNM: Double, afterSeconds: Long = 1L) {
        clock.advance(afterSeconds * 1000L)
        c.ingestAircraftState(at(Geo.destination(start, 0.0, alongNM)))
    }

    private fun jumpWarnings(c: FlightSessionCoordinator, log: List<String>) =
        log.filter { it.contains("Telemetry jumped") }

    @Test
    fun `ordinary motion at cruise is never called a jump`() = runTest {
        // 450 kt is 0.125 NM per second. A hundred ticks of real flying.
        val records = mutableListOf<String>()
        val coordinator = FlightSessionCoordinator(
            scope = this,
            clock = clock,
            settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
            diagnostics = RecordingSink(records),
        )
        coordinator.ingestFlightPlan(plan)
        tick(coordinator, 0.0)
        for (i in 1..100) tick(coordinator, i * 0.125)

        assertTrue(
            jumpWarnings(coordinator, records).isEmpty(),
            "normal flight was flagged: ${jumpWarnings(coordinator, records)}",
        )
    }

    @Test
    fun `a socket that froze and snapped forward is flagged`() = runTest {
        val records = mutableListOf<String>()
        val coordinator = FlightSessionCoordinator(
            scope = this,
            clock = clock,
            settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
            diagnostics = RecordingSink(records),
        )
        coordinator.ingestFlightPlan(plan)
        tick(coordinator, 0.0)
        // One second later, sixty miles up the track: eight minutes of flying in one tick.
        tick(coordinator, 60.0)

        assertTrue(
            jumpWarnings(coordinator, records).isNotEmpty(),
            "a 60 NM step in one second went unflagged",
        )
    }

    @Test
    fun `a long gap with proportionate movement is not a jump`() = runTest {
        // The app was backgrounded for five minutes and the aircraft flew 37 NM. That is
        // what 450 kt does in five minutes — the poll simply was not reporting it.
        val records = mutableListOf<String>()
        val coordinator = FlightSessionCoordinator(
            scope = this,
            clock = clock,
            settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
            diagnostics = RecordingSink(records),
        )
        coordinator.ingestFlightPlan(plan)
        tick(coordinator, 0.0)
        tick(coordinator, 37.0, afterSeconds = 300L)

        assertTrue(
            jumpWarnings(coordinator, records).isEmpty(),
            "five minutes of ordinary flight was flagged: ${jumpWarnings(coordinator, records)}",
        )
    }

    @Test
    fun `a stationary aircraft that teleports is flagged`() = runTest {
        // Ground speed zero, so nothing justifies any movement beyond the slack.
        val records = mutableListOf<String>()
        val coordinator = FlightSessionCoordinator(
            scope = this,
            clock = clock,
            settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
            diagnostics = RecordingSink(records),
        )
        coordinator.ingestFlightPlan(plan)
        clock.advance(1_000L)
        coordinator.ingestAircraftState(at(start).copy(groundSpeed = 0.0))
        clock.advance(1_000L)
        coordinator.ingestAircraftState(
            at(Geo.destination(start, 90.0, 25.0)).copy(groundSpeed = 0.0),
        )

        assertTrue(
            jumpWarnings(coordinator, records).isNotEmpty(),
            "a parked aircraft moved 25 NM in a second and nothing noticed",
        )
    }
}
