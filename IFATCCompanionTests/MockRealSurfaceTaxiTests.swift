import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Mock Mode taxi over the *real* airport surface: the demo always taxis the actual
/// downloaded/cached OSM field for both its origin and destination — using the pre-cached
/// extract when ready, otherwise *waiting* for the real field to download rather than
/// dropping onto the bundled synthetic surface. It falls back to the synthetic field only
/// when the real one genuinely can't be produced (offline / no OSM data) or can't be routed,
/// while the taxi map still appears with simulated movement on both departure and arrival.
/// Also verifies the mock route's realistic default gates.
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

    /// A coordinator whose provider can never reach the network (no endpoints, an isolated
    /// empty cache), so the "wait for the real field" path can be driven deterministically: the
    /// background download always fails fast, and the test itself supplies the real surface via
    /// `deliverSimulatedSurfaceForTesting` (or asserts the offline synthetic fallback).
    private func makeOfflineCoordinator() -> AirportSurfaceCoordinator {
        let provider = AirportSurfaceProvider(
            cache: AirportSurfaceCache(directoryName: "test-mock-real-surface-offline"),
            endpoints: [])
        let coord = AirportSurfaceCoordinator(provider: provider)
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

    func testMockDestinationWaitsForRealSurfaceThenTaxisIt() {
        let coord = makeOfflineCoordinator()
        // The demo destination's reference is recorded (as prepareSimulatedSurfaces would), but
        // its real extract isn't cached yet — e.g. a large destination like KMSP whose download
        // is still in flight when the arrival taxi begins.
        coord.setSimulatedReferenceForTesting(ref, icao: "KMSP")
        coord.beginArrival(icao: "KMSP", reference: ref, aircraftName: "Boeing 737-800",
                           gate: "C6",
                           startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                           mock: true, arrivalRunway: "36")

        // It must WAIT for the real field, not drop onto the bundled synthetic surface.
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "a demo destination waits for the real field rather than using the synthetic surface")
        XCTAssertTrue(coord.surfaceLoadInProgress,
                      "the surface stays loading while the real destination field downloads")

        // The pilot reads back, but the taxi map stays withheld until the real field is ready.
        coord.taxiReadBackComplete()
        XCTAssertFalse(coord.taxiMapVisible,
                       "the taxi map is withheld until the real destination surface downloads")

        // The download resolves before the drive starts: the demo taxis the actual airport.
        coord.deliverSimulatedSurfaceForTesting(realSurface(icao: "KMSP", runway: "36", gate: "C6"),
                                                icao: "KMSP")
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "the real destination surface is adopted once downloaded, not the bundled model")
        XCTAssertTrue(coord.taxiMapVisible, "the map reveals on the real destination surface")
        XCTAssertEqual(coord.routeForTesting?.arrivalGate, "C6")
        coord.hideTaxiMap()
    }

    func testMockOriginWaitsForRealSurfaceThenTaxisIt() {
        let coord = makeOfflineCoordinator()
        // The demo origin's reference is recorded but its real extract isn't cached yet.
        coord.setSimulatedReferenceForTesting(ref, icao: "KIAH")
        coord.beginDeparture(icao: "KIAH", reference: ref, aircraftName: "Boeing 737-800",
                             runway: "15L", gate: "C24",
                             startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), mock: true)

        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "a demo origin waits for the real field rather than using the synthetic surface")
        XCTAssertTrue(coord.surfaceLoadInProgress,
                      "the surface stays loading while the real origin field downloads")

        // The download resolves before the pilot reads back / the drive starts.
        coord.deliverSimulatedSurfaceForTesting(realSurface(icao: "KIAH", runway: "15L", gate: "C24"),
                                                icao: "KIAH")
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting,
                       "the real origin surface is adopted once downloaded, not the bundled model")

        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "the taxi map appears on the real origin surface")
        XCTAssertEqual(coord.routeForTesting?.holdShortRunway, "15L")
        coord.hideTaxiMap()
    }

    func testMockDemoFallsBackToSyntheticWhenRealDownloadFails() async {
        let coord = makeOfflineCoordinator()
        coord.setSimulatedReferenceForTesting(ref, icao: "KMSP")
        coord.beginArrival(icao: "KMSP", reference: ref, aircraftName: "Boeing 737-800",
                           gate: "C6",
                           startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                           mock: true, arrivalRunway: "36")
        XCTAssertFalse(coord.usingSyntheticSurfaceForTesting, "it waits for the real field first")

        // The real download can never resolve (offline / no endpoints); once it fails, the demo
        // falls back to the synthetic field so it still taxis rather than hanging on loading.
        var waited = 0
        while coord.surfaceLoadInProgress && waited < 200 {
            try? await Task.sleep(nanoseconds: 20_000_000)
            waited += 1
        }
        XCTAssertTrue(coord.usingSyntheticSurfaceForTesting,
                      "a real download that can't be produced falls back to the synthetic field")
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "the map still appears via the synthetic fallback")
        coord.hideTaxiMap()
    }

    func testMockTaxiKeepsSyntheticWhenRealSurfaceArrivesMidDrive() {
        let coord = makeCoordinator()
        // No recorded reference for this field, so the taxi uses the synthetic fallback and the
        // drive starts on it (the offline / no-OSM-data path).
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
