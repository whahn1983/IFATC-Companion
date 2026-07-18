import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Exercises the ATIS wiring in `AppModel`: availability gating (at the gate, and
/// within 100 NM on arrival), and appending the received information code to the taxi
/// request and the Approach check-in — including graceful absence when no ATIS exists.
@MainActor
final class ATISAppModelTests: XCTestCase {

    private func makeModel() -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        model.settings.mockMode = true
        model.settings.initialClimbAltitudeFt = 5000
        model.settings.traconCeilingFL = 180

        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        plan.departure = "KIAH"
        plan.destination = "KMSP"
        plan.cruiseAltitude = 37000
        plan.star = "KKILR"
        plan.approach = "ILS 30L"
        plan.waypoints = model.mock.route.waypoints
        model.flightPlan = plan
        return model
    }

    private func report(_ icao: String, _ letter: String) -> AirportATIS {
        AirportATIS(airport: icao,
                    parts: [AirportATIS.Part(kind: .combined, letter: letter,
                                             text: "\(icao) INFORMATION \(ATISPhraseology.phoneticLetter(letter)).")],
                    fetchedAt: Date())
    }

    // MARK: - Availability

    func testNoButtonWhenNoATIS() {
        let model = makeModel()
        XCTAssertNil(model.currentATIS)
        XCTAssertFalse(model.atisButtonVisible)
    }

    func testDepartureATISAvailableAtGate() {
        let model = makeModel()
        model.setATISReportsForTesting(departure: report("KIAH", "C"), arrival: nil)
        XCTAssertTrue(model.departureATISAvailable)
        XCTAssertTrue(model.atisButtonVisible)
        XCTAssertFalse(model.currentATISIsArrival)
        XCTAssertEqual(model.currentATISCode, "C")
        XCTAssertEqual(model.atisButtonSubtitle, "Info C")
        XCTAssertEqual(model.atisAirport, "KIAH")
    }

    func testArrivalATISAvailableWithin100NM() {
        let model = makeModel()
        model.setATISReportsForTesting(departure: nil, arrival: report("KMSP", "D"))
        // Airborne ~45 NM north of KMSP → within the 100 NM arrival window.
        var near = AircraftState()
        near.latitude = 45.6; near.longitude = -93.22
        near.altitudeMSL = 12000; near.onGround = false
        near.groundSpeed = 350; near.verticalSpeed = -1200; near.heading = 360
        model.ingestStateForTesting(near)

        XCTAssertTrue(model.withinArrivalATISRange)
        XCTAssertTrue(model.arrivalATISAvailable)
        XCTAssertTrue(model.currentATISIsArrival)
        XCTAssertEqual(model.currentATISCode, "D")
    }

    func testArrivalATISHiddenBeyond100NM() {
        let model = makeModel()
        model.setATISReportsForTesting(departure: nil, arrival: report("KMSP", "D"))
        // Airborne over KIAH (~900 NM from KMSP) → out of range.
        var far = AircraftState()
        far.latitude = 29.98; far.longitude = -95.34
        far.altitudeMSL = 35000; far.onGround = false
        far.groundSpeed = 450; far.heading = 10
        model.ingestStateForTesting(far)

        XCTAssertFalse(model.withinArrivalATISRange)
        XCTAssertFalse(model.arrivalATISAvailable)
    }

    // MARK: - Appending the information code

    func testAppendingATISInfoHelper() {
        let model = makeModel()
        let tx = ATCTransmission(sender: .pilot, facility: .ground,
                                 displayText: "Ground, United 598, request taxi.",
                                 spokenText: "Ground, United five niner eight, request taxi.")
        let out = model.appendingATISInfo(tx, word: "Alpha")
        XCTAssertEqual(out.displayText, "Ground, United 598, request taxi, information Alpha.")
        XCTAssertTrue(out.spokenText.hasSuffix(", information Alpha."))
        // A nil word leaves the transmission untouched.
        XCTAssertEqual(model.appendingATISInfo(tx, word: nil).displayText, tx.displayText)
    }

    func testTaxiRequestAppendsReceivedInformationOnce() {
        let model = makeModel()
        model.setReportedATISForTesting(departure: "A", arrival: nil)

        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()   // Ramp → Ground hand-off (no info)
        model.requestTaxi()                            // Ground taxi request (info appended)

        let taxiLines = model.transcript.filter {
            $0.sender == .pilot && $0.displayText.contains("request taxi")
        }
        XCTAssertEqual(taxiLines.count, 1)
        XCTAssertTrue(taxiLines.first?.displayText.contains("request taxi, information Alpha") ?? false,
                      taxiLines.first?.displayText ?? "no taxi line")
    }

    func testTaxiRequestOmitsInformationWhenNoATIS() {
        let model = makeModel()   // no ATIS received

        model.requestClearance();   model.readBack()
        model.requestPushback();    model.readBack()
        model.requestEngineStart(); model.readBack()
        model.requestTaxi();        model.readBack()
        model.requestTaxi()

        let taxi = model.transcript.first {
            $0.sender == .pilot && $0.displayText.contains("request taxi")
        }
        XCTAssertNotNil(taxi)
        XCTAssertFalse(taxi!.displayText.contains("information"), taxi!.displayText)
    }

    func testApproachCheckInAppendsArrivalInformationOnce() {
        let model = makeModel()
        model.setReportedATISForTesting(departure: nil, arrival: "B")

        // Pilot-driven pre-departure, then tune each controller by hand to reach the
        // Approach check-in on arrival.
        model.requestClearance();        model.readBack()
        model.requestPushback();         model.readBack()
        model.requestEngineStart();      model.readBack()
        model.requestTaxi();             model.readBack()
        model.requestTaxi();             model.readBack()
        model.reportReadyForDeparture(); model.readBack()
        model.tuneTo(.tower);     model.requestHandoff(); model.readBack()
        model.tuneTo(.departure); model.requestHandoff(); model.readBack()
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()
        model.tuneTo(.center);    model.requestHandoff()
        model.tuneTo(.center);    model.requestHandoff(); model.readBack()
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // check in with Approach
        model.tuneTo(.approach);  model.requestHandoff(); model.readBack()   // cleared approach (no repeat)

        let approachInfoLines = model.transcript.filter {
            $0.sender == .pilot && $0.displayText.contains("information Bravo")
        }
        XCTAssertEqual(approachInfoLines.count, 1, "arrival ATIS code should be reported to Approach exactly once")
        XCTAssertEqual(approachInfoLines.first?.facility, .approach)
    }
}
