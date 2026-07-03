import XCTest
import CoreLocation
@testable import IFATCCompanion

final class WeatherTests: XCTestCase {

    func testRawMETARParsing() {
        let m = METARParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        XCTAssertNotNil(m)
        XCTAssertEqual(m?.icao, "KMSP")
        XCTAssertEqual(m?.windDirection, 320)
        XCTAssertEqual(m?.windSpeed, 12)
        XCTAssertEqual(m?.visibilitySM, 10)
        XCTAssertEqual(m?.ceilingFt, 2500)
        XCTAssertEqual(m?.temperatureC, 18)
        XCTAssertEqual(m?.dewpointC, 11)
        XCTAssertEqual(m?.altimeterInHg ?? 0, 30.12, accuracy: 0.001)
    }

    func testRawMETARGustParsing() {
        let m = METARParser.parseRaw("KDEN 281953Z 02015G24KT 10SM SCT080 24/06 A2998")
        XCTAssertEqual(m?.windDirection, 20)
        XCTAssertEqual(m?.windSpeed, 15)
        XCTAssertEqual(m?.windGust, 24)
    }

    func testRouteAnalyzerFiltersByCorridorAndAltitude() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let position = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)

        let ahead = PIREP(raw: "ahead", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                          altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)
        let behind = PIREP(raw: "behind", coordinate: CLLocationCoordinate2D(latitude: 38, longitude: -96),
                           altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)
        let wrongAlt = PIREP(raw: "wrongAlt", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                             altitudeFt: 20000, turbulence: .light, icing: nil, time: nil, aircraftType: nil)
        let smooth = PIREP(raw: "smooth", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                           altitudeFt: 35000, turbulence: .smooth, icing: nil, time: nil, aircraftType: nil)

        let items = analyzer.relevantReports(pireps: [ahead, behind, wrongAlt, smooth],
                                             position: position, routeEnd: end, altitudeFt: 35000)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.severity, .moderate)
        XCTAssertGreaterThan(items.first?.distanceAheadNM ?? 0, 0)
    }

    /// A PIREP at cruise altitude is relevant when evaluated against the planned
    /// cruise level, but not when evaluated against a much lower climb altitude —
    /// this is why the ride model keys route reports off the flight-plan cruise
    /// altitude (within tolerance) rather than the live altitude while climbing.
    func testReportsFilteredAgainstCruiseAltitudeWithinTolerance() {
        var analyzer = WeatherRouteAnalyzer()
        analyzer.config.corridorNM = 100
        analyzer.config.altitudeBandFt = 5000

        let position = CLLocationCoordinate2D(latitude: 40, longitude: -95)
        let end = CLLocationCoordinate2D(latitude: 44, longitude: -93)
        let atCruise = PIREP(raw: "cruise", coordinate: CLLocationCoordinate2D(latitude: 42, longitude: -94),
                             altitudeFt: 35000, turbulence: .moderate, icing: nil, time: nil, aircraftType: nil)

        // Referenced against the planned cruise level → kept (within ±5000).
        let atCruiseRef = analyzer.relevantReports(pireps: [atCruise], position: position,
                                                   routeEnd: end, altitudeFt: 35000)
        XCTAssertEqual(atCruiseRef.count, 1)

        // Referenced against a 12,000 ft climb altitude → dropped (outside ±5000).
        let atClimbRef = analyzer.relevantReports(pireps: [atCruise], position: position,
                                                  routeEnd: end, altitudeFt: 12000)
        XCTAssertTrue(atClimbRef.isEmpty)
    }

    func testEmptyPirepsProducesNoItems() {
        let analyzer = WeatherRouteAnalyzer()
        let items = analyzer.relevantReports(pireps: [],
                                             position: CLLocationCoordinate2D(latitude: 40, longitude: -95),
                                             routeEnd: CLLocationCoordinate2D(latitude: 44, longitude: -93),
                                             altitudeFt: 35000)
        XCTAssertTrue(items.isEmpty)
    }

    func testTurbulenceSeverityParsing() {
        XCTAssertEqual(TurbulenceSeverity.parse("MOD"), .moderate)
        XCTAssertEqual(TurbulenceSeverity.parse("SEV"), .severe)
        XCTAssertEqual(TurbulenceSeverity.parse("LGT CHOP"), .light)  // contains LGT
        XCTAssertEqual(TurbulenceSeverity.parse("CHOP"), .lightChop)
    }

    func testRideReportEngineNoReports() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let tx = ride.rideReport(items: [], callsign: cs)
        XCTAssertTrue(tx.displayText.contains("no significant ride reports"))
    }

    func testDestinationWeatherSpoken() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let ride = RideReportEngine(engine: engine)
        let cs = engine.callsign(airline: "United", flightNumber: "598", fallback: "")
        let metar = METARParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012")
        let tx = ride.destinationWeather(metar: metar, callsign: cs, icao: "KMSP")
        XCTAssertTrue(tx.spokenText.contains("wind three two zero at one two"))
        XCTAssertTrue(tx.displayText.contains("Minneapolis"))
    }

    /// The pilot acknowledges an informational reply (ride report / destination
    /// weather) with a courtesy "Roger", addressed to the working controller.
    func testPilotRogerAcknowledgement() {
        let engine = PhraseologyEngine(digitStyle: .individual)
        let pilot = PilotResponseEngine(engine: engine)
        let tx = pilot.roger(context: TestSupport.context(), facility: .center)
        XCTAssertEqual(tx.sender, .pilot)
        XCTAssertEqual(tx.facility, .center)
        XCTAssertTrue(tx.displayText.contains("Roger"))
        XCTAssertTrue(tx.displayText.contains("United 598"))
    }
}
