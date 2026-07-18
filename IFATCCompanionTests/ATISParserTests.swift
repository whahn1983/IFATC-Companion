import XCTest
@testable import IFATCCompanion

/// Tests for the FAA D-ATIS JSON parser (`datis.clowd.io` payload → `AirportATIS`).
final class ATISParserTests: XCTestCase {

    private func data(_ s: String) -> Data { Data(s.utf8) }

    // MARK: - Separate arrival + departure

    func testParsesSeparateArrivalAndDeparture() {
        let json = """
        [
          {"airport":"KLAX","type":"dep","code":"R",
           "datis":"LOS ANGELES INTL DEP INFO ROMEO. 1953Z. DEPG RWY 24L, 25R. ADVS YOU HAVE INFO ROMEO."},
          {"airport":"KLAX","type":"arr","code":"Q",
           "datis":"LOS ANGELES INTL ARR INFO QUEBEC. 1953Z. ILS RWY 24R, 25L APCHS IN USE. ADVS YOU HAVE INFO QUEBEC."}
        ]
        """
        let atis = ATISParser.parse(data(json), airport: "KLAX")
        XCTAssertNotNil(atis)
        XCTAssertEqual(atis?.airport, "KLAX")
        XCTAssertEqual(atis?.parts.count, 2)
        // Arrival ATIS drives the arrival letter, departure ATIS the departure letter.
        XCTAssertEqual(atis?.letter(arrival: true), "Q")
        XCTAssertEqual(atis?.letter(arrival: false), "R")
        XCTAssertEqual(atis?.part(arrival: true)?.kind, .arrival)
        XCTAssertEqual(atis?.part(arrival: false)?.kind, .departure)
    }

    // MARK: - Combined

    func testParsesCombinedATIS() {
        let json = """
        [{"airport":"KPHX","type":"combined","code":"A",
          "datis":"PHOENIX SKY HARBOR INTL INFO ALPHA. 2352Z. ADVISE YOU HAVE INFORMATION ALPHA."}]
        """
        let atis = ATISParser.parse(data(json), airport: "KPHX")
        // A combined ATIS resolves for both arrival and departure with the same letter.
        XCTAssertEqual(atis?.letter(arrival: true), "A")
        XCTAssertEqual(atis?.letter(arrival: false), "A")
        XCTAssertEqual(atis?.part(arrival: true)?.kind, .combined)
    }

    // MARK: - Letter recovery from text when no code field

    func testRecoversLetterFromTextWhenCodeMissing() {
        let json = """
        [{"airport":"KSEA","type":"combined",
          "datis":"SEATTLE TACOMA INTL INFORMATION BRAVO. 0053Z. ADVISE YOU HAVE INFORMATION BRAVO."}]
        """
        let atis = ATISParser.parse(data(json), airport: "KSEA")
        XCTAssertEqual(atis?.letter(arrival: true), "B")
    }

    func testInfoLetterAcceptsPhoneticCodeWord() {
        XCTAssertEqual(ATISParser.infoLetter(code: "ALPHA", text: ""), "A")
        XCTAssertEqual(ATISParser.infoLetter(code: "Q", text: ""), "Q")
        XCTAssertEqual(ATISParser.infoLetter(code: nil,
                                             text: "… ADVISE YOU HAVE INFORMATION LIMA."), "L")
    }

    // MARK: - Missing / error responses degrade to nil

    func testErrorObjectYieldsNil() {
        XCTAssertNil(ATISParser.parse(data("{\"error\":\"not found\"}"), airport: "XXXX"))
    }

    func testEmptyArrayYieldsNil() {
        XCTAssertNil(ATISParser.parse(data("[]"), airport: "KLAX"))
    }

    func testMalformedJSONYieldsNil() {
        XCTAssertNil(ATISParser.parse(data("not json"), airport: "KLAX"))
    }

    func testEmptyDatisTextYieldsNil() {
        let json = """
        [{"airport":"KLAX","type":"combined","code":"A","datis":"   "}]
        """
        XCTAssertNil(ATISParser.parse(data(json), airport: "KLAX"))
    }

    // MARK: - Single letter access is validated

    func testLetterRejectsNonSingleLetterCode() {
        // A part whose code couldn't be resolved has an empty letter → nil.
        let part = AirportATIS.Part(kind: .combined, letter: "", text: "x")
        let atis = AirportATIS(airport: "KLAX", parts: [part], fetchedAt: Date())
        XCTAssertNil(atis.letter(arrival: true))
    }
}
