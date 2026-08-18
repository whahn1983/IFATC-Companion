import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Enroute Center sectors: the bundled boundary dataset, the geometry it is queried
/// with, the hysteresis that decides when a crossing is real, the phraseology that
/// names the controller, and the licence the data ships under.
final class CenterSectorTests: XCTestCase {

    // MARK: - Helpers

    /// A rectangular sector, for tests that care about crossing behaviour rather than
    /// real geography.
    private func box(id: String, radio: String,
                     latitudes: ClosedRange<Double>, longitudes: ClosedRange<Double>,
                     frequency: Double? = nil) -> CenterSector {
        CenterSector(id: id, name: id, radioName: radio, isOceanic: false,
                     publishedFrequency: frequency,
                     minLat: latitudes.lowerBound, maxLat: latitudes.upperBound,
                     minLon: longitudes.lowerBound, maxLon: longitudes.upperBound,
                     polygons: [[[longitudes.lowerBound, latitudes.lowerBound,
                                  longitudes.upperBound, latitudes.lowerBound,
                                  longitudes.upperBound, latitudes.upperBound,
                                  longitudes.lowerBound, latitudes.upperBound]]])
    }

    /// West sector (lon −100…−90) and east sector (lon −90…−80), sharing the −90 edge.
    private func twoSectorDatabase() -> CenterSectorDatabase {
        CenterSectorDatabase(sectors: [
            box(id: "WEST", radio: "West Center", latitudes: 30...40, longitudes: (-100)...(-90)),
            box(id: "EAST", radio: "East Center", latitudes: 30...40, longitudes: (-90)...(-80))
        ])
    }

    private func coordinate(_ lat: Double, _ lon: Double) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// The dataset as the app loads it. Hosted tests run inside the app bundle, so the
    /// resource the app ships is the resource under test.
    private func bundledDatabase() throws -> CenterSectorDatabase {
        let database = CenterSectorDatabase()
        guard database.loadNow() else {
            throw XCTSkip("CenterSectors.json failed to load: \(database.state)")
        }
        return database
    }

    // MARK: - Bundled dataset

    func testBundledDatasetLoadsWithGlobalCoverage() throws {
        let database = try bundledDatabase()
        XCTAssertTrue(database.isReady)
        XCTAssertGreaterThan(database.count, 300,
                             "the dataset should cover every FIR/ARTCC worldwide, not just the US")
        XCTAssertEqual(database.provenance?.license, "CC BY-SA 4.0")
        XCTAssertEqual(database.provenance?.sectorCount, database.count)
    }

    func testUnitedStatesFieldsResolveToTheirARTCC() throws {
        let database = try bundledDatabase()
        let expected: [(String, CLLocationCoordinate2D)] = [
            ("Houston Center", coordinate(29.98, -95.34)),      // KIAH
            ("Fort Worth Center", coordinate(32.90, -97.04)),   // KDFW
            ("Memphis Center", coordinate(35.04, -89.98)),      // KMEM
            ("Atlanta Center", coordinate(33.64, -84.43)),      // KATL
            ("Los Angeles Center", coordinate(33.94, -118.41)), // KLAX
            ("New York Center", coordinate(40.64, -73.78))      // KJFK
        ]
        for (radioName, position) in expected {
            XCTAssertEqual(database.sector(at: position)?.radioName, radioName)
        }
    }

    /// The rest of the world is covered too, and ICAO area control centres are called
    /// "Control", not "Center".
    func testInternationalSectorsUseTheirOwnRadioNames() throws {
        let database = try bundledDatabase()
        XCTAssertEqual(database.sector(at: coordinate(51.47, -0.45))?.radioName, "London Control")
        XCTAssertEqual(database.sector(at: coordinate(43.68, -79.63))?.radioName, "Toronto Center")
        XCTAssertEqual(database.sector(at: coordinate(52.0, -30.0))?.radioName, "Shanwick Oceanic")
        XCTAssertNotNil(database.sector(at: coordinate(-33.95, 151.18)))   // YSSY
        XCTAssertNotNil(database.sector(at: coordinate(35.55, 139.78)))    // RJTT
    }

    /// Flying Houston to Chicago crosses Fort Worth's and Memphis's airspace on the way,
    /// in that order — the sequence of hand-offs the enroute leg should produce.
    func testRouteCrossesSectorsInOrder() throws {
        let database = try bundledDatabase()
        let start = coordinate(29.98, -95.34)      // KIAH
        let end = coordinate(41.97, -87.91)        // KORD
        var sequence: [String] = []
        for step in 0...120 {
            let fraction = Double(step) / 120
            let position = coordinate(start.latitude + (end.latitude - start.latitude) * fraction,
                                      start.longitude + (end.longitude - start.longitude) * fraction)
            guard let name = database.sector(at: position)?.radioName else { continue }
            if sequence.last != name { sequence.append(name) }
        }
        XCTAssertEqual(Array(sequence.prefix(3)),
                       ["Houston Center", "Fort Worth Center", "Memphis Center"])
        XCTAssertTrue(sequence.contains("Chicago Center"), "\(sequence)")
    }

    func testNeighbouringSectorsNeverShareAFrequency() throws {
        let database = try bundledDatabase()
        let houston = try XCTUnwrap(database.sector(id: "KZHU"))
        let fortWorth = try XCTUnwrap(database.sector(id: "KZFW"))
        let memphis = try XCTUnwrap(database.sector(id: "KZME"))
        XCTAssertNotEqual(houston.frequency, fortWorth.frequency)
        XCTAssertNotEqual(fortWorth.frequency, memphis.frequency)
        XCTAssertNotEqual(houston.frequency, memphis.frequency)
    }

    // MARK: - Geometry

    func testContainmentAndDistanceToBoundary() {
        let west = box(id: "WEST", radio: "West Center", latitudes: 30...40, longitudes: (-100)...(-90))
        XCTAssertTrue(west.contains(coordinate(35, -95)))
        XCTAssertFalse(west.contains(coordinate(35, -89)))
        XCTAssertFalse(west.contains(coordinate(45, -95)), "outside the bounding box entirely")
        // One degree of longitude at 35° N is ~49 NM; the nearest edge is the −90 one.
        XCTAssertEqual(west.distanceToBoundaryNM(from: coordinate(35, -91)), 49, accuracy: 2)
    }

    // MARK: - Frequencies

    func testSimulatedFrequenciesAreStableAndInTheEnrouteBand() {
        for id in ["KZHU", "KZFW", "KZME", "EGTT", "CZQX"] {
            let frequency = CenterSector.simulatedFrequency(for: id)
            XCTAssertEqual(frequency, CenterSector.simulatedFrequency(for: id),
                           "the same sector must always get the same frequency")
            XCTAssertGreaterThanOrEqual(frequency, CenterSector.lowestFrequency)
            XCTAssertLessThanOrEqual(frequency, CenterSector.highestFrequency)
            XCTAssertEqual((frequency * 1000).rounded()
                            .truncatingRemainder(dividingBy: 25), 0, accuracy: 0.001,
                           "frequencies sit on the 25 kHz grid")
        }
    }

    func testPublishedFrequencyWinsOverTheSimulatedOne() {
        let sector = box(id: "YBIK", radio: "Melbourne Center",
                         latitudes: (-40)...(-30), longitudes: 140...150, frequency: 129.8)
        XCTAssertEqual(sector.frequency, 129.8)
        let database = CenterSectorDatabase(sectors: [sector])
        XCTAssertEqual(database.sector(id: "YBIK")?.frequency, 129.8,
                       "a real published frequency is never moved by de-confliction")
    }

    // MARK: - Crossing hysteresis

    func testFirstFixAdoptsTheSectorWithoutAHandoff() {
        let database = twoSectorDatabase()
        var tracker = CenterSectorTracker()
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -95), at: Date(), database: database))
        XCTAssertEqual(tracker.current?.id, "WEST")
    }

    func testCrossingIsAnnouncedOnlyOnceWellInsideTheNextSector() {
        let database = twoSectorDatabase()
        var tracker = CenterSectorTracker()
        let start = Date()
        _ = tracker.update(coordinate: coordinate(35, -90.5), at: start, database: database)

        // A mile inside the next sector is not a crossing — a track that skims the
        // boundary must not bounce the radio between two controllers.
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -89.98),
                                    at: start.addingTimeInterval(10), database: database))
        XCTAssertEqual(tracker.current?.id, "WEST")

        // Five miles inside it is.
        let crossing = tracker.update(coordinate: coordinate(35, -89.9),
                                      at: start.addingTimeInterval(20), database: database)
        XCTAssertEqual(crossing?.from.id, "WEST")
        XCTAssertEqual(crossing?.to.id, "EAST")
        XCTAssertEqual(tracker.current?.id, "EAST")
    }

    func testASecondCrossingWaitsOutTheMinimumSpacing() {
        let database = twoSectorDatabase()
        var tracker = CenterSectorTracker()
        let start = Date()
        _ = tracker.update(coordinate: coordinate(35, -90.5), at: start, database: database)
        XCTAssertNotNil(tracker.update(coordinate: coordinate(35, -89.9),
                                       at: start.addingTimeInterval(20), database: database))

        // Straight back across a moment later: held, so two calls can't stack.
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -90.5),
                                    at: start.addingTimeInterval(30), database: database))
        XCTAssertEqual(tracker.current?.id, "EAST")

        // Once the spacing has elapsed the hand-off back is issued.
        let back = tracker.update(coordinate: coordinate(35, -90.5),
                                  at: start.addingTimeInterval(200), database: database)
        XCTAssertEqual(back?.to.id, "WEST")
    }

    func testAPositionJumpAdoptsTheNewSectorSilently() {
        let database = twoSectorDatabase()
        var tracker = CenterSectorTracker()
        let start = Date()
        _ = tracker.update(coordinate: coordinate(35, -95), at: start, database: database)
        // Hundreds of miles between two fixes: the app was backgrounded or the link
        // resynced. The pilot is already deep inside the new sector, so nothing is said.
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -85),
                                    at: start.addingTimeInterval(30), database: database))
        XCTAssertEqual(tracker.current?.id, "EAST")
    }

    func testAGapInTheDataKeepsTheWorkingSector() {
        let database = twoSectorDatabase()
        var tracker = CenterSectorTracker()
        let start = Date()
        _ = tracker.update(coordinate: coordinate(35, -95), at: start, database: database)
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -70),
                                    at: start.addingTimeInterval(10), database: database),
                     "a position no sector covers must not produce a hand-off")
        XCTAssertEqual(tracker.current?.id, "WEST")
    }

    func testNoSectorDataMeansNoCrossings() {
        let database = CenterSectorDatabase(sectors: [])
        var tracker = CenterSectorTracker()
        XCTAssertNil(tracker.update(coordinate: coordinate(35, -95), at: Date(), database: database))
        XCTAssertNil(tracker.current)
    }

    // MARK: - Phraseology

    func testCenterCallsNameTheWorkingSector() {
        var engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        XCTAssertEqual(engine.spokenName(for: .center), "Center",
                       "with no sector known the generic name is used")

        engine.centerSectorName = "Memphis Center"
        XCTAssertEqual(engine.spokenName(for: .center), "Memphis Center")
        XCTAssertEqual(engine.spokenName(for: .tower), "Tower", "only Center is sector-named")

        let handoff = engine.handoff(cs: cs, from: .center, to: .center, frequency: 133.975)
        XCTAssertTrue(handoff.displayText.contains("contact Memphis Center on 133.975"),
                      handoff.displayText)
        XCTAssertTrue(handoff.readback?.displayText.contains("Contacting Memphis Center on 133.975") ?? false,
                      handoff.readback?.displayText ?? "")
        XCTAssertEqual(handoff.readback?.tuneTo, .center)

        let checkIn = engine.radarContact(cs: cs, facility: .center)
        XCTAssertTrue(checkIn.displayText.contains("Memphis Center, radar contact"), checkIn.displayText)
    }

    func testPilotChecksInWithTheSectorByName() {
        var engine = PhraseologyEngine(digitStyle: .individual, mode: .faa)
        engine.centerSectorName = "Fort Worth Center"
        let pilot = PilotResponseEngine(engine: engine)
        var plan = FlightPlan()
        plan.airline = "United"
        plan.flightNumber = "598"
        let context = ATCContext(callsign: engine.callsign(airline: "United", flightNumber: "598", fallback: ""),
                                 plan: plan, assignedAltitude: 37000, cruiseAltitude: 37000,
                                 initialClimbAltitude: 5000, windDirection: 180, windSpeed: 10,
                                 squawk: "4271", runway: "27", taxiway: "A", crossingRunway: nil,
                                 parkingTaxiway: "B", approachName: "ILS", departureFrequency: 124.3,
                                 centerFrequency: 133.975, approachFrequency: 119.7,
                                 towerFrequency: 118.3, groundFrequency: 121.8)
        let call = pilot.requestHandoff(context: context, facility: .center,
                                        currentAltitude: 37000, targetAltitude: 37000,
                                        onGround: false)
        XCTAssertTrue(call.displayText.hasPrefix("Fort Worth Center,"), call.displayText)
    }

    // MARK: - Licensing

    func testSectorDataIsAttributedUnderShareAlike() {
        XCTAssertEqual(CenterSectorData.providerName, "VATSIM VATSpy Data Project")
        XCTAssertEqual(CenterSectorData.licenseShortName, "CC BY-SA 4.0")
        XCTAssertTrue(CenterSectorData.licenseName.contains("ShareAlike"))
        XCTAssertEqual(CenterSectorData.attributionText, "Sector boundaries © VATSIM VATSpy Data Project")
        XCTAssertEqual(CenterSectorData.licenseURL.host, "creativecommons.org")
        for url in [CenterSectorData.sourceURL, CenterSectorData.licenseURL,
                    CenterSectorData.publicDocumentationURL] {
            XCTAssertEqual(url.scheme, "https")
        }
        XCTAssertFalse(CenterSectorData.attributionText.contains("OpenStreetMap"),
                       "sector geometry does not come from OSM — OSM does not map airspace")
    }
}
