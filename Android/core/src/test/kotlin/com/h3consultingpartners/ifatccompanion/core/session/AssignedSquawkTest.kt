package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The beacon code in the IFR clearance.
 *
 * Every Android clearance used to assign a hard-coded 4271, so two aircraft in the same
 * session squawked the same code and the number never matched the flight. These pin the
 * three things that make a generated code usable: it is legal, it is not a code that means
 * something else, and it belongs to the flight.
 */
class AssignedSquawkTest {

    private fun coordinator(scope: TestScope) = FlightSessionCoordinator(
        scope = scope,
        clock = MutableClock(0),
        settingsProvider = { AppSettings(mockMode = true, voiceEnabled = false) },
    )

    private fun squawkFor(scope: TestScope, flightNumber: String): String {
        val coordinator = coordinator(scope)
        coordinator.ingestFlightPlan(
            FlightPlan.empty.copy(
                departure = "KIAH",
                destination = "KMSP",
                airline = "United",
                flightNumber = flightNumber,
                callsign = "United $flightNumber",
                cruiseAltitude = 37_000,
            ),
        )
        coordinator.performPilotAction(PilotAction.CLEARANCE)
        val clearance = coordinator.state.value.transcript
            .last { it.sender == ATCTransmission.Sender.ATC }
            .displayText
        return Regex("squawk (\\d{4})").find(clearance)?.groupValues?.get(1)
            ?: error("no squawk in the clearance: $clearance")
    }

    @Test
    fun `the assigned code is always a legal octal squawk`() = runTest {
        for (number in listOf("1", "598", "1234", "8888", "9999", "40000")) {
            val squawk = squawkFor(this, number)
            assertEquals(4, squawk.length, "flight $number got $squawk")
            assertTrue(
                squawk.all { it in '0'..'7' },
                "flight $number was assigned $squawk, which has a digit above 7",
            )
        }
    }

    @Test
    fun `no flight is ever assigned a code that means something else`() = runTest {
        // 7500, 7600 and 7700 are the emergencies; 1200 is VFR conspicuity. A controller
        // handing a pilot one of these has told them something entirely different.
        for (number in 1..600) {
            val squawk = squawkFor(this, number.toString())
            assertTrue(
                squawk !in FlightSessionCoordinator.RESERVED_SQUAWKS,
                "flight $number was assigned the reserved code $squawk",
            )
        }
    }

    @Test
    fun `the code follows the flight number`() = runTest {
        assertTrue(
            squawkFor(this, "598") != squawkFor(this, "599"),
            "two different flights were assigned the same code",
        )
        assertEquals(
            squawkFor(this, "598"),
            squawkFor(this, "598"),
            "the same flight was assigned a different code on a second clearance",
        )
    }

    @Test
    fun `a plan with no numeric flight number still gets a code`() = runTest {
        val squawk = squawkFor(this, "")
        assertEquals(4, squawk.length)
        assertTrue(squawk.all { it in '0'..'7' }, squawk)
    }
}
