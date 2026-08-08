import XCTest
import CoreLocation
@testable import IFATCCompanion

/// Covers merging a flight plan read from Infinite Flight into the active plan.
///
/// The route is the one field the pilot cannot type anywhere in the app, so it must
/// always follow Infinite Flight — including when the plan is edited mid-flight, which
/// is how the approach normally arrives (the pilot adds it after getting the ATIS).
@MainActor
final class LiveFlightPlanMergeTests: XCTestCase {

    private func fix(_ name: String, _ lat: Double, _ lon: Double) -> Waypoint {
        Waypoint(name: name, latitude: lat, longitude: lon)
    }

    /// The KIAH→KATL route before and after the I09R approach was added in the sim.
    private var starOnlyFixes: [Waypoint] {
        [fix("JNGLE", 33.2997, -84.8297), fix("QUBIT", 33.4525, -84.8297)]
    }
    private var withApproachFixes: [Waypoint] {
        starOnlyFixes + [fix("DFINS", 33.6311, -84.7936), fix("GGUYY", 33.6314, -84.7186),
                         fix("EEASY", 33.6314, -84.6497), fix("BURNY", 33.6317, -84.5492)]
    }

    private func model(manualOverride: Bool, waypoints: [Waypoint]) -> AppModel {
        let model = AppModel()
        model.settings.voiceEnabled = false
        var plan = FlightPlan()
        plan.departure = "KIAH"
        plan.destination = "KATL"
        plan.waypoints = waypoints
        plan.manualOverride = manualOverride
        model.flightPlan = plan
        return model
    }

    private func livePlan(_ waypoints: [Waypoint]) -> FlightPlan {
        var live = FlightPlan()
        live.departure = "KIAH"
        live.destination = "KATL"
        live.waypoints = waypoints
        live.star = "GNDLF3"
        live.approach = "I09R"
        live.approachStartFixName = "DFINS"
        return live
    }

    /// A route edited mid-flight reaches the app even when the pilot pinned the
    /// endpoints with manual overrides — there is no field to type a fix list into, so
    /// `manualOverride` must not freeze the route at the STAR's last fix.
    func testManualOverrideDoesNotFreezeTheRoute() {
        let model = model(manualOverride: true, waypoints: starOnlyFixes)

        model.mergeLiveFlightPlanForTesting(livePlan(withApproachFixes))

        XCTAssertEqual(model.flightPlan.waypoints.map(\.name),
                       ["JNGLE", "QUBIT", "DFINS", "GGUYY", "EEASY", "BURNY"])
        // The pinned endpoints still win.
        XCTAssertEqual(model.flightPlan.departure, "KIAH")
        XCTAssertEqual(model.flightPlan.destination, "KATL")
    }

    /// The approach's first fix travels with the route it names, so the weather-deviation
    /// rejoin cap can find it. Left unmerged it stayed empty and the cap never applied.
    func testApproachStartFixNameIsMerged() {
        let model = model(manualOverride: false, waypoints: starOnlyFixes)

        model.mergeLiveFlightPlanForTesting(livePlan(withApproachFixes))

        XCTAssertEqual(model.flightPlan.approachStartFixName, "DFINS")
        XCTAssertEqual(model.flightPlan.approachStartCoordinate?.latitude ?? 0,
                       33.6311, accuracy: 0.0001)
    }

    /// A momentarily degraded read — the detailed `full_info` state missing on one poll,
    /// leaving only the coordinate-less route string — must not strip the positions off a
    /// route the app already has, or the map line and the deviation math go blank until
    /// the next good poll.
    func testDegradedReadKeepsKnownCoordinates() {
        let model = model(manualOverride: false, waypoints: withApproachFixes)

        let unlocated = withApproachFixes.map { Waypoint(name: $0.name) }
        XCTAssertTrue(unlocated.allSatisfy { $0.coordinate == nil })
        model.mergeLiveFlightPlanForTesting(livePlan(unlocated))

        XCTAssertEqual(model.flightPlan.waypoints.map(\.name),
                       ["JNGLE", "QUBIT", "DFINS", "GGUYY", "EEASY", "BURNY"])
        XCTAssertTrue(model.flightPlan.waypoints.allSatisfy { $0.coordinate != nil })
        XCTAssertEqual(model.flightPlan.waypoints.last?.coordinate?.longitude ?? 0,
                       -84.5492, accuracy: 0.0001)
    }

    /// A fix that is genuinely new and unlocated stays unlocated — the carry-over only
    /// restores a position the app already knew for that same fix.
    func testDegradedReadDoesNotInventCoordinates() {
        let model = model(manualOverride: false, waypoints: starOnlyFixes)

        model.mergeLiveFlightPlanForTesting(livePlan(starOnlyFixes + [Waypoint(name: "NEWFX")]))

        XCTAssertEqual(model.flightPlan.waypoints.map(\.name), ["JNGLE", "QUBIT", "NEWFX"])
        XCTAssertNil(model.flightPlan.waypoints.last?.coordinate)
        XCTAssertNotNil(model.flightPlan.waypoints.first?.coordinate)
    }

    /// An empty live route never wipes a route the app already has (a read that returned
    /// nothing usable should leave the plan alone).
    func testEmptyLiveRouteIsIgnored() {
        let model = model(manualOverride: false, waypoints: withApproachFixes)

        model.mergeLiveFlightPlanForTesting(livePlan([]))

        XCTAssertEqual(model.flightPlan.waypoints.count, withApproachFixes.count)
    }
}
