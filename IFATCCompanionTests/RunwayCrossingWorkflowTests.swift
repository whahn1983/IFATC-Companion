import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Crossing workflow: detection, hold-short, separate crossing clearance, read-back
/// required before authorization, early runway-entry warning, completion + taxi resume,
/// and low-confidence automation disabled.
@MainActor
final class RunwayCrossingWorkflowTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    private func makeCoordinator() -> (AirportSurfaceCoordinator, () -> [ATCTransmission]) {
        let coord = AirportSurfaceCoordinator()
        var collected: [ATCTransmission] = []
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine,
                        emit: { collected.append($0) },
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        return (coord, { collected })
    }

    private func tick(_ coord: AirportSurfaceCoordinator, until: () -> Bool, max: Int = 800) {
        var n = 0
        while !until() && n < max { coord.mockTickForTesting(); n += 1 }
    }

    func testFullDepartureCrossingSequence() {
        let (coord, messages) = makeCoordinator()
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        XCTAssertNotNil(coord.routeForTesting)
        XCTAssertEqual(coord.routeForTesting?.crossings.count, 1)

        // Drive up to the point a crossing clearance is issued and awaits a read-back.
        tick(coord, until: { coord.awaitingCrossingReadback })
        XCTAssertTrue(coord.awaitingCrossingReadback, "a separate crossing clearance should await a read-back")

        // A high-confidence crossing clearance is issued automatically as the aircraft nears
        // the runway — with no redundant hold-short call (the taxi clearance already held the
        // pilot short of this first crossing).
        let text = messages().map { $0.displayText.lowercased() }
        XCTAssertTrue(text.contains { $0.contains("cross runway") }, "a separate crossing clearance is issued")
        XCTAssertFalse(text.contains { $0.contains("hold short") },
                       "no redundant hold-short precedes an automatic crossing clearance")

        // NOT authorized before the read-back.
        XCTAssertFalse(coord.crossingState.isAuthorized, "crossing must not be authorized before read-back")

        // Read back → authorized.
        coord.crossingReadbackReceived()
        XCTAssertTrue(coord.crossingState.isAuthorized, "crossing authorized after read-back")
        XCTAssertFalse(coord.awaitingCrossingReadback)

        // Continue: the aircraft crosses, vacates, and reaches the departure runway hold.
        tick(coord, until: { coord.reachedDestination })
        XCTAssertTrue(coord.reachedDestination, "aircraft reaches the departure runway hold-short point")
        // The taxi route resumed (continue-taxi issued after vacating).
        XCTAssertTrue(messages().contains { $0.displayText.lowercased().contains("continue taxi") })
        // The crossing sequence is no longer active.
        XCTAssertTrue(coord.crossingState == .noCrossingPending || coord.crossingState == .taxiResumed)
    }

    func testCrossingNotAuthorizedUntilReadback() {
        let (coord, _) = makeCoordinator()
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        tick(coord, until: { coord.awaitingCrossingReadback })
        // Keep ticking WITHOUT reading back: the aircraft must stay held short.
        for _ in 0..<30 { coord.mockTickForTesting() }
        XCTAssertFalse(coord.crossingState.isAuthorized, "no read-back → no authorization → no crossing")
        XCTAssertFalse(coord.reachedDestination, "held short of the crossing without authorization")
    }

    func testEarlyRunwayEntryProducesWarning() {
        let (coord, messages) = makeCoordinator()
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        guard let route = coord.routeForTesting, let crossing = route.crossings.first else {
            return XCTFail("expected a crossing")
        }
        // Inject an aircraft moving into the corridor toward the runway, unauthorized.
        let line = route.clGeometry
        let approach = SurfaceGeometry.pointAlong(line, meters: max(0, crossing.alongMeters - 10)) ?? crossing.point.clLocation
        let heading = Geo.bearing(from: approach, to: crossing.point.clLocation)
        coord.feedForTesting(coordinate: approach, heading: heading, groundSpeed: 15)
        coord.feedForTesting(coordinate: approach, heading: heading, groundSpeed: 15)   // sustained
        XCTAssertEqual(coord.crossingState, .unauthorizedCrossingDetected)
        XCTAssertTrue(messages().contains {
            let t = $0.displayText.lowercased()
            return t.contains("hold position") || t.contains("stop immediately")
        }, "an early runway entry must produce a simulated hold/stop warning")
    }

    func testLowConfidenceDisablesAutomaticCrossingClearance() {
        let (coord, _) = makeCoordinator()
        // A stripped, unnamed, hold-less surface → low crossing confidence.
        var m = MockAirportSurface.model(icao: "KLOW", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        m.taxiways = m.taxiways.map { var t = $0; t.name = ""; return t }
        m.holdingPositions = []
        m.confidence = OSMSurfaceNormalizer.preliminaryConfidence(m)
        coord.installSurfaceForTesting(m, kind: .departure, runway: "36", gate: "A1")
        guard coord.routeForTesting?.crossings.first != nil else { return XCTFail("expected a crossing") }

        // Drive to the hold: no automatic crossing clearance should be issued.
        for _ in 0..<300 { coord.mockTickForTesting() }
        XCTAssertFalse(coord.awaitingCrossingReadback,
                       "low-confidence crossings must not auto-issue a detailed clearance")

        // The pilot must Request Crossing; then the clearance is issued.
        coord.requestCrossing()
        for _ in 0..<10 { coord.mockTickForTesting() }
        XCTAssertTrue(coord.awaitingCrossingReadback, "Request Crossing yields the clearance")
    }

    func testRequestCrossingIssuesClearanceBeforeSettlingAtHold() {
        // Regression: at the runway threshold the Request Crossing button did nothing when
        // the aircraft hadn't tripped the settle-at-hold heuristics (the OSM hold point not
        // matching the sim scenery). Tapping it must issue the clearance regardless.
        let (coord, _) = makeCoordinator()
        coord.autoCrossingCalls = false   // force the manual Request-Crossing path
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        guard let route = coord.routeForTesting, let crossing = route.crossings.first else {
            return XCTFail("expected a crossing")
        }
        // Approach the crossing but stay moving and short of the mapped hold point, so the
        // "holding short + settled" gate has NOT been met.
        let line = route.clGeometry
        let approach = SurfaceGeometry.pointAlong(line, meters: max(0, crossing.alongMeters - 60)) ?? crossing.point.clLocation
        let heading = Geo.bearing(from: approach, to: crossing.point.clLocation)
        coord.feedForTesting(coordinate: approach, heading: heading, groundSpeed: 10)
        XCTAssertFalse(coord.awaitingCrossingReadback, "no automatic clearance with auto calls off")

        coord.requestCrossing()
        XCTAssertTrue(coord.awaitingCrossingReadback,
                      "Request Crossing must issue the clearance even before settling at the hold")
    }

    func testAutoCrossingCallsOverrideDisablesAutomation() {
        let (coord, _) = makeCoordinator()
        coord.autoCrossingCalls = false
        coord.beginMockTaxiForTesting(kind: .departure, reference: ref, runway: "36", gate: "A1")
        for _ in 0..<300 { coord.mockTickForTesting() }
        XCTAssertFalse(coord.awaitingCrossingReadback,
                       "with automatic crossing calls off, no clearance auto-issues")
        coord.requestCrossing()
        for _ in 0..<10 { coord.mockTickForTesting() }
        XCTAssertTrue(coord.awaitingCrossingReadback)
    }
}
