import Foundation

/// Reads the mapped aircraft states from Connect and assembles an `AircraftState`.
/// Tolerant of missing/unknown states — any individual read failure is skipped.
struct IFConnectStateReader {

    let store: IFStateMappingStore

    /// Read all resolved logical states and build an `AircraftState` snapshot.
    func readState(using client: IFConnectClient) async -> AircraftState {
        var s = AircraftState()
        s.lastUpdate = Date()

        func double(_ logical: IFStateMappingStore.Logical) async -> Double? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).doubleValue
        }
        func bool(_ logical: IFStateMappingStore.Logical) async -> Bool? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).boolValue
        }
        func string(_ logical: IFStateMappingStore.Logical) async -> String? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).stringValue
        }

        s.latitude = await double(.latitude)
        s.longitude = await double(.longitude)
        s.altitudeMSL = await double(.altitudeMSL)
        s.altitudeAGL = await double(.altitudeAGL)
        s.groundSpeed = await double(.groundSpeed)
        s.indicatedAirspeed = await double(.indicatedAirspeed)
        s.trueAirspeed = await double(.trueAirspeed)
        s.heading = (await double(.heading)).map(IFConnectStateReader.normalizeAngle)
        s.track = (await double(.track)).map(IFConnectStateReader.normalizeAngle)
        s.verticalSpeed = await double(.verticalSpeed)
        s.onGround = await bool(.onGround)
        s.gForce = await double(.gForce)
        s.bankAngle = await double(.bankAngle)
        s.pitch = await double(.pitch)
        s.aircraftName = await string(.aircraftName)
        s.liveryName = await string(.liveryName)
        s.nearestAirport = await string(.nearestAirportICAO)
        return s
    }

    /// Read just the live callsign, if exposed.
    func readCallsign(using client: IFConnectClient) async -> String? {
        guard let entry = store.entry(for: .callsign) else { return nil }
        return try? await client.readState(entry).stringValue
    }

    /// Read multiplayer / ATC-staffing context, if exposed. All signals optional.
    func readATCStatus(using client: IFConnectClient) async -> LiveATCStatus {
        func bool(_ logical: IFStateMappingStore.Logical) async -> Bool? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).boolValue
        }
        func int(_ logical: IFStateMappingStore.Logical) async -> Int? {
            guard let entry = store.entry(for: logical) else { return nil }
            guard let d = try? await client.readState(entry).doubleValue else { return nil }
            return d.map(Int.init)
        }
        func string(_ logical: IFStateMappingStore.Logical) async -> String? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).stringValue
        }

        let detector = LiveATCDetector()
        return detector.status(atcActive: await bool(.atcActive),
                               facilityName: await string(.atcFacilityName),
                               facilityCount: await int(.atcFacilityCount),
                               online: await bool(.isOnline),
                               serverName: await string(.serverName))
    }

    /// IF often reports heading/track in radians; normalize to 0–360 degrees.
    static func normalizeAngle(_ value: Double) -> Double {
        var deg = value
        // Heuristic: small magnitudes are radians.
        if abs(deg) <= (2 * Double.pi + 0.01) { deg = deg * 180 / .pi }
        deg = deg.truncatingRemainder(dividingBy: 360)
        if deg < 0 { deg += 360 }
        return deg
    }
}
