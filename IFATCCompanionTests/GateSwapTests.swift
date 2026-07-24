import XCTest
@testable import IFATCCompanion

/// Verifies the Dep/Arr gate swap shortcut surfaced on the ATC card: pressing it
/// trades the two gate values and pushes them into the active flight plan, which
/// is the common case when the pilot flies the return leg.
@MainActor
final class GateSwapTests: XCTestCase {

    func testSwapExchangesGateValues() {
        let model = AppModel()
        model.settings.departureGate = "C12"
        model.settings.arrivalGate = "B44"

        model.swapManualGates()

        XCTAssertEqual(model.settings.departureGate, "B44")
        XCTAssertEqual(model.settings.arrivalGate, "C12")
        XCTAssertEqual(model.flightPlan.departureGate, "B44",
                       "swap must mirror the new departure gate into the plan")
        XCTAssertEqual(model.flightPlan.arrivalGate, "C12",
                       "swap must mirror the new arrival gate into the plan")
    }

    func testSwapWithOneEmptyGate() {
        let model = AppModel()
        model.settings.departureGate = "A1"
        model.settings.arrivalGate = ""

        model.swapManualGates()

        XCTAssertEqual(model.settings.departureGate, "")
        XCTAssertEqual(model.settings.arrivalGate, "A1")
        XCTAssertEqual(model.flightPlan.departureGate, "")
        XCTAssertEqual(model.flightPlan.arrivalGate, "A1")
    }
}
