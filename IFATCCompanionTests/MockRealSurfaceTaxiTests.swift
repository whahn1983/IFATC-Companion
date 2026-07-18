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
