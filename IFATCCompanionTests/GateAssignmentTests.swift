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

    // MARK: - The gate the aircraft is parked on

    /// A coordinate `metersEast` east of the field reference, so a test can park the aircraft a
    /// known distance from a stand built with the same offset convention.
    private func offset(dLat: Double, dLon: Double) -> GeoCoordinate {
        GeoCoordinate(latitude: ref.latitude + dLat, longitude: ref.longitude + dLon)
    }

    func testAStandTheAircraftIsParkedOnBeatsEverySignal() {
        // B12 is the airline's own stand *and* sized for the aircraft — it would win on the
        // tags alone. The aircraft is sitting on F3, so F3 is the gate.
        let stands = [
            stand("B12", tags: ["operator": "United Airlines", "aircraft:type": "B738"],
                  dLat: 0.001, dLon: 0.001),
            stand("F3", dLat: 0.010, dLon: 0.010)
        ]
        let flight = GateAssigner.FlightContext(callsign: "UAL598", airline: "United",
                                               aircraftName: "Boeing 737-800",
                                               parkedPosition: offset(dLat: 0.010, dLon: 0.010))
        guard let assignment = assign(stands, flight: flight) else {
            return XCTFail("a parked aircraft on a mapped stand must be assigned that stand")
        }
        XCTAssertEqual(assignment.gate, "F3")
        XCTAssertTrue(assignment.fromAircraftPosition, "it was read, not chosen")
        XCTAssertTrue(assignment.reason.contains("parked on it"),
                      "the log says where it came from: \(assignment.reason)")
    }

    func testTheNearestStandWinsWhenSeveralAreInRange() {
        let stands = [stand("A1", dLat: 0.0000, dLon: 0.0000),
                      stand("A2", dLat: 0.0003, dLon: 0.0000),
                      stand("A3", dLat: 0.0006, dLon: 0.0000)]
        // ~0.0003° of latitude is ~33 m, so all three sit inside the 80 m radius; the aircraft
        // is parked on A2's node exactly.
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800",
                                               parkedPosition: offset(dLat: 0.0003, dLon: 0.0000))
        XCTAssertEqual(assign(stands, flight: flight)?.gate, "A2")
    }

    func testAPositionNowhereNearAStandFallsBackToChoosingOne() {
        let stands = [stand("B12", tags: ["operator": "United Airlines"], dLat: 0.001, dLon: 0.001)]
        // Parked a long way from the only stand — out on a taxiway, or a field whose stands
        // aren't mapped where the aircraft is.
        let flight = GateAssigner.FlightContext(callsign: "UAL598", airline: "United",
                                               aircraftName: "Boeing 737-800",
                                               parkedPosition: offset(dLat: 0.050, dLon: 0.050))
        guard let assignment = assign(stands, flight: flight) else {
            return XCTFail("it still assigns a stand, just not a position-derived one")
        }
        XCTAssertEqual(assignment.gate, "B12")
        XCTAssertFalse(assignment.fromAircraftPosition, "this one was chosen, not read")
    }

    func testNoPositionAtAllStillChoosesAStand() {
        let stands = [stand("B12")]
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800")
        let assignment = assign(stands, flight: flight)
        XCTAssertEqual(assignment?.gate, "B12")
        XCTAssertEqual(assignment?.fromAircraftPosition, false)
    }

    func testTheArrivalGateIsNeverReadOffTheAircraftsPosition() {
        // Origin and destination are the same field (a there-and-back leg), so the aircraft is
        // parked on a stand of the *arrival* surface too. Reading it would hand back the stand
        // the flight is leaving.
        let stands = [stand("F3", dLat: 0.010, dLon: 0.010), stand("B12", dLat: 0.001, dLon: 0.001)]
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800",
                                               parkedPosition: offset(dLat: 0.010, dLon: 0.010))
        let arrival = assign(stands, flight: flight, role: .arrival)
        XCTAssertEqual(arrival?.fromAircraftPosition, false,
                       "the arrival gate is chosen from the stand data, never from where we are")
        XCTAssertEqual(assign(stands, flight: flight, role: .departure)?.gate, "F3",
                       "while the departure gate at the same field is read off the position")
    }

    func testParkingOnAServiceOrUnnamedStandIsStillNotAGate() {
        let deicing = stand("D1", tags: ["name": "De-icing pad"], dLat: 0.010, dLon: 0.010)
        let unnamed = stand("", tags: ["ref": ""], dLat: 0.010, dLon: 0.010)
        let realGate = stand("B12", dLat: 0.001, dLon: 0.001)
        let flight = GateAssigner.FlightContext(callsign: "UAL598", aircraftName: "Boeing 737-800",
                                               parkedPosition: offset(dLat: 0.010, dLon: 0.010))
        // Sitting on the de-icing pad (or an unidentified stand) names neither — the real gate
        // is chosen instead, because neither can be said in a clearance.
        guard let assignment = assign([deicing, unnamed, realGate], flight: flight) else {
            return XCTFail("the real gate is still assignable")
        }
        XCTAssertEqual(assignment.gate, "B12")
        XCTAssertFalse(assignment.fromAircraftPosition)
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

    func testStampRemembersWhetherTheGateWasReadOffThePosition() {
        let read = AutoGateStamp(icao: "KIAH", gate: "C24", fromAircraftPosition: true)
        XCTAssertEqual(read.encoded, "KIAH:C24:P")
        XCTAssertEqual(AutoGateStamp(encoded: "KIAH:C24:P"), read)
        XCTAssertEqual(AutoGateStamp(encoded: "KIAH:C24:P")?.gate, "C24",
                       "the flag is not folded into the gate name")
        XCTAssertEqual(AutoGateStamp(encoded: "KIAH:C24")?.fromAircraftPosition, false,
                       "a marker written before the flag existed decodes as a chosen gate")
    }

    func testAChosenGateIsUpgradedByTheOneTheAircraftIsParkedOn() {
        let chosen = AutoGateStamp(icao: "KIAH", gate: "C24").encoded
        let parkedAssignment = GateAssigner.Assignment(
            gate: "F3", osmID: "node/F3", coordinate: GeoCoordinate(ref),
            matchedOperator: false, matchedAircraftType: false, fromAircraftPosition: true,
            tiedCandidates: 1, totalCandidates: 12, reason: "aircraft is parked on it")

        XCTAssertTrue(GateAssigner.couldUpgrade(current: "C24", stamp: chosen, icao: "KIAH"),
                      "a gate the app chose for this field is worth a second look")
        XCTAssertTrue(GateAssigner.mayUpgrade(current: "C24", stamp: chosen, icao: "KIAH",
                                              to: parkedAssignment))

        // Not a pilot's gate, not a re-roll, and not once the gate already came from the position.
        XCTAssertFalse(GateAssigner.couldUpgrade(current: "E7", stamp: chosen, icao: "KIAH"),
                       "a gate the pilot typed over ours is theirs")
        XCTAssertFalse(GateAssigner.couldUpgrade(current: "C24", stamp: chosen, icao: "KMSP"),
                       "a different airport is a fresh assignment, not an upgrade")
        let alreadyRead = AutoGateStamp(icao: "KIAH", gate: "C24", fromAircraftPosition: true).encoded
        XCTAssertFalse(GateAssigner.couldUpgrade(current: "C24", stamp: alreadyRead, icao: "KIAH"),
                       "a gate already read off the position is the truth — never re-picked")

        var chosenAssignment = parkedAssignment
        chosenAssignment.fromAircraftPosition = false
        XCTAssertFalse(GateAssigner.mayUpgrade(current: "C24", stamp: chosen, icao: "KIAH",
                                               to: chosenAssignment),
                       "a second *chosen* gate would just re-roll the dice on the pilot")
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
