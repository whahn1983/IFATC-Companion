import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Phraseology: no "cleared to taxi", assigned runway + taxiway sequence + hold-short
/// included, crossing runway in the read-back, no "cross all runways", and no crossing
/// clearance from Ramp.
final class TaxiPhraseologyTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)
    private func engine() -> PhraseologyEngine { PhraseologyEngine(digitStyle: .individual, mode: .faa) }
    private func cs(_ e: PhraseologyEngine) -> PhraseologyEngine.Callsign {
        e.callsign(airline: "United", flightNumber: "598", fallback: "")
    }

    private func departureRoute(runway: String = "36") -> SurfaceTaxiRoute {
        let m = MockAirportSurface.model(icao: "KTEST", reference: ref, primaryRunwayIdent: runway, gate: "A1")
        let g = SurfaceGraphBuilder.build(from: m)
        return TaxiRouteEngine(graph: g, model: m).route(
            .init(startCoordinate: MockAirportSurface.gateCoordinate(reference: ref), startGateName: "A1",
                  isDeparture: true, assignedRunwayIdent: runway, arrivalGateName: nil, aircraft: .medium))!
    }

    func testTaxiClearanceIncludesRunwaySequenceAndHoldShort() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.taxiClearance(cs: cs(e), route: departureRoute(runway: "36"), runway: "36")
        let text = tx.displayText.lowercased()
        XCTAssertTrue(text.contains("taxi to runway 36"), tx.displayText)
        XCTAssertTrue(text.contains("via a"), "taxiway sequence must be named: \(tx.displayText)")
        // Hold-short names both directions of the physical runway (36/18).
        XCTAssertTrue(text.contains("hold short runway 18-36"), tx.displayText)
        XCTAssertTrue(tx.spokenText.lowercased().contains("hold short runway one eight three six"), tx.spokenText)
        XCTAssertEqual(tx.facility, .ground)
    }

    func testTaxiClearanceHoldsShortOfFirstCrossing() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        // When the route crosses a runway, the clearance holds the pilot short of that first
        // crossing (real-world "taxi to runway 36 … hold short runway 09"), not the
        // destination runway.
        let tx = phr.taxiClearance(cs: cs(e), route: departureRoute(runway: "36"), runway: "36",
                                   holdShortCrossing: "09")
        let text = tx.displayText.lowercased()
        XCTAssertTrue(text.contains("taxi to runway 36"), tx.displayText)
        // Holds short of the first crossing (09/27), named in both directions.
        XCTAssertTrue(text.contains("hold short runway 9-27"), "must hold short of the first crossing: \(tx.displayText)")
        XCTAssertFalse(text.contains("hold short runway 18-36"),
                       "the crossing hold-short replaces the destination-runway hold-short: \(tx.displayText)")
        XCTAssertTrue(tx.readback?.displayText.lowercased().contains("hold short runway 9-27") ?? false,
                      tx.readback?.displayText ?? "")
    }

    func testTaxiClearanceNeverSaysClearedToTaxiOrCrossAllRunways() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.taxiClearance(cs: cs(e), route: departureRoute(), runway: "36")
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared to taxi"))
        XCTAssertFalse(tx.displayText.lowercased().contains("cross all runways"))
        XCTAssertFalse(tx.spokenText.lowercased().contains("cleared to taxi"))
    }

    func testTaxiReadbackContainsRunwayAndHoldShort() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.taxiClearance(cs: cs(e), route: departureRoute(), runway: "36")
        XCTAssertNotNil(tx.readback)
        XCTAssertTrue(tx.readback?.displayText.contains("36") ?? false)
        XCTAssertTrue(tx.readback?.displayText.lowercased().contains("hold short") ?? false)
    }

    func testCrossingClearanceContainsRunwayAndIsFromGroundNotRamp() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.crossingClearance(cs: cs(e), runwayIdent: "09", atTaxiway: "A")
        // The crossing names both directions of the physical runway (09/27).
        XCTAssertTrue(tx.displayText.lowercased().contains("cross runway 9-27"))
        XCTAssertTrue(tx.spokenText.lowercased().contains("cross runway niner two seven"))
        XCTAssertFalse(tx.displayText.lowercased().contains("cross all runways"))
        XCTAssertEqual(tx.facility, .ground, "a runway-crossing clearance is issued by Ground, never Ramp")
        XCTAssertNotEqual(tx.facility, .ramp)
        // Read-back must contain the runway identifier.
        XCTAssertNotNil(tx.readback)
        XCTAssertTrue(tx.readback?.displayText.contains("9-27") ?? false)
    }

    func testHoldShortPhrase() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.holdShort(cs: cs(e), runwayIdent: "09")
        // Names both directions of the physical runway (09/27).
        XCTAssertTrue(tx.displayText.lowercased().contains("hold short of runway 9-27"))
        XCTAssertTrue(tx.spokenText.lowercased().contains("hold short of runway niner two seven"))
        XCTAssertEqual(tx.facility, .ground)
    }

    func testLowConfidenceTaxiIsConservative() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let tx = phr.lowConfidenceTaxi(cs: cs(e), runway: "27")
        let text = tx.displayText.lowercased()
        XCTAssertTrue(text.contains("unavailable"))
        XCTAssertTrue(text.contains("hold short of all runways"))
        XCTAssertFalse(text.contains("via a"), "conservative form must not name specific taxiways")
        XCTAssertFalse(text.contains("cross all runways"))
    }

    func testArrivalTaxiNamesTheGate() {
        let e = engine()
        let phr = TaxiPhraseology(engine: e)
        let m = MockAirportSurface.model(icao: "KTEST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        let g = SurfaceGraphBuilder.build(from: m)
        let route = TaxiRouteEngine(graph: g, model: m).route(
            .init(startCoordinate: MockAirportSurface.runwayExitCoordinate(reference: ref), startGateName: nil,
                  isDeparture: false, assignedRunwayIdent: nil, arrivalGateName: "A1", aircraft: .medium))!
        let tx = phr.arrivalTaxi(cs: cs(e), route: route, gate: "A1")
        XCTAssertTrue(tx.displayText.lowercased().contains("gate a1"))
        XCTAssertFalse(tx.displayText.lowercased().contains("cleared to taxi"))
    }
}
