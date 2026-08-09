import XCTest
@testable import IFATCCompanion

/// The "did I already say this, and was it acknowledged?" check. A controller-initiated call
/// that would only repeat an acknowledged one is held off the radio — the drawn deviation
/// line can carry the same heading across consecutive vertices, and each vertex fires its own
/// turn, so the same instruction was going out three times while the pilot flew it.
final class AcknowledgedRepeatTests: XCTestCase {

    private let vector = "United 1678, fly heading 082, vectors around precipitation."

    private func atc(_ text: String, facility: ATCFacility = .center) -> ATCTransmission {
        ATCTransmission(sender: .atc, facility: facility, displayText: text)
    }

    private func pilot(_ text: String, facility: ATCFacility = .center) -> ATCTransmission {
        ATCTransmission(sender: .pilot, facility: facility, displayText: text)
    }

    func testSameCallAfterTheReadBackIsARepeat() {
        let transcript = [atc(vector), pilot("Heading 082, United 1678.")]
        XCTAssertTrue(ATCTransmission.isAcknowledgedRepeat(atc(vector), in: transcript))
    }

    func testUnacknowledgedCallIsNotARepeat() {
        // Nothing from the pilot since the call, so it may simply not have been heard —
        // re-issuing it is exactly what should happen.
        XCTAssertFalse(ATCTransmission.isAcknowledgedRepeat(atc(vector), in: [atc(vector)]))
    }

    func testDifferentWordsOrFacilityAreNotRepeats() {
        let acknowledged = [atc(vector), pilot("Heading 082, United 1678.")]
        XCTAssertFalse(ATCTransmission.isAcknowledgedRepeat(
            atc("United 1678, fly heading 062, vectors around precipitation."), in: acknowledged),
            "a different heading is a different instruction")
        XCTAssertFalse(ATCTransmission.isAcknowledgedRepeat(atc(vector, facility: .approach), in: acknowledged),
                       "the same words from the next controller are that controller's own call")
    }

    func testOnlyTheMostRecentControllerCallCounts() {
        // The same words appear earlier, but the controller has said something else since —
        // the instruction is worth stating again rather than left to an older acknowledgement.
        let transcript = [atc(vector),
                          pilot("Heading 082, United 1678."),
                          atc("United 1678, descend and maintain 24,000."),
                          pilot("Descend and maintain 24,000, United 1678.")]
        XCTAssertFalse(ATCTransmission.isAcknowledgedRepeat(atc(vector), in: transcript))
    }

    func testEmptyTranscriptIsNeverARepeat() {
        XCTAssertFalse(ATCTransmission.isAcknowledgedRepeat(atc(vector), in: []))
    }
}
