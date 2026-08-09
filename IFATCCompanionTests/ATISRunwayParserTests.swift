import XCTest
@testable import IFATCCompanion

/// Tests the deterministic extraction of active departure / arrival runways from D-ATIS text,
/// used to ground the background chatter in the runways actually in use at a field.
final class ATISRunwayParserTests: XCTestCase {

    private func atis(_ airport: String, _ parts: [(AirportATIS.Kind, String)]) -> AirportATIS {
        AirportATIS(airport: airport,
                    parts: parts.map { AirportATIS.Part(kind: $0.0, letter: "A", text: $0.1) },
                    fetchedAt: Date(timeIntervalSince1970: 0))
    }

    // MARK: - Combined ATIS

    func testSeparateLandingAndDepartingRunways() {
        let r = ATISRunwayParser.parse("ILS RWY 24R APCH IN USE. DEPG RWY 25R.", kind: .combined)
        XCTAssertEqual(r.arrivals, ["24R"])
        XCTAssertEqual(r.departures, ["25R"])
    }

    func testLandingAndDepartingSameRunwayCountsAsBoth() {
        let r = ATISRunwayParser.parse("LDG AND DEPG RWY 13.", kind: .combined)
        XCTAssertEqual(r.departures, ["13"])
        XCTAssertEqual(r.arrivals, ["13"])
    }

    func testMultipleRunwaysPerOperation() {
        let text = "DEPG RWYS 24L AND 25R. ILS RWY 24R AND ILS RWY 25L APCHS IN USE."
        let r = ATISRunwayParser.parse(text, kind: .combined)
        XCTAssertEqual(Set(r.departures), ["24L", "25R"])
        XCTAssertEqual(Set(r.arrivals), ["24R", "25L"])
    }

    func testCommaSeparatedRunwayList() {
        let r = ATISRunwayParser.parse("LANDING AND DEPARTING RWYS 27L, 27R.", kind: .combined)
        XCTAssertEqual(Set(r.departures), ["27L", "27R"])
        XCTAssertEqual(Set(r.arrivals), ["27L", "27R"])
    }

    func testTwoClausesInOneSentence() {
        // No period between the clauses — the keyword still re-scopes each runway.
        let r = ATISRunwayParser.parse("LDG RWY 4R DEPG RWY 4L", kind: .combined)
        XCTAssertEqual(r.arrivals, ["4R"])
        XCTAssertEqual(r.departures, ["4L"])
    }

    // MARK: - Single-operation parts default by kind

    func testDepartureOnlyPartDefaultsToDepartures() {
        let r = ATISRunwayParser.parse("RWY 22 IN USE FOR DEPARTURE.", kind: .departure)
        XCTAssertEqual(r.departures, ["22"])
        XCTAssertTrue(r.arrivals.isEmpty)
    }

    func testArrivalOnlyPartDefaultsToArrivals() {
        let r = ATISRunwayParser.parse("EXPECT ILS RWY 27L. RWY 27L IN USE.", kind: .arrival)
        XCTAssertEqual(r.arrivals, ["27L"])
        XCTAssertTrue(r.departures.isEmpty)
    }

    func testCombinedBareRunwayCountsAsBoth() {
        let r = ATISRunwayParser.parse("RWY 4 IN USE.", kind: .combined)
        XCTAssertEqual(r.departures, ["4"])
        XCTAssertEqual(r.arrivals, ["4"])
    }

    // MARK: - Robustness

    func testNoRunwaysYieldsEmpty() {
        let r = ATISRunwayParser.parse("WIND 25012KT. VISIBILITY 10SM. ALTIMETER A2992.", kind: .combined)
        XCTAssertTrue(r.isEmpty)
    }

    func testRVRIsNotMistakenForARunwayInUse() {
        // An RVR group ("R28L/2400FT") is not preceded by a runway keyword, so it is ignored.
        let r = ATISRunwayParser.parse("R28L/2400FT. INFO BRAVO.", kind: .combined)
        XCTAssertTrue(r.isEmpty)
    }

    func testRunwayKeywordGluedToDesignatorIsParsed() {
        // Some feeds publish the keyword flush against its designator ("RY8R"). It scans as
        // the same runway the spaced form does, so the flight still sees it as in use.
        let r = ATISRunwayParser.parse("ILS RY8R APCH IN USE. DEPG RWY26L.", kind: .combined)
        XCTAssertEqual(r.arrivals, ["8R"])
        XCTAssertEqual(r.departures, ["26L"])
        // The plural keyword and a leading-zero designator split the same way.
        let r2 = ATISRunwayParser.parse("LDG AND DEPG RWYS04L AND 04R.", kind: .combined)
        XCTAssertEqual(Set(r2.arrivals), ["4L", "4R"])
        XCTAssertEqual(Set(r2.departures), ["4L", "4R"])
    }

    func testFlushSplitOnlyAppliesToRunwayKeywords() {
        // Only a token whose letters are exactly a runway keyword is split. A word that merely
        // ends in those letters, or a keyword with a tail that isn't a runway ident, is left
        // alone — neither may invent an active runway.
        XCTAssertTrue(ATISRunwayParser.parse("ENTRY8R VIA TWY A.", kind: .combined).isEmpty)
        XCTAssertTrue(ATISRunwayParser.parse("RWY40 IN USE.", kind: .combined).isEmpty)
    }

    func testCanonicalDropsLeadingZeroAndUppercases() {
        XCTAssertEqual(ATISRunwayParser.canonical("04L"), "4L")
        XCTAssertEqual(ATISRunwayParser.canonical("09"), "9")
        XCTAssertEqual(ATISRunwayParser.canonical("16l"), "16L")
        XCTAssertEqual(ATISRunwayParser.canonical("36"), "36")
    }

    // MARK: - NAVAID-outage / condition-report runways are not "in use"

    func testOutOfServiceAndConditionRunwaysAreNotActive() {
        // The O'Hare shape: only ILS 10C (arr) and 9C (dep) are in use; the long lists of
        // "RWY x LOC/GS/IM/PAPI OTS" and "RWY x COND CODE 5 5 5 …" must not become active
        // runways just because they're named without an arrival/departure keyword.
        let text = "ARR EXP VECTORS ILS RWY 10C APCH. DEPS EXP RWYS 9C. "
            + "RWY 22R LOC OTS, RWY 28L GS OTS, RWY 9L IM OTS, RWY 9C IM OTS, RWY 9L PAPI OTS, "
            + "RWY 27R PAPI OTS. RWY 22L, COND CODE, 5 5 5 AT, 1630Z, RWY 28R, COND CODE, 5 5 5 "
            + "AT, 1630Z."
        let r = ATISRunwayParser.parse(text, kind: .combined)
        XCTAssertEqual(r.arrivals, ["10C"])
        XCTAssertEqual(r.departures, ["9C"])
    }

    func testClosedRunwayIsNotActive() {
        // A closed runway is named but not in use; only the keyworded runway stays active.
        let r = ATISRunwayParser.parse("LDG AND DEPG RWY 13. RWY 31 CLSD.", kind: .combined)
        XCTAssertEqual(r.arrivals, ["13"])
        XCTAssertEqual(r.departures, ["13"])
    }

    func testKeywordedRunwayStillCountsEvenWithLaterOutage() {
        // An explicit arrival/departure keyword is trusted; a following outage clause for a
        // different runway is suppressed on its own, not applied to the keyworded group.
        let r = ATISRunwayParser.parse("DEPG RWY 9C. RWY 22R LOC OTS.", kind: .combined)
        XCTAssertEqual(r.departures, ["9C"])
        XCTAssertTrue(r.arrivals.isEmpty)
    }

    // MARK: - Whole report (multiple parts)

    func testActiveRunwaysAcrossSeparateArrivalAndDepartureParts() {
        let report = atis("KLAX", [
            (.arrival, "ILS RWY 24R AND ILS RWY 25L APCHS IN USE."),
            (.departure, "DEPG RWYS 24L AND 25R.")
        ])
        let r = ATISRunwayParser.activeRunways(report)
        XCTAssertEqual(Set(r.arrivals), ["24R", "25L"])
        XCTAssertEqual(Set(r.departures), ["24L", "25R"])
    }
}
