package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.HttpResponse
import com.h3consultingpartners.ifatccompanion.core.net.HttpResult
import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryFileStore
import com.h3consultingpartners.ifatccompanion.core.platform.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface controller decides what gets loaded and what the pilot is told when it
 * can't be. The rule that matters most is that a missing field is never an error the
 * pilot has to act on — the taxi map simply doesn't draw.
 */
class SurfaceSessionControllerTest {

    private class FakeHttp(var body: String, var fail: Boolean = false) : HttpFetching {
        val requests = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSeconds: Long): HttpResult {
            requests += url
            return respond()
        }
        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): HttpResult {
            requests += url
            return respond()
        }
        private fun respond(): HttpResult =
            if (fail) {
                HttpResult.Failure("Overpass is down")
            } else {
                HttpResult.Success(HttpResponse(200, body.toByteArray(Charsets.UTF_8), emptyMap()))
            }
    }

    /** A minimal but well-formed Overpass answer: one taxiway and one stand. */
    private val overpassBody = """
        {"elements":[
          {"type":"node","id":1,"lat":29.9902,"lon":-95.3368},
          {"type":"node","id":2,"lat":29.9912,"lon":-95.3368},
          {"type":"way","id":10,"nodes":[1,2],"tags":{"aeroway":"taxiway","ref":"A"}},
          {"type":"node","id":3,"lat":29.9905,"lon":-95.3372,
           "tags":{"aeroway":"parking_position","ref":"C24"}}
        ]}
    """.trimIndent()

    private fun controller(http: FakeHttp, clock: MutableClock = MutableClock(0)) =
        SurfaceSessionController(
            provider = AirportSurfaceProvider(http, AirportSurfaceCache(InMemoryFileStore(clock)), clock = clock),
            clock = clock,
        )

    private val plan = FlightPlan(departure = "KIAH", destination = "KMSP")

    @Test
    fun bothEndsOfTheFlightAreLoaded() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(plan)

        assertEquals("KIAH", controller.state.value.departure?.icao)
        assertEquals("KMSP", controller.state.value.arrival?.icao)
        assertEquals(2, http.requests.size)
        assertFalse(controller.state.value.loading)
    }

    /**
     * An Overpass outage is not something the pilot can act on: it is recorded and
     * reported, never thrown, and the flight carries on with generic taxi phrasing.
     */
    @Test
    fun anOverpassOutageIsReportedNotThrown() = runTest {
        val http = FakeHttp(overpassBody, fail = true)
        val controller = controller(http)
        controller.refresh(plan)

        assertNull(controller.state.value.departure)
        assertNotNull(controller.state.value.lastError)
        assertFalse(controller.state.value.loading)
    }

    /** A field with no known reference position is never requested at all. */
    @Test
    fun anUnknownFieldIsNotRequested() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(FlightPlan(departure = "ZZZZ", destination = "YYYY"))
        assertTrue(http.requests.isEmpty())
    }

    /** A blank or too-short identifier is not an airport. */
    @Test
    fun ablankIdentifierIsNotRequested() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(FlightPlan(departure = "", destination = "KM"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun theAssignedStandResolvesFromTheExtract() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(plan)

        val stand = assertNotNull(controller.standPosition(arriving = false, gate = "C24"))
        assertEquals(29.9905, stand.latitude, 1e-6)
        assertNull(controller.standPosition(arriving = false, gate = "Z99"))
        assertNull(controller.standPosition(arriving = false, gate = ""))
    }

    /**
     * The ODbL attribution rides with the data everywhere it is shown or exported. It is
     * the licence condition, not decoration, so it is asserted rather than assumed.
     */
    @Test
    fun theAttributionTravelsWithTheData() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(plan)

        val rows = controller.diagnosticRows()
        assertTrue(rows.any { it.second == OSMSurface.ATTRIBUTION_TEXT })
        assertTrue(rows.any { it.second == OSMSurface.LICENSE_NAME })

        val exported = controller.exportText()
        assertTrue(exported.contains(OSMSurface.ATTRIBUTION_TEXT))
        assertTrue(exported.contains(OSMSurface.LICENSE_NAME))
        // Never CC BY 4.0 — OpenStreetMap is ODbL, and saying otherwise would be a
        // licensing claim the project cannot make.
        assertFalse(exported.contains("CC BY 4.0"))
    }

    @Test
    fun clearingTheCacheEmptiesTheLoadedSurfaces() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(plan)
        assertNotNull(controller.state.value.departure)

        controller.clearCache()
        assertNull(controller.state.value.departure)
        assertNull(controller.state.value.arrival)
        assertNull(controller.state.value.cacheSummary)
    }

    @Test
    fun theCacheSummaryCountsWhatIsStored() = runTest {
        val http = FakeHttp(overpassBody)
        val controller = controller(http)
        controller.refresh(plan)
        val summary = assertNotNull(controller.state.value.cacheSummary)
        assertTrue(summary.startsWith("2 airports"))
    }
}
