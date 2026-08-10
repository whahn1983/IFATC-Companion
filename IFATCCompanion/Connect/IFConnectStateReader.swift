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
        // Infinite Flight reports speeds in metres per second and vertical speed in
        // m/s; the app's models (and the mock feed) use knots and feet-per-minute.
        // Convert here so the Flight tab, phase detection (climb/descent thresholds)
        // and line-up/roll detection all see the expected units. (Without this,
        // groundspeed read ~half the real knots and descents were never detected,
        // so the phase stayed "Cruise" on the way down.)
        s.groundSpeed = (await double(.groundSpeed)).map { $0 * IFConnectStateReader.metresPerSecondToKnots }
        s.indicatedAirspeed = (await double(.indicatedAirspeed)).map { $0 * IFConnectStateReader.metresPerSecondToKnots }
        s.trueAirspeed = (await double(.trueAirspeed)).map { $0 * IFConnectStateReader.metresPerSecondToKnots }
        // Infinite Flight reports heading/track in radians on some versions and in degrees
        // on others, and a single value can't tell the two apart: `4` is both a heading of
        // 004° and one of 4 rad (229°). So the units are decided **once for the whole
        // snapshot** rather than per value — these three come out of the same API in the
        // same convention, so if any one of them is bigger than a full circle in radians,
        // all three are degrees. Judging each on its own turned a nose on 004° into 229°,
        // which then poisoned the wind triangle (`HeadingSolver.wind`) and every deviation
        // vector solved from it whenever heading or track sat within ~6° of north.
        // `environment/wind_direction_true` is an angle from the same API in the same
        // convention, so it joins the snapshot's units vote rather than guessing on its own.
        let rawHeading = await double(.heading)
        let rawTrueHeading = await double(.trueHeading)
        let rawTrack = await double(.track)
        let rawWindDirection = await double(.windDirectionTrue)
        let anglesInDegrees = [rawHeading, rawTrueHeading, rawTrack, rawWindDirection]
            .compactMap { $0 }
            .contains { IFConnectStateReader.exceedsFullCircleInRadians($0) }
        s.heading = rawHeading.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.trueHeading = rawTrueHeading.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.track = rawTrack.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.reportedWindDirectionTrue = rawWindDirection.map {
            IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees)
        }
        // The sim reports wind speed in m/s, like every other speed it exposes.
        s.reportedWindSpeedKnots = (await double(.windVelocity)).map { $0 * IFConnectStateReader.metresPerSecondToKnots }
        s.verticalSpeed = (await double(.verticalSpeed)).map { $0 * IFConnectStateReader.metresPerSecondToFeetPerMinute }
        s.onGround = await bool(.onGround)
        s.approachModeEngaged = await bool(.approachMode)
        s.parkingBrakeSet = await bool(.parkingBrake)
        s.gForce = await double(.gForce)
        s.bankAngle = await double(.bankAngle)
        s.pitch = await double(.pitch)
        s.aircraftName = await string(.aircraftName)
        s.liveryName = await string(.liveryName)
        s.nearestAirport = await string(.nearestAirportICAO)
        return s
    }

    /// The raw flight-plan strings Infinite Flight exposes. Any field may be absent
    /// depending on the IF version / manifest.
    struct FlightPlanPayloads {
        /// `aircraft/0/flightplan/full_info` — the detailed JSON document with per-fix
        /// planned altitudes and nested SID/STAR/approach procedure groups. This is the
        /// richest source (the cruise altitude and procedure names come from here).
        var fullInfo: String?
        /// `aircraft/0/flightplan` — the full plan (rich JSON on some versions, a
        /// collapsed summary of the legs on others).
        var full: String?
        /// `aircraft/0/flightplan/route` — the textual route (every enroute fix).
        var route: String?
        /// `aircraft/0/flightplan/coordinates` — per-fix coordinates.
        var coordinates: String?

        var isEmpty: Bool { fullInfo == nil && full == nil && route == nil && coordinates == nil }
    }

    /// Read the raw flight-plan string (`aircraft/0/flightplan`), if exposed.
    func readFlightPlanRaw(using client: IFConnectClient) async -> String? {
        await readFlightPlanPayloads(using: client).full
    }

    /// Read every flight-plan-related state Infinite Flight exposes. The detailed
    /// route/coordinate states are read alongside the summary so a sparse summary can
    /// be enriched with the full fix list.
    func readFlightPlanPayloads(using client: IFConnectClient) async -> FlightPlanPayloads {
        func read(_ logical: IFStateMappingStore.Logical) async -> String? {
            guard let entry = store.entry(for: logical) else { return nil }
            let raw = try? await client.readState(entry).stringValue
            guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return raw
        }
        return FlightPlanPayloads(fullInfo: await read(.flightPlanFullInfo),
                                  full: await read(.flightPlan),
                                  route: await read(.flightPlanRoute),
                                  coordinates: await read(.flightPlanCoordinates))
    }

    /// Read multiplayer / ATC-staffing context, if exposed. All signals optional.
    /// The tuned COM1 frequency name is the location-aware standby signal — it names the
    /// frequency the pilot is actually on, so the companion defers only when the pilot
    /// has tuned a staffed human controller (not when a human is merely controlling some
    /// other airport in the session).
    func readATCStatus(using client: IFConnectClient) async -> LiveATCStatus {
        func bool(_ logical: IFStateMappingStore.Logical) async -> Bool? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).boolValue
        }
        func int(_ logical: IFStateMappingStore.Logical) async -> Int? {
            guard let entry = store.entry(for: logical) else { return nil }
            guard let d = try? await client.readState(entry).doubleValue else { return nil }
            return Int(d)
        }
        func double(_ logical: IFStateMappingStore.Logical) async -> Double? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).doubleValue
        }
        func string(_ logical: IFStateMappingStore.Logical) async -> String? {
            guard let entry = store.entry(for: logical) else { return nil }
            return try? await client.readState(entry).stringValue
        }

        let detector = LiveATCDetector()
        return detector.status(atcActive: await bool(.atcActive),
                               controllerName: await string(.atcFacilityName),
                               facilityCount: await int(.atcFacilityCount),
                               online: await bool(.isOnline),
                               serverName: await string(.serverName),
                               tunedFrequencyName: await string(.tunedComName),
                               tunedFrequencyMHz: await double(.tunedComFrequency))
    }

    /// Metres-per-second → knots (Infinite Flight reports speeds in m/s).
    static let metresPerSecondToKnots = 1.943_844
    /// Metres-per-second → feet-per-minute (vertical speed).
    static let metresPerSecondToFeetPerMinute = 196.850_4

    /// Whether a raw angular reading is too large to be radians, so its state is being
    /// reported in degrees. Used to settle the units for a whole snapshot at once — see
    /// the heading reads in `readState`.
    static func exceedsFullCircleInRadians(_ value: Double) -> Bool {
        abs(value) > (2 * Double.pi + 0.01)
    }

    /// IF often reports heading/track in radians; normalize to 0–360 degrees.
    ///
    /// `alreadyDegrees` carries the decision made for the whole state snapshot. On its own
    /// a reading of `4` is ambiguous — 004° or 4 rad — so guessing per value silently
    /// mangles every heading near north on a build that reports degrees. The single-argument
    /// form keeps the old per-value guess for callers with no snapshot to reason over.
    static func normalizeAngle(_ value: Double, alreadyDegrees: Bool) -> Double {
        var deg = alreadyDegrees ? value : value * 180 / .pi
        deg = deg.truncatingRemainder(dividingBy: 360)
        if deg < 0 { deg += 360 }
        return deg
    }

    static func normalizeAngle(_ value: Double) -> Double {
        normalizeAngle(value, alreadyDegrees: exceedsFullCircleInRadians(value))
    }
}
