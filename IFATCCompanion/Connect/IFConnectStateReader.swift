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
        // Infinite Flight reports heading and track in radians on some versions and in degrees
        // on others, and a single value can't tell the two apart: `4` is both a heading of
        // 004° and one of 4 rad (229°). So the heading's units are decided from the **heading
        // states themselves** — magnetic and true, read together — and everything else that
        // shares their convention (the ground track, bank, pitch) follows that decision rather
        // than contributing to it.
        //
        // The **wind is not one of them.** It was, on the reasoning that every angle comes out
        // of "the same API in the same convention", and that is precisely what broke the nose
        // in the field: `environment/wind_direction_true` reports degrees on builds whose
        // aircraft states are radians, so one wind from 331 proved "degrees" on every snapshot
        // and every heading — all of them in 0…6.28 — was shown within 6° of north. 084°
        // magnetic arrives as 1.466 and read that way it is 001°. The weather is a separate
        // subsystem and settles its own units from its own readings (`AngleFamily`), which is
        // also what restores the heading to what it read before the wind was ever consulted.
        let rawHeading = await double(.heading)
        let rawTrueHeading = await double(.trueHeading)
        let rawTrack = await double(.track)
        let rawWindDirection = await double(.windDirectionTrue)
        // Keep the raw readings for Diagnostics. The whole radians-vs-degrees question turns on
        // the magnitude of these numbers, and nothing recorded them: a nose shown as 001° while
        // the sim's own panel read 084° could only be argued about.
        var rawAngleLog: [IFStateMappingStore.RawAngleReading] = []
        if let value = rawHeading { rawAngleLog.append(.init(name: "heading", value: value)) }
        if let value = rawTrueHeading { rawAngleLog.append(.init(name: "trueHeading", value: value)) }
        if let value = rawTrack { rawAngleLog.append(.init(name: "track", value: value)) }
        if let value = rawWindDirection { rawAngleLog.append(.init(name: "windDirection", value: value)) }
        store.noteRawAngles(rawAngleLog)

        // The decision also carries across snapshots, not just within one. One snapshot can
        // fail to witness anything: with the nose and the track both within ~6° of north there
        // is no angle too large to be radians, so a build reporting degrees was read as radians
        // and every angle in it multiplied by 57.3 — a 004° nose becoming 229°, and the two
        // headings' one-degree difference becoming tens of degrees of "variation" that went
        // straight into the departure vector. A north-facing runway lines an aircraft up for
        // exactly that and holds it there.
        //
        // The store decides how much evidence that takes and when it has been contradicted
        // (`noteAngleSnapshot`); what belongs here is what each reading is worth. A value past
        // a full circle *in degrees* witnesses nothing: no heading can read 450, so such a
        // number is a corrupt read — the answer to a different state — and treating it as proof
        // of degrees is the other way every heading ends up pinned to north.
        //
        // **Only the two headings vote.** They are the states the decision is *for*, and the
        // only angles resolved by an exact name (`heading_magnetic`, `heading_true`). The
        // ground track is matched by a looser signature — on one build it landed on the bool
        // `aircraft/0/is_on_flight_plan_track` — and a state that isn't the angle its name
        // suggests has no business moving the nose. It follows the decision instead of making
        // it, as bank and pitch already do.
        let headingAngles = [rawHeading, rawTrueHeading].compactMap { $0 }
        store.noteAngleSnapshot(
            family: .aircraft,
            provesDegrees: headingAngles.contains { IFConnectStateReader.provesDegrees($0) },
            anyAboveRadianCircle: headingAngles.contains { IFConnectStateReader.exceedsFullCircleInRadians($0) },
            rawHeading: rawHeading ?? rawTrueHeading)
        store.noteAngleSnapshot(
            family: .environment,
            provesDegrees: rawWindDirection.map { IFConnectStateReader.provesDegrees($0) } ?? false,
            anyAboveRadianCircle: rawWindDirection.map { IFConnectStateReader.exceedsFullCircleInRadians($0) } ?? false,
            // The wind direction barely moves over a flight, so it can never sweep the compass
            // the way a nose does; its proof is corroborated but not contradicted this way.
            rawHeading: nil)
        let anglesInDegrees = store.anglesProvedDegrees
        s.heading = rawHeading.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.trueHeading = rawTrueHeading.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.track = rawTrack.map { IFConnectStateReader.normalizeAngle($0, alreadyDegrees: anglesInDegrees) }
        s.reportedWindDirectionTrue = rawWindDirection.map {
            IFConnectStateReader.normalizeAngle($0, alreadyDegrees: store.windAnglesProvedDegrees)
        }
        // The sim reports wind speed in m/s, like every other speed it exposes.
        s.reportedWindSpeedKnots = (await double(.windVelocity)).map { $0 * IFConnectStateReader.metresPerSecondToKnots }
        s.verticalSpeed = (await double(.verticalSpeed)).map { $0 * IFConnectStateReader.metresPerSecondToFeetPerMinute }
        s.onGround = await bool(.onGround)
        s.approachModeEngaged = await bool(.approachMode)
        s.parkingBrakeSet = await bool(.parkingBrake)
        s.gForce = await double(.gForce)
        // Bank and pitch are angles out of the same API in the same convention as the
        // headings above, so they follow the snapshot's units decision instead of being
        // passed through raw. Raw, a build reporting radians handed a 25° bank over as
        // `0.44`, and every degree-scaled test of it silently passed: the wings-level guard
        // on the wind sample (`HeadingSolver.maxSampleBankDegrees`) never once tripped, so
        // the triangle was solved *mid-turn* — differencing a ~450 kt air vector against a
        // ~450 kt ground vector whose directions were seconds apart in a roll, which invents
        // tens of knots of wind that was never there and crabs every weather vector for it.
        // Unlike a heading these are small signed angles — a left bank is negative — so they
        // wrap to −180…180 rather than onto the 0–360 compass rose, which would turn a −4°
        // bank into 356° and read wings-level as knife-edge.
        s.bankAngle = (await double(.bankAngle)).map {
            IFConnectStateReader.normalizeSignedAngle($0, alreadyDegrees: anglesInDegrees)
        }
        s.pitch = (await double(.pitch)).map {
            IFConnectStateReader.normalizeSignedAngle($0, alreadyDegrees: anglesInDegrees)
        }
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

    /// A full circle in degrees, with slack for a reading that rounds past 360.
    static let fullCircleInDegrees = 360.5

    /// Whether a raw angular reading is evidence that this connection reports angles in
    /// degrees: too large to be radians, and still small enough to *be* an angle in degrees.
    /// Anything past a full circle is not a heading in either convention — it is a reading
    /// that belongs to some other state — so it proves nothing about the units.
    static func provesDegrees(_ value: Double) -> Bool {
        value.isFinite
            && exceedsFullCircleInRadians(value)
            && abs(value) <= fullCircleInDegrees
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

    /// The same conversion for an attitude angle — bank, pitch — which is signed about zero
    /// rather than measured round a compass rose. Wrapped to −180…180 so "how far from level"
    /// stays `abs(value)`.
    static func normalizeSignedAngle(_ value: Double, alreadyDegrees: Bool) -> Double {
        let deg = normalizeAngle(value, alreadyDegrees: alreadyDegrees)
        return deg > 180 ? deg - 360 : deg
    }
}
