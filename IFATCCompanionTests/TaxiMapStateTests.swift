import XCTest
import CoreLocation
@testable import IFATCCompanion

/// UI / state: the taxi map appears after the Ground taxi read-back, stays visible
/// through the crossing and holding short, hides on the Ground→Tower hand-off, reappears
/// for arrival, and Mock Mode completes the full flow.
@MainActor
final class TaxiMapStateTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        var plan = FlightPlan()
        plan.airline = "United"; plan.flightNumber = "598"
        plan.departure = "KIAH"; plan.destination = "KMSP"
        plan.departureGate = "C12"; plan.arrivalGate = "B44"
        plan.cruiseAltitude = 37000
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    func testTaxiMapAppearsAfterGroundTaxiReadbackAndHidesOnTowerHandoff() {
        let model = makeModel()
        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()   // Ramp → Ground hand-off
        XCTAssertFalse(model.airportSurface.taxiMapVisible, "map not shown before the taxi clearance")

        model.requestTaxi()                            // Ground issues the taxi clearance
        XCTAssertFalse(model.airportSurface.taxiMapVisible, "map appears only after the read-back")
        model.readBack()                               // read back the taxi clearance
        XCTAssertTrue(model.airportSurface.taxiMapVisible, "taxi map appears after Ground taxi read-back")

        model.reportReadyForDeparture()
        XCTAssertFalse(model.airportSurface.taxiMapVisible, "taxi map hides on the Ground→Tower hand-off")
    }

    func testTaxiMapStaysVisibleThroughCrossingAndHoldingShort() {
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        XCTAssertTrue(coord.taxiMapVisible)

        var n = 0
        while !coord.awaitingCrossingReadback && n < 800 { coord.mockTickForTesting(); n += 1 }
        XCTAssertTrue(coord.awaitingCrossingReadback)
        XCTAssertTrue(coord.taxiMapVisible, "map stays visible while holding short for the crossing")

        coord.crossingReadbackReceived()
        for _ in 0..<20 { coord.mockTickForTesting() }
        XCTAssertTrue(coord.taxiMapVisible, "map stays visible through the crossing")
    }

    func testTaxiMapReappearsForArrival() {
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        // Departure map shown, then Ground→Tower hides it.
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        XCTAssertTrue(coord.taxiMapVisible)
        coord.hideTaxiMap()
        XCTAssertFalse(coord.taxiMapVisible)

        // Arrival: Ground issues taxi-to-gate → the map reappears on read-back.
        coord.beginArrival(icao: "KTEST", reference: ref, aircraftName: "Boeing 737-800",
                           gate: "A1", startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref), mock: true)
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "taxi map reappears after landing for the arrival Ground taxi")
        XCTAssertEqual(coord.routeForTesting?.arrivalGate, "A1")
    }

    func testLiveTaxiClearanceSupersedesGenericOnceSurfaceLoads() {
        // Live, uncached airports load the surface asynchronously, so the pilot's taxi
        // request goes out before a route exists and a generic clearance is issued. Once
        // the Overpass fetch resolves, the detailed OSM route clearance must supersede it
        // and its read-back must reveal the taxi map.
        let coord = AirportSurfaceCoordinator()
        var emitted: [ATCTransmission] = []
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { emitted.append($0) },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        let model = MockAirportSurface.model(icao: "KTEST", reference: ref,
                                             primaryRunwayIdent: "36", gate: "A1")
        coord.simulateDeferredDepartureForTesting(model: model, runway: "36", gate: "A1")

        // A detailed route clearance (runway + taxiway sequence + hold-short) was issued,
        // not the generic "detailed taxi routing is unavailable" fallback. The route crosses a
        // runway, so it holds short of that first crossing (09), not the destination runway.
        let last = emitted.last?.displayText.lowercased() ?? ""
        XCTAssertTrue(last.contains("taxi to runway 36 via"), "detailed OSM route clearance issued: \(last)")
        XCTAssertTrue(last.contains("hold short runway 09"), "holds short of the first crossing: \(last)")
        XCTAssertFalse(last.contains("unavailable"), "must not fall back to the generic clearance")
        XCTAssertNotNil(coord.routeForTesting)

        // Reading it back reveals the taxi map.
        XCTAssertFalse(coord.taxiMapVisible, "map hidden until the pilot reads back the clearance")
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "taxi map appears after the superseding clearance is read back")
    }

    func testLiveArrivalTaxiClearanceSupersedesGenericOnceSurfaceLoads() {
        // Live arrival at an uncached field: the taxi-to-gate goes out generic before the
        // destination surface loads, then the detailed OSM gate route supersedes it once the
        // fetch resolves — and its read-back reveals the taxi map at the destination.
        let coord = AirportSurfaceCoordinator()
        var emitted: [ATCTransmission] = []
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { emitted.append($0) },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        let model = MockAirportSurface.model(icao: "KTEST", reference: ref,
                                             primaryRunwayIdent: "36", gate: "A1")
        coord.simulateDeferredArrivalForTesting(model: model, gate: "A1")

        // A detailed gate route ("taxi to gate A1 via …") was issued once the surface loaded.
        let last = emitted.last?.displayText.lowercased() ?? ""
        XCTAssertTrue(last.contains("taxi to gate a1 via"), "detailed OSM arrival route issued: \(last)")
        XCTAssertNotNil(coord.routeForTesting)

        // Reading it back reveals the taxi map.
        XCTAssertFalse(coord.taxiMapVisible, "map hidden until the pilot reads back the clearance")
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "taxi map appears after the arrival clearance is read back")
    }

    func testLiveArrivalMapShowsAircraftAndRecoversRouteWhenSurfaceReadyButRouteMissing() {
        // The reported MSY bug: landing at an uncached field, the surface loads (.ready) but
        // the route can't yet be computed from the runway rollout point, so a generic taxi
        // clearance goes out. Reading it back revealed the map — but the old code gated the
        // aircraft marker on a route existing, so the plane never appeared and nothing
        // re-routed during the taxi: the map stayed blank until the app was relaunched.
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        // Load a ready surface but start the arrival *at the gate itself*, so start == goal
        // and no route can be built — the "surface ready, route nil" state at reveal time.
        let model = MockAirportSurface.model(icao: "KMSY", reference: ref,
                                             primaryRunwayIdent: "36", gate: "A1")
        coord.simulateDeferredArrivalForTesting(model: model, gate: "A1",
                                                start: MockAirportSurface.gateCoordinate(reference: ref))
        XCTAssertNil(coord.routeForTesting, "no route computes from the on-top-of-the-gate start")

        // Reading back reveals the map even though the route is still missing.
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "the map is revealed on read-back")
        XCTAssertNil(coord.displayAircraft, "no telemetry has arrived yet")

        // A live telemetry sample from a routable point (the runway exit) must place the
        // aircraft immediately and recover the route — the empty map fills in without a
        // relaunch.
        coord.updateLive(coordinate: MockAirportSurface.runwayExitCoordinate(reference: ref),
                         heading: 0, onGround: true, groundSpeed: 6)
        XCTAssertNotNil(coord.displayAircraft, "the aircraft renders even while the route is still missing")
        XCTAssertNotNil(coord.routeForTesting, "the route recovers from the live position")
        XCTAssertEqual(coord.routeForTesting?.arrivalGate, "A1")

        coord.hideTaxiMap()
    }

    func testHidingTaxiMapClearsGeometrySoNextTaxiStartsFresh() {
        // Removing the map clears its geometry, so the next taxi never briefly shows the
        // previous airport's surface while the new one loads (the arrival map popping up
        // still showing the departure field).
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        XCTAssertNotNil(coord.routeForTesting)
        XCTAssertNotNil(coord.surfaceForTesting)

        coord.hideTaxiMap()
        XCTAssertNil(coord.routeForTesting, "route is cleared when the map is removed")
        XCTAssertNil(coord.surfaceForTesting, "surface is cleared when the map is removed")
    }

    func testResumeAfterRelaunchRevealsMapWithoutFreshReadback() {
        // The app was swiped away mid-taxi. On relaunch the taxi is re-begun but there is
        // no fresh read-back — `resumeTaxiAfterRelaunch` must reveal the map once the route
        // is ready, picking up where it left off.
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        // Re-begin the departure taxi (as the relaunch restore does) — no read-back yet.
        coord.beginDeparture(icao: "KTEST", reference: ref, aircraftName: "Boeing 737-800",
                             runway: "36", gate: "A1",
                             startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), mock: true)
        XCTAssertFalse(coord.taxiMapVisible, "map stays hidden until the taxi is resumed")

        coord.resumeTaxiAfterRelaunch()
        XCTAssertTrue(coord.taxiMapVisible, "the taxi map is restored on relaunch without a fresh read-back")
        XCTAssertNotNil(coord.routeForTesting)

        coord.hideTaxiMap()   // stop the mock ticker started by the reveal
    }

    func testResumeAfterRelaunchIsANoOpWithNoActiveTaxi() {
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        coord.resumeTaxiAfterRelaunch()
        XCTAssertFalse(coord.taxiMapVisible, "nothing to resume when no taxi is active")
    }

    func testLoadTimePrefetchDoesNotClobberActiveTaxiSurface() {
        // Pre-caching both airports at flight load must never disturb the surface of a
        // taxi already in progress (the departure load is gated on `kind == .none`).
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { _ in },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        XCTAssertEqual(coord.diagnosticsSnapshot().airportID, "KTEST")

        // A load-time prefetch naming a different departure is skipped mid-taxi (arrival is
        // empty so no provider/network warm is triggered either).
        coord.prefetchFlightSurfaces(departure: "KOTHER",
                                     departureReference: CLLocationCoordinate2D(latitude: 41, longitude: -74),
                                     arrival: "", arrivalReference: nil)
        XCTAssertEqual(coord.diagnosticsSnapshot().airportID, "KTEST", "active taxi surface unchanged")
        XCTAssertTrue(coord.taxiMapVisible)
    }

    func testMockModeCompletesFullFlow() {
        let coord = AirportSurfaceCoordinator()
        var emitted: [ATCTransmission] = []
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: { emitted.append($0) },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })

        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        // 1) taxi map visible after read-back
        XCTAssertTrue(coord.taxiMapVisible)
        // 2) approach the crossing → automatic separate crossing clearance
        var n = 0
        while !coord.awaitingCrossingReadback && n < 800 { coord.mockTickForTesting(); n += 1 }
        XCTAssertTrue(coord.awaitingCrossingReadback)
        // 3) read back → authorized
        coord.crossingReadbackReceived()
        XCTAssertTrue(coord.crossingState.isAuthorized)
        // 4) cross, vacate, reach the departure runway hold
        n = 0
        while !coord.reachedDestination && n < 1200 { coord.mockTickForTesting(); n += 1 }
        XCTAssertTrue(coord.reachedDestination)
        // 5) Ground hands to Tower → map disappears
        coord.hideTaxiMap()
        XCTAssertFalse(coord.taxiMapVisible)

        // The transcript carried the separate crossing clearance and the resume. The
        // first-crossing hold-short now rides in the initial Ground taxi clearance (issued by
        // AppModel, not the coordinator), so the workflow no longer repeats it before an
        // automatic high-confidence crossing.
        let text = emitted.map { $0.displayText.lowercased() }
        XCTAssertFalse(text.contains { $0.contains("hold short") },
                       "no redundant hold-short before an automatic crossing clearance")
        XCTAssertTrue(text.contains { $0.contains("cross runway") })
        XCTAssertTrue(text.contains { $0.contains("continue taxi") })
    }
}
