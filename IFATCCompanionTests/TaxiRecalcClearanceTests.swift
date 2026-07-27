import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Recalculating a taxi route — whether the pilot taps Recalculate / Request New Taxi, or
/// an automatic off-route recalculation fires — issues a fresh Ground taxi clearance with a
/// read-back **only when the route materially changes**. An identical route stays silent so
/// recalculating doesn't repeat the same instruction.
@MainActor
final class TaxiRecalcClearanceTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 40, longitude: -75)

    /// A live (non-mock) departure with a computed route to runway 36 that crosses runway 09,
    /// with the initial Ground clearance already read back. Emitted transmissions append to
    /// `emit`.
    private func departureCoordinator(emit: @escaping (ATCTransmission) -> Void) -> AirportSurfaceCoordinator {
        let coord = AirportSurfaceCoordinator()
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        coord.configure(diagnostics: nil, engine: engine, emit: emit,
                        callsign: { engine.callsign(airline: "United", flightNumber: "598", fallback: "") })
        let model = MockAirportSurface.model(icao: "KTEST", reference: ref, primaryRunwayIdent: "36", gate: "A1")
        coord.simulateDeferredDepartureForTesting(model: model, runway: "36", gate: "A1")
        coord.taxiReadBackComplete()   // the pilot reads back the initial clearance
        return coord
    }

    /// Count of Ground taxi clearances ("… taxi to runway …") emitted so far. Excludes runway
    /// crossing clearances ("cross runway …") and the low-confidence "taxi toward runway …"
    /// fallback.
    private func taxiClearances(_ txs: [ATCTransmission]) -> [ATCTransmission] {
        txs.filter { $0.displayText.lowercased().contains("taxi to runway") }
    }

    // MARK: - Manual recalculate

    func testManualRecalculateIssuesNewGroundClearanceWhenRouteChanges() {
        var emitted: [ATCTransmission] = []
        let coord = departureCoordinator(emit: { emitted.append($0) })
        XCTAssertEqual(taxiClearances(emitted).count, 1, "the initial detailed clearance went out")
        XCTAssertEqual(coord.routeForTesting?.crossings.isEmpty, false, "the initial route crosses runway 09")

        // The aircraft has taxied down taxiway A, past the crossing. Recalculating from here
        // resolves to a route that no longer crosses a runway — a materially different clearance.
        let pastCrossing = CLLocationCoordinate2D(latitude: ref.latitude - 0.0015, longitude: ref.longitude + 0.0030)
        coord.feedForTesting(coordinate: pastCrossing, heading: 180, groundSpeed: 0)
        coord.recalculateRoute()

        let clearances = taxiClearances(emitted)
        XCTAssertEqual(clearances.count, 2, "recalculating to a new route re-issues a Ground taxi clearance")
        XCTAssertTrue(clearances.last?.displayText.lowercased().contains("taxi to runway 36 via") ?? false,
                      "the new clearance names the runway and taxiway sequence")
        XCTAssertNotNil(clearances.last?.readback, "the recalculated clearance carries a read-back")
        XCTAssertTrue(coord.awaitingTaxiReadback, "the recalculated clearance re-arms the read-back")
        XCTAssertEqual(coord.routeForTesting?.crossings.isEmpty, true, "the recalculated route no longer crosses a runway")
    }

    func testManualRecalculateStaysSilentWhenRouteUnchanged() {
        var emitted: [ATCTransmission] = []
        let coord = departureCoordinator(emit: { emitted.append($0) })
        XCTAssertEqual(taxiClearances(emitted).count, 1)
        XCTAssertFalse(coord.awaitingTaxiReadback, "the initial read-back is complete")

        // Recalculating from the same stand reproduces the same route → no new instruction.
        let gate = MockAirportSurface.gateCoordinate(reference: ref)
        coord.feedForTesting(coordinate: gate, heading: 180, groundSpeed: 0)
        coord.recalculateRoute()

        XCTAssertEqual(taxiClearances(emitted).count, 1, "an unchanged route does not re-issue a clearance")
        XCTAssertFalse(coord.awaitingTaxiReadback, "no read-back is armed when the route is unchanged")
    }

    // MARK: - Automatic off-route recalculate

    func testAutoRecalculateIssuesNewClearanceOnSustainedOffRoute() {
        var emitted: [ATCTransmission] = []
        let coord = departureCoordinator(emit: { emitted.append($0) })
        coord.autoRecalculate = true

        // Off the route (a lateral offset beyond the tracker threshold) and past the crossing,
        // held for several ticks. Auto-recalculate re-plans from here — dropping the crossing —
        // and Ground issues the updated clearance rather than latching the off-route banner.
        let offRoutePastCrossing = CLLocationCoordinate2D(latitude: ref.latitude - 0.0015, longitude: ref.longitude + 0.0037)
        for _ in 0..<6 { coord.feedForTesting(coordinate: offRoutePastCrossing, heading: 180, groundSpeed: 0) }

        XCTAssertFalse(coord.offRoute, "auto-recalculate re-plans instead of latching the off-route banner")
        XCTAssertEqual(taxiClearances(emitted).count, 2, "the automatic recalculation issues one fresh Ground clearance")
        XCTAssertTrue(coord.awaitingTaxiReadback, "the automatic clearance arms a read-back")
    }

    func testOffRouteWithoutAutoRecalculateShowsBannerAndDoesNotReissue() {
        var emitted: [ATCTransmission] = []
        let coord = departureCoordinator(emit: { emitted.append($0) })
        // autoRecalculate stays at its default (off).

        let offRoutePastCrossing = CLLocationCoordinate2D(latitude: ref.latitude - 0.0015, longitude: ref.longitude + 0.0037)
        for _ in 0..<6 { coord.feedForTesting(coordinate: offRoutePastCrossing, heading: 180, groundSpeed: 0) }

        XCTAssertTrue(coord.offRoute, "without auto-recalculate the off-route banner latches for the pilot to decide")
        XCTAssertEqual(taxiClearances(emitted).count, 1, "no new clearance is issued while the pilot decides")
        XCTAssertFalse(coord.awaitingTaxiReadback, "no read-back is armed while the banner is shown")
    }
}
