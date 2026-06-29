import Foundation
import CoreLocation
import Combine

/// Deterministic mock flight-data feed so the app is fully demoable in the
/// Simulator without Infinite Flight. Drives a simulated route through each
/// flight phase and ships sample weather + PIREP data.
@MainActor
final class MockSimulatorFeed: ObservableObject {

    struct Route {
        let departure: String
        let destination: String
        let depCoord: CLLocationCoordinate2D
        let destCoord: CLLocationCoordinate2D
        let cruiseAltitude: Int
        let waypoints: [Waypoint]
    }

    @Published private(set) var phase: FlightPhase = .preflight
    @Published private(set) var running = false

    /// Pushed states (AppModel subscribes, identical interface to live feed).
    var onState: ((AircraftState) -> Void)?

    let route: Route
    private var task: Task<Void, Never>?
    private var phaseProgress: Double = 0  // 0..1 within the current phase emission

    init(route: Route = MockSimulatorFeed.defaultRoute()) {
        self.route = route
    }

    // MARK: - Control

    func start() {
        guard !running else { return }
        running = true
        emit() // push immediately
        task = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, self.running else { break }
                await MainActor.run {
                    self.phaseProgress = min(1, self.phaseProgress + 0.1)
                    self.emit()
                }
            }
        }
    }

    func stop() {
        running = false
        task?.cancel()
        task = nil
    }

    func setPhase(_ newPhase: FlightPhase) {
        phase = newPhase
        phaseProgress = 0
        emit()
    }

    func advancePhase() {
        let seq = FlightPhase.demoSequence
        if let idx = seq.firstIndex(of: phase), idx + 1 < seq.count {
            setPhase(seq[idx + 1])
        } else {
            setPhase(seq.first ?? .preflight)
        }
    }

    // MARK: - State synthesis

    private func emit() {
        onState?(state(for: phase))
    }

    /// Build a plausible aircraft state for a given phase.
    func state(for phase: FlightPhase) -> AircraftState {
        var s = AircraftState()
        s.lastUpdate = Date()
        s.aircraftName = "Boeing 737-800"
        s.liveryName = "United"
        let course = Geo.bearing(from: route.depCoord, to: route.destCoord)
        s.heading = course
        s.track = course

        func along(_ fraction: Double) -> CLLocationCoordinate2D {
            let lat = route.depCoord.latitude + (route.destCoord.latitude - route.depCoord.latitude) * fraction
            let lon = route.depCoord.longitude + (route.destCoord.longitude - route.depCoord.longitude) * fraction
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        }

        // Base profile per phase: (fraction-along, altitude, gs, vs, onGround)
        let profile: (frac: Double, alt: Double, gs: Double, vs: Double, ground: Bool)
        switch phase {
        case .preflight:    profile = (0.00, 0,      0,   0,     true)
        case .taxiOut:      profile = (0.00, 0,      16,  0,     true)
        case .takeoff:      profile = (0.01, 50,     150, 200,   true)
        case .initialClimb: profile = (0.04, 4500,   240, 2600,  false)
        case .climb:        profile = (0.18, 21000,  390, 1900,  false)
        case .cruise:       profile = (0.50, Double(route.cruiseAltitude), 460, 0, false)
        case .descent:      profile = (0.80, 17000,  410, -1900, false)
        case .approach:     profile = (0.97, 4000,   240, -1100, false)
        case .landing:      profile = (1.00, 0,      130, -300,  true)
        case .taxiIn:       profile = (1.00, 0,      16,  0,     true)
        case .parked:       profile = (1.00, 0,      0,   0,     true)
        case .unknown:      profile = (0.50, Double(route.cruiseAltitude), 450, 0, false)
        }

        // Nudge fraction forward slightly across in-phase ticks for liveliness.
        let frac = min(1, profile.frac + phaseProgress * 0.02)
        let coord = along(frac)
        s.latitude = coord.latitude
        s.longitude = coord.longitude
        s.altitudeMSL = profile.alt
        s.altitudeAGL = profile.ground ? 0 : max(0, profile.alt - 800)
        s.groundSpeed = profile.gs
        s.indicatedAirspeed = profile.ground ? profile.gs : max(0, profile.gs - 60)
        s.trueAirspeed = profile.gs
        s.verticalSpeed = profile.vs
        s.onGround = profile.ground
        // Simulate the autopilot approach mode (APPR) being engaged once on the
        // approach, so the companion can issue the "cleared … approach" call.
        s.approachModeEngaged = (phase == .approach || phase == .landing)
        // Parking brake is set at the gate (pre-departure) and once parked after
        // arrival; released any time the aircraft is moving or airborne.
        s.parkingBrakeSet = (phase == .preflight || phase == .parked)
        s.nearestAirport = frac < 0.5 ? route.departure : route.destination
        s.nearestAirportDistanceNM = Geo.distanceNM(from: coord,
                                                     to: frac < 0.5 ? route.depCoord : route.destCoord)
        return s
    }

    // MARK: - Sample weather

    func sampleMETARs() -> [METAR] {
        [
            METARParser.parseRaw("KIAH 281953Z 16008KT 10SM FEW250 31/21 A3001"),
            METARParser.parseRaw("KMSP 281953Z 32012KT 10SM BKN025 18/11 A3012"),
            METARParser.parseRaw("KDEN 281953Z 02015G24KT 10SM SCT080 24/06 A2998")
        ].compactMap { $0 }
    }

    func sampleTAF() -> TAF {
        TAF(icao: route.destination,
            raw: "KMSP 281720Z 2818/2924 32012KT P6SM BKN025 FM290200 31008KT P6SM SCT040",
            issueTime: nil,
            periods: [])
    }

    /// Sample PIREPs placed along the route at cruise altitude with light/moderate
    /// turbulence, so the ride-report feature is demoable offline.
    func samplePIREPs() -> [PIREP] {
        func point(_ fraction: Double) -> CLLocationCoordinate2D {
            CLLocationCoordinate2D(
                latitude: route.depCoord.latitude + (route.destCoord.latitude - route.depCoord.latitude) * fraction,
                longitude: route.depCoord.longitude + (route.destCoord.longitude - route.depCoord.longitude) * fraction)
        }
        return [
            PIREP(raw: "UA /OV KMCI090040 /TM 1945 /FL350 /TP B738 /TB LGT-MOD",
                  coordinate: point(0.62), altitudeFt: 35000, turbulence: .moderate,
                  icing: nil, time: Date(), aircraftType: "B738"),
            PIREP(raw: "UA /OV KOMA /TM 1950 /FL330 /TP A320 /TB LGT CHOP",
                  coordinate: point(0.70), altitudeFt: 33000, turbulence: .lightChop,
                  icing: nil, time: Date(), aircraftType: "A320"),
            PIREP(raw: "UA /OV KDSM /TM 1955 /FL370 /TP B739 /TB LGT",
                  coordinate: point(0.78), altitudeFt: 37000, turbulence: .light,
                  icing: nil, time: Date(), aircraftType: "B739")
        ]
    }

    // MARK: - Routes

    nonisolated static func defaultRoute() -> Route {
        let db = AirportDatabase.shared
        let dep = db.coordinate(for: "KIAH") ?? CLLocationCoordinate2D(latitude: 29.98, longitude: -95.34)
        let dest = db.coordinate(for: "KMSP") ?? CLLocationCoordinate2D(latitude: 44.88, longitude: -93.22)
        return Route(departure: "KIAH", destination: "KMSP",
                     depCoord: dep, destCoord: dest, cruiseAltitude: 37000,
                     waypoints: synthWaypoints(dep: dep, dest: dest,
                                               names: ["TBONE", "KMCI", "KOMA", "KDSM", "FARGO"]))
    }

    nonisolated static func denverRoute() -> Route {
        let db = AirportDatabase.shared
        let dep = db.coordinate(for: "KDEN") ?? CLLocationCoordinate2D(latitude: 39.85, longitude: -104.67)
        let dest = db.coordinate(for: "KMSP") ?? CLLocationCoordinate2D(latitude: 44.88, longitude: -93.22)
        return Route(departure: "KDEN", destination: "KMSP",
                     depCoord: dep, destCoord: dest, cruiseAltitude: 35000,
                     waypoints: synthWaypoints(dep: dep, dest: dest,
                                               names: ["AKO", "ONL", "FSD", "REDWG"]))
    }

    nonisolated private static func synthWaypoints(dep: CLLocationCoordinate2D,
                                       dest: CLLocationCoordinate2D,
                                       names: [String]) -> [Waypoint] {
        names.enumerated().map { idx, name in
            let f = Double(idx + 1) / Double(names.count + 1)
            return Waypoint(name: name,
                            latitude: dep.latitude + (dest.latitude - dep.latitude) * f,
                            longitude: dep.longitude + (dest.longitude - dep.longitude) * f,
                            altitude: nil)
        }
    }
}
