import XCTest
@testable import IFATCCompanion

/// Tests for the FAA/ICAO phraseology packs and user phraseology profiles.
final class PhraseologyPackTests: XCTestCase {

    // MARK: - ICAO digit + phrase differences

    func testICAODigitWords() {
        XCTAssertEqual(Phonetic.spellDigits("345", icao: true), "tree fower fife")
        // FAA default unchanged.
        XCTAssertEqual(Phonetic.spellDigits("345"), "three four five")
    }

    func testICAOFrequencyUsesDecimal() {
        XCTAssertEqual(Phonetic.frequency(118.300, icao: true), "one one eight decimal tree")
        XCTAssertEqual(Phonetic.frequency(118.300), "one one eight point three")
    }

    func testICAOAltitudeAndFlightLevel() {
        XCTAssertEqual(Phonetic.altitude(37000, icao: true), "flight level tree seven zero")
        XCTAssertEqual(Phonetic.altitude(5000, icao: true), "fife thousand")
    }

    func testICAOAltimeterIsQNHInHectopascals() {
        // 29.92 inHg ≈ 1013 hPa.
        XCTAssertEqual(Phonetic.altimeterSetting(inHg: 29.92, icao: true), "QNH one zero one tree")
        XCTAssertEqual(Phonetic.altimeterSetting(inHg: 30.12, icao: false), "altimeter three zero one two")
    }

    func testEngineICAOClearanceUsesDecimalSeparator() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .icao)
        let cs = engine.callsign(airline: "Speedbird", flightNumber: "12", fallback: "")
        let tx = engine.clearance(cs: cs, destination: "KMSP", cruise: 37000, sid: "",
                                  initialAlt: 5000, departureFreq: 124.300, squawk: "4271")
        XCTAssertTrue(tx.spokenText.contains("decimal"))
        XCTAssertFalse(tx.spokenText.contains(" point "))
    }

    func testICAOTaxiUsesHoldingPoint() {
        let engine = PhraseologyEngine(digitStyle: .individual, mode: .icao)
        let cs = engine.callsign(airline: "Speedbird", flightNumber: "12", fallback: "")
        let tx = engine.taxiToRunway(cs: cs, runway: "27", via: "A", crossing: nil)
        XCTAssertTrue(tx.displayText.contains("holding point"))
    }

    // MARK: - User profiles

    func testProfileTemplateOverridesTakeoffCall() {
        var profile = PhraseologyProfile(name: "Test")
        profile.templates[PhraseologyTemplateKey.takeoff.rawValue] = PhraseologyTemplate(
            display: "{callsign}, runway {runway}, you are clear to go.",
            spoken: "{callsign}, runway {runway}, you are clear to go.")
        var engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        engine.profile = profile
        let cs = engine.callsign(airline: "United", flightNumber: "1", fallback: "")
        let tx = engine.clearedForTakeoff(cs: cs, runway: "17R", windDir: 180, windSpeed: 8)
        XCTAssertEqual(tx.displayText, "United 1, runway 17R, you are clear to go.")
    }

    func testProfileAirlineCallNameOverridesSpokenAirline() {
        var profile = PhraseologyProfile(name: "Test")
        profile.airlineCallSets["DLH"] = "Lufthansa"
        var engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        engine.profile = profile
        XCTAssertEqual(engine.spokenCallsign(airline: "DLH", flightNumber: "400"),
                       "Lufthansa four zero zero")
    }

    @MainActor
    func testProfileRoundTripsThroughJSON() {
        let store = PhraseologyProfileStore(defaults: UserDefaults(suiteName: "test.profiles.\(UUID().uuidString)")!)
        var profile = PhraseologyProfile(name: "Roundtrip")
        profile.airlineCallSets["BAW"] = "Speedbird"
        let json = store.exportJSON(profile)
        let imported = store.importJSON(json)
        XCTAssertNotNil(imported)
        XCTAssertEqual(imported?.airlineCallSets["BAW"], "Speedbird")
    }
}
