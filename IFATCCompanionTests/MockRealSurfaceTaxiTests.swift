import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Mock Mode taxi over a pre-cached *real* airport surface: the demo prefers the real
/// field (so it taxis the actual airport) and falls back to the synthetic field when the
/// real one can't be routed — while the taxi map still appears with simulated movement on
/// both departure and arrival. Also verifies the mock route's realistic default gates.
@MainActor
final class MockRealSurfaceTaxiTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func makeCoordinator() -> AirportSurfaceCoordinator {
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        return coord
    }

    /// A pre-cached "real" surface stand-in (built with the mock geometry, but delivered
    /// via the pre-cache path rather than the synthetic fallback).
    private func realSurface(icao: String, runway: String, gate: String) -> AirportSurfaceModel {
        MockAirportSurface.model(icao: icao, reference: ref, primaryRunwayIdent: runway, gate: gate)
    }

    func testDefaultMockRouteHasRealisticUnitedGates() {
        let route = MockSimulatorFeed.defaultRoute()
        XCTAssertEqual(route.departure, "KIAH")
        XCTAssertEqual(route.destination, "KMSP")
        XCTAssertFalse(route.departureGate.isEmpty, "the mock origin has a default United gate")
        XCTAssertFalse(route.arrivalGate.isEmpty, "the mock destination has a default United gate")
    }

    func testMockModeAppliesRouteGatesToFlightPlan() {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.departureGate = ""
        model.settings.arrivalGate = ""
        model.syncFlightPlanFromSettings()
        XCTAssertEqual(model.flightPlan.departureGate, model.mock.route.departureGate)
        XCTAssertEqual(model.flightPlan.arrivalGate, model.mock.route.arrivalGate)
    }

    func testEnteredGateOverridesMockDefault() {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.departureGate = "E7"
        model.settings.arrivalGate = "F12"
        model.syncFlightPlanFromSettings()
        XCTAssertEqual(model.flightPlan.departureGate, "E7", "an entered gate wins over the mock default")
        XCTAssertEqual(model.flightPlan.arrivalGate, "F12")
    }

    func testMockDepartureUsesPreCachedRealSurface() {
        let coord = makeCoordinator()
        // The whole airport is pre-cached (as prepareSimulatedSurfaces would after fetching).
        coord.injectSimulatedSurfaceForTesting(realSurface(icao: "KIAH", runway: "15L", gate: "C24"),
                                               icao: "KIAH")
        coord.beginDeparture(icao: "KIAH", reference: ref, aircraftName: "Boeing 737-800",
                             runway: "15L", gate: "C24",
                             startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), mock: true)
        coord.taxiReadBackComplete()

        XCTAssertTrue(coord.taxiMapVisible, "the taxi map appears for the simulated departure")
        XCTAssertNotNil(coord.routeForTesting)
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "the pre-cached real surface is used, not the synthetic fallback")
        XCTAssertEqual(coord.routeForTesting?.holdShortRunway, "15L")
        coord.hideTaxiMap()
    }

    func testMockDepartureFallsBackToSyntheticWhenRealSurfaceUnroutable() {
        let coord = makeCoordinator()
        // A pre-cached real surface whose runways don't include the assigned runway, so it
        // can't be routed — the demo must fall back to the synthetic field.
        coord.injectSimulatedSurfaceForTesting(realSurface(icao: "KIAH", runway: "09", gate: "C24"),
                                               icao: "KIAH")
        coord.beginDeparture(icao: "KIAH", reference: ref, aircraftName: "Boeing 737-800",
                             runway: "15L", gate: "C24",
                             startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), mock: true)
        coord.taxiReadBackComplete()

        XCTAssertTrue(coord.taxiMapVisible, "the map still appears via the synthetic fallback")
        XCTAssertNotNil(coord.routeForTesting, "the synthetic fallback produces a route")
        XCTAssertTrue(coord.usingSyntheticSurfaceForTesting,
                      "an unroutable real surface falls back to the synthetic field")
        XCTAssertEqual(coord.routeForTesting?.holdShortRunway, "15L")
        coord.hideTaxiMap()
    }

    func testMockTaxiUpgradesToRealSurfaceWhenPreCacheArrivesLate() {
        let coord = makeCoordinator()
        // The field's real surface isn't pre-cached yet (e.g. a large destination like KMSP
        // whose extract is still fetching), so the taxi begins on the synthetic fallback.
        coord.beginDeparture(icao: "KMSP", reference: ref, aircraftName: "Boeing 737-800",
                             runway: "12R", gate: "C6",
                             startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), mock: true)
        XCTAssertTrue(coord.usingSyntheticSurfaceForTesting,
                      "the taxi begins on the synthetic fallback while the real extract is loading")

        // The pre-cache fetch resolves before the pilot reads back / the drive starts: the
        // taxi upgrades onto the real field so the demo taxis the actual airport.
        coord.deliverSimulatedSurfaceForTesting(realSurface(icao: "KMSP", runway: "12R", gate: "C6"),
                                                icao: "KMSP")
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "the real surface is adopted once it arrives, before the drive starts")

        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "the taxi map appears for the simulated taxi")
        XCTAssertEqual(coord.routeForTesting?.holdShortRunway, "12R")
        coord.hideTaxiMap()
    }

    func testMockTaxiKeepsSyntheticWhenRealSurfaceArrivesMidDrive() {
        let coord = makeCoordinator()
        coord.beginArrival(icao: "KMSP", reference: ref, aircraftName: "Boeing 737-800",
                           gate: "C6",
                           startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                           mock: true, arrivalRunway: "36")
        coord.taxiReadBackComplete()   // reveals the map and starts the simulated drive
        coord.mockTickForTesting()     // the aircraft is now moving along the synthetic route
        XCTAssertTrue(coord.usingSyntheticSurfaceForTesting)

        // A late-arriving real surface must not teleport the aircraft mid-drive — it is cached
        // for next time but the current drive stays on the synthetic field.
        coord.deliverSimulatedSurfaceForTesting(realSurface(icao: "KMSP", runway: "36", gate: "C6"),
                                                icao: "KMSP")
        XCTAssertTrue(coord.usingSyntheticSurfaceForTesting,
                      "a real surface arriving mid-drive does not swap the surface out from under the aircraft")
        coord.hideTaxiMap()
    }

    func testMockArrivalTaxiMapDrivesInToGate() {
        let coord = makeCoordinator()
        coord.beginArrival(icao: "KTEST", reference: ref, aircraftName: "Boeing 737-800",
                           gate: "A1",
                           startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                           mock: true, arrivalRunway: "36")
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "the taxi map appears for the simulated arrival")
        XCTAssertEqual(coord.routeForTesting?.arrivalGate, "A1")

        // The simulated aircraft taxis in, authorizing the runway crossing when asked.
        var n = 0
        while !coord.reachedDestination && n < 2000 {
            coord.mockTickForTesting()
            if coord.awaitingCrossingReadback { coord.crossingReadbackReceived() }
            n += 1
        }
        XCTAssertTrue(coord.reachedDestination, "the simulated aircraft taxis in to the gate")
        XCTAssertTrue(coord.taxiMapVisible, "the map stays visible through the arrival taxi")
        coord.hideTaxiMap()
    }
}
