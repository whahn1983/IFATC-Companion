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
        // not the generic "detailed taxi routing is unavailable" fallback.
        let last = emitted.last?.displayText.lowercased() ?? ""
        XCTAssertTrue(last.contains("taxi to runway 36 via"), "detailed OSM route clearance issued: \(last)")
        XCTAssertTrue(last.contains("hold short runway 36"))
        XCTAssertFalse(last.contains("unavailable"), "must not fall back to the generic clearance")
        XCTAssertNotNil(coord.routeForTesting)

        // Reading it back reveals the taxi map.
        XCTAssertFalse(coord.taxiMapVisible, "map hidden until the pilot reads back the clearance")
        coord.taxiReadBackComplete()
        XCTAssertTrue(coord.taxiMapVisible, "taxi map appears after the superseding clearance is read back")
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
        // 2) approach the crossing → hold-short + separate crossing clearance
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

        // The transcript carried the hold-short, the separate crossing clearance, and a resume.
        let text = emitted.map { $0.displayText.lowercased() }
        XCTAssertTrue(text.contains { $0.contains("hold short") })
        XCTAssertTrue(text.contains { $0.contains("cross runway") })
        XCTAssertTrue(text.contains { $0.contains("continue taxi") })
    }
}
