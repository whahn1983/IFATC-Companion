import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Automatic gate assignment: the optional (off-by-default) feature that fills a **blank**
/// Dep Gate / Arr Gate with a real stand from the airport's OpenStreetMap extract.
///
/// Covers the three things that make it safe and useful: what the OSM stand tags are read to
/// mean, how a stand is chosen from them (airline, aircraft size, cargo, terminal vs. remote,
/// random among equals), and the "only when the pilot left it blank" rule that decides
/// whether the app is allowed to write the field at all.
@MainActor
final class GateAssignmentTests: XCTestCase {

    private let ref = CLLocationCoordinate2D(latitude: 29.9844, longitude: -95.3414)

    // A deterministic generator, so a test that asserts on a random pick asserts on a fixed
    // one. Any fixed sequence will do — the point is repeatability, not statistical quality.
    private struct SeededGenerator: RandomNumberGenerator {
        var state: UInt64
        init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
        mutating func next() -> UInt64 {
            state ^= state << 13
            state ^= state >> 7
            state ^= state << 17
            return state
        }
    }

    private func stand(_ name: String, kind: SurfaceParking.Kind = .gate,
                       tags: [String: String] = [:],
                       dLat: Double = 0.001, dLon: Double = 0.001) -> SurfaceParking {
        var allTags = tags
        allTags["aeroway"] = kind == .gate ? "gate" : "parking_position"
        if allTags["ref"] == nil { allTags["ref"] = name }
        return SurfaceParking(osmID: "node/\(name)", tags: allTags, kind: kind, name: name,
                              coordinate: GeoCoordinate(latitude: ref.latitude + dLat,
                                                        longitude: ref.longitude + dLon))
    }

    /// A surface carrying only the stands under test (the rest of the field is irrelevant to
    /// the assignment, which reads `parkingPositions` alone).
    private func surface(_ stands: [SurfaceParking], icao: String = "KIAH") -> AirportSurfaceModel {
        var model = MockAirportSurface.model(icao: icao, reference: ref,
                                             primaryRunwayIdent: "15L", gate: "A1")
        model.parkingPositions = stands
        return model
    }

    private func assign(_ stands: [SurfaceParking], flight: GateAssigner.FlightContext,
                        role: GateRole = .departure, seed: UInt64 = 42) -> GateAssigner.Assignment? {
        var generator = SeededGenerator(seed: seed)
        return GateAssigner.assign(surface: surface(stands), flight: flight, role: role,
                                   using: &generator)
    }

    // MARK: - Reading the OSM stand tags

    func testAircraftTypeTagGivesTheStandsSizeClass() {
        XCTAssertEqual(StandProfile.from(tags: ["aircraft:type": "A320"], standName: "B12").maxClass,
                       AircraftSizeClass.medium, "an airframe designator sizes the stand")
        XCTAssertEqual(StandProfile.from(tags: ["aircraft:type": "B738;B77W"], standName: "B12").maxClass,
                       AircraftSizeClass.heavy, "a multi-value tag takes the largest aircraft named")
        XCTAssertEqual(StandProfile.from(tags: ["aircraft:type": "wide_body"], standName: "B12").maxClass,
                       AircraftSizeClass.large, "a size band is understood as well as an airframe")
        XCTAssertEqual(StandProfile.from(tags: ["aircraft:type": "code_c"], standName: "B12").maxClass,
                       AircraftSizeClass.medium, "an ICAO aerodrome reference code is understood")
        XCTAssertNil(StandProfile.from(tags: ["aircraft:type": "whatever"], standName: "B12").maxClass,
                     "an unrecognised value stays unknown rather than inventing a size")
        XCTAssertNil(StandProfile.from(tags: [:], standName: "B12").maxClass,
                     "an untagged stand has no size")
    }

    func testStandSizeDecidesWhatFitsAndHowSnugly() {
        let narrow = StandProfile.from(tags: ["aircraft:type": "A320"], standName: "B12")
        XCTAssertTrue(narrow.accepts(.medium))
        XCTAssertTrue(narrow.accepts(.small), "a smaller aircraft fits a bigger stand")
        XCTAssertFalse(narrow.accepts(.heavy), "a 777 does not fit an A320 stand")
        XCTAssertEqual(narrow.fitGap(for: .medium), 0, "an exact match is a snug fit")
        XCTAssertEqual(narrow.fitGap(for: .light), 2, "a light aircraft is two classes small for it")

        let untagged = StandProfile.from(tags: [:], standName: "B12")
        XCTAssertTrue(untagged.accepts(.heavy), "an untagged stand never disqualifies an aircraft")
        XCTAssertEqual(untagged.fitGap(for: .heavy), 0, "there is nothing to grade an untagged stand on")
    }

    func testHelicopterAndAccessAndCargoAndServiceTags() {
        XCTAssertTrue(StandProfile.from(tags: ["aircraft:type": "helicopter"], standName: "H1").helicopterOnly)
        XCTAssertFalse(StandProfile.from(tags: ["aircraft:type": "helicopter;A320"], standName: "H1").helicopterOnly,
                       "a stand that also takes fixed-wing aircraft is not helicopter-only")
        XCTAssertTrue(StandProfile.from(tags: ["access": "private"], standName: "X1").restricted)
        XCTAssertTrue(StandProfile.from(tags: ["access": "no"], standName: "X1").restricted)
        XCTAssertFalse(StandProfile.from(tags: ["access": "yes"], standName: "X1").restricted)
        XCTAssertTrue(StandProfile.from(tags: ["operator": "Lufthansa Cargo"], standName: "C1").cargo,
                      "a freight operator marks a cargo stand")
        XCTAssertTrue(StandProfile.from(tags: ["name": "Cargo stand 4"], standName: "C4").cargo)
        XCTAssertTrue(StandProfile.from(tags: ["description": "de-icing pad"], standName: "D1").servicePosition)
        XCTAssertTrue(StandProfile.from(tags: ["name": "Maintenance apron"], standName: "M1").servicePosition)
    }

    func testAnAirportAuthorityNameIsNeverReadAsAServicePosition() {
        // The operator text is deliberately not searched for purpose words: an authority or
        // city name that happens to contain one must not disqualify a real stand.
        let profile = StandProfile.from(tags: ["operator": "Aeroporto di Firenze"], standName: "201")
        XCTAssertFalse(profile.servicePosition,
                       "an operator name is not a purpose tag — the stand stays assignable")
    }

    // MARK: - Choosing a stand

    func testUnnamedAndServiceStandsAreNeverAssigned() {
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        XCTAssertNil(assign([], flight: flight), "a field with no stands assigns nothing")
        XCTAssertNil(assign([stand("", tags: ["ref": ""])], flight: flight),
                     "a stand with no identifier can't be named in a clearance")
        XCTAssertNil(assign([stand("D1", tags: ["name": "De-icing pad"])], flight: flight),
                     "no flight is sent to park on the de-icing pad")
    }

    func testTheAirlinesOwnStandWins() {
        let stands = [
            stand("A1", tags: ["operator": "Delta Air Lines"]),
            stand("B12", tags: ["operator": "United Airlines"]),
            stand("C3", tags: ["operator": "American Airlines"])
        ]
        let flight = GateAssigner.FlightContext(callsign: "UAL598", airline: "United",
                                               aircraftName: "Boeing 737-800")
        XCTAssertEqual(assign(stands, flight: flight)?.gate, "B12")
        XCTAssertEqual(assign(stands, flight: flight)?.matchedOperator, true)
    }

    func testABrandThatDiffersFromTheTelephonyNameStillMatches() {
        // "Speedbird" is British Airways on the radio and nowhere in OSM, so the brand table
        // is what makes this match.
        let stands = [stand("A1", tags: ["operator": "Lufthansa"]),
                      stand("T5-501", tags: ["operator": "British Airways"])]
        let flight = GateAssigner.FlightContext(callsign: "BAW117", airline: "Speedbird",
                                               aircraftName: "Boeing 777-300ER")
        XCTAssertEqual(assign(stands, flight: flight)?.gate, "T5-501")
    }

    func testTheSnuggestStandThatFitsIsPreferred() {
        let stands = [
            stand("H1", tags: ["aircraft:type": "B77W"]),
            stand("N4", tags: ["aircraft:type": "A320"]),
            stand("U9")
        ]
        let narrowbody = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Airbus A320")
        XCTAssertEqual(assign(stands, flight: narrowbody)?.gate, "N4",
                       "a narrowbody takes the narrowbody stand, not the widebody one")

        let widebody = GateAssigner.FlightContext(callsign: "UAL1", aircraftName: "Boeing 777-300ER")
        XCTAssertEqual(assign(stands, flight: widebody)?.gate, "H1",
                       "a widebody takes the stand that actually fits it")
    }

    func testAStandTooSmallIsUsedOnlyWhenNothingElseIs() {
        let heavy = GateAssigner.FlightContext(callsign: "UAL1", aircraftName: "Boeing 777-300ER")
        let onlySmall = [stand("R2", tags: ["aircraft:type": "CRJ900"])]
        XCTAssertEqual(assign(onlySmall, flight: heavy)?.gate, "R2",
                       "an undersized stand still beats leaving the pilot with no gate")

        let withUntagged = onlySmall + [stand("U9")]
        XCTAssertEqual(assign(withUntagged, flight: heavy)?.gate, "U9",
                       "but an untagged stand that might fit is preferred over one that can't")
    }

    func testHelicopterPadsAndRestrictedStandsAreLastResorts() {
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        XCTAssertEqual(assign([stand("H1", tags: ["aircraft:type": "helicopter"]), stand("B2")],
                              flight: flight)?.gate, "B2")
        XCTAssertEqual(assign([stand("P1", tags: ["access": "private"]), stand("B2")],
                              flight: flight)?.gate, "B2")
    }

    func testCargoAndPassengerStandsAreNotInterchangeable() {
        let stands = [stand("PAX3"), stand("CGO1", tags: ["operator": "FedEx Cargo"])]
        let airliner = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        XCTAssertEqual(assign(stands, flight: airliner)?.gate, "PAX3",
                       "a passenger flight is not sent to the freight ramp")

        let freighter = GateAssigner.FlightContext(callsign: "FDX1234", aircraftName: "Boeing 777F")
        XCTAssertEqual(assign(stands, flight: freighter)?.gate, "CGO1",
                       "and a freighter is not sent to a passenger gate")
    }

    func testTerminalGatesForAirlinersAndRemoteStandsForLightAircraft() {
        let stands = [stand("B12", kind: .gate), stand("GA7", kind: .parkingPosition)]
        let airliner = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        XCTAssertEqual(assign(stands, flight: airliner)?.gate, "B12")

        let ga = GateAssigner.FlightContext(callsign: "N123AB", aircraftName: "Cessna 172")
        XCTAssertEqual(assign(stands, flight: ga)?.gate, "GA7",
                       "a light single parks on the ramp, not at a jet bridge")
    }

    func testEquallySuitableStandsAreDrawnAtRandomSoTheGateVaries() {
        let stands = (1...12).map { stand("B\($0)") }
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        var picks = Set<String>()
        for seed in UInt64(1)...40 {
            guard let assignment = assign(stands, flight: flight, seed: seed) else {
                return XCTFail("a field of twelve plain stands must always assign one")
            }
            XCTAssertEqual(assignment.tiedCandidates, 12, "all twelve stands are equally suitable")
            XCTAssertEqual(assignment.totalCandidates, 12)
            picks.insert(assignment.gate)
        }
        XCTAssertGreaterThan(picks.count, 1,
                             "the same field must not hand out the same stand every flight")
    }

    func testAssignmentReportsWhereItCameFrom() {
        let stands = [stand("B12", tags: ["operator": "United Airlines", "aircraft:type": "B738"])]
        let flight = GateAssigner.FlightContext(callsign: "UAL598", airline: "United",
                                               aircraftName: "Boeing 737-800")
        guard let assignment = assign(stands, flight: flight) else {
            return XCTFail("the tagged United stand must be assigned")
        }
        XCTAssertEqual(assignment.gate, "B12")
        XCTAssertEqual(assignment.osmID, "node/B12")
        XCTAssertTrue(assignment.matchedOperator)
        XCTAssertTrue(assignment.matchedAircraftType)
        XCTAssertTrue(assignment.reason.contains("operator match"),
                      "the log line explains the choice: \(assignment.reason)")
    }

    // MARK: - "Only when the pilot left it blank"

    func testStampRoundTrips() {
        let stamp = AutoGateStamp(icao: "kiah", gate: " C24 ")
        XCTAssertEqual(stamp.encoded, "KIAH:C24")
        XCTAssertEqual(AutoGateStamp(encoded: "KIAH:C24"), AutoGateStamp(icao: "KIAH", gate: "C24"))
        XCTAssertNil(AutoGateStamp(encoded: ""))
        XCTAssertNil(AutoGateStamp(encoded: "KIAH"))
        XCTAssertNil(AutoGateStamp(encoded: ":C24"))
        XCTAssertEqual(AutoGateStamp(icao: "KIAH", gate: "").encoded, "",
                       "there is nothing to remember without a gate")
    }

    func testABlankFieldMayBeAssignedAndATypedGateMayNot() {
        XCTAssertTrue(GateAssigner.mayAssign(current: "", stamp: "", icao: "KIAH"))
        XCTAssertTrue(GateAssigner.mayAssign(current: "   ", stamp: "", icao: "KIAH"),
                      "whitespace is a blank field")
        XCTAssertFalse(GateAssigner.mayAssign(current: "C24", stamp: "", icao: "KIAH"),
                       "a gate the pilot typed is never overwritten")
        XCTAssertFalse(GateAssigner.mayAssign(current: "E7", stamp: "KIAH:C24", icao: "KIAH"),
                       "a gate the pilot typed over an automatic one is theirs now")
    }

    func testTheAppReplacesItsOwnGateOnlyWhenTheAirportChanges() {
        XCTAssertFalse(GateAssigner.mayAssign(current: "C24", stamp: "KIAH:C24", icao: "KIAH"),
                       "already assigned for this field — it must not re-roll every tick")
        XCTAssertTrue(GateAssigner.mayAssign(current: "C24", stamp: "KIAH:C24", icao: "KMSP"),
                      "the last flight's automatic gate is stale at a new airport")
        XCTAssertTrue(GateAssigner.mayAssign(current: "c24", stamp: "KIAH:C24", icao: "KMSP"),
                      "the match ignores case")
    }

    func testAppAssignedRecognisesOnlyItsOwnValue() {
        XCTAssertTrue(GateAssigner.isAppAssigned(current: "C24", stamp: "KIAH:C24"))
        XCTAssertFalse(GateAssigner.isAppAssigned(current: "E7", stamp: "KIAH:C24"))
        XCTAssertFalse(GateAssigner.isAppAssigned(current: "C24", stamp: ""))
        XCTAssertFalse(GateAssigner.isAppAssigned(current: "", stamp: "KIAH:C24"))
    }
}
