package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import kotlinx.coroutines.CancellationException
import kotlin.math.PI
import kotlin.math.abs

/**
 * Reads the mapped aircraft states from Connect and assembles an [AircraftState].
 * Tolerant of missing/unknown states — any individual read failure is skipped.
 *
 * Ported from `IFATCCompanion/Connect/IFConnectStateReader.swift`.
 */
class IFConnectStateReader(
    val store: IFStateMappingStore,
    private val clock: Clock = Clock.system,
) {

    /**
     * Read all resolved logical states and build an [AircraftState] snapshot.
     *
     * The **order** of the reads below is load bearing, not cosmetic. Every read is a
     * separate request/response round-trip on one socket, so the order decides which
     * stale frame lands in which slot after a read times out — see
     * [IFConnectClient.readState].
     */
    suspend fun readState(client: IFConnectClient): AircraftState {
        val lastUpdateMillis = clock.nowMillis()

        val latitude = double(client, IFStateMappingStore.Logical.LATITUDE)
        val longitude = double(client, IFStateMappingStore.Logical.LONGITUDE)
        val altitudeMSL = double(client, IFStateMappingStore.Logical.ALTITUDE_MSL)
        val altitudeAGL = double(client, IFStateMappingStore.Logical.ALTITUDE_AGL)
        // Infinite Flight reports speeds in metres per second and vertical speed in
        // m/s; the app's models (and the mock feed) use knots and feet-per-minute.
        // Convert here so the Flight tab, phase detection (climb/descent thresholds)
        // and line-up/roll detection all see the expected units. (Without this,
        // groundspeed read ~half the real knots and descents were never detected,
        // so the phase stayed "Cruise" on the way down.)
        val groundSpeed = double(client, IFStateMappingStore.Logical.GROUND_SPEED)
            ?.times(METRES_PER_SECOND_TO_KNOTS)
        val indicatedAirspeed = double(client, IFStateMappingStore.Logical.INDICATED_AIRSPEED)
            ?.times(METRES_PER_SECOND_TO_KNOTS)
        val trueAirspeed = double(client, IFStateMappingStore.Logical.TRUE_AIRSPEED)
            ?.times(METRES_PER_SECOND_TO_KNOTS)
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
        // subsystem and settles its own units from its own readings ([IFStateMappingStore
        // .AngleFamily]), which is also what restores the heading to what it read before the
        // wind was ever consulted.
        val rawHeading = double(client, IFStateMappingStore.Logical.HEADING)
        val rawTrueHeading = double(client, IFStateMappingStore.Logical.TRUE_HEADING)
        val rawTrack = double(client, IFStateMappingStore.Logical.TRACK)
        val rawWindDirection = double(client, IFStateMappingStore.Logical.WIND_DIRECTION_TRUE)

        // Keep the raw readings for Diagnostics. The whole radians-vs-degrees question turns on
        // the magnitude of these numbers, and nothing recorded them: a nose shown as 001° while
        // the sim's own panel read 084° could only be argued about.
        val rawAngleLog = buildList {
            rawHeading?.let { add(IFStateMappingStore.RawAngleReading("heading", it)) }
            rawTrueHeading?.let { add(IFStateMappingStore.RawAngleReading("trueHeading", it)) }
            rawTrack?.let { add(IFStateMappingStore.RawAngleReading("track", it)) }
            rawWindDirection?.let { add(IFStateMappingStore.RawAngleReading("windDirection", it)) }
        }
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
        // ([IFStateMappingStore.noteAngleSnapshot]); what belongs here is what each reading is
        // worth. A value past a full circle *in degrees* witnesses nothing: no heading can read
        // 450, so such a number is a corrupt read — the answer to a different state — and
        // treating it as proof of degrees is the other way every heading ends up pinned to
        // north.
        //
        // **Only the two headings vote.** They are the states the decision is *for*, and the
        // only angles resolved by an exact name (`heading_magnetic`, `heading_true`). The
        // ground track is matched by a looser signature — on one build it landed on the bool
        // `aircraft/0/is_on_flight_plan_track` — and a state that isn't the angle its name
        // suggests has no business moving the nose. It follows the decision instead of making
        // it, as bank and pitch already do.
        val headingAngles = listOfNotNull(rawHeading, rawTrueHeading)
        store.noteAngleSnapshot(
            family = IFStateMappingStore.AngleFamily.AIRCRAFT,
            provesDegrees = headingAngles.any { provesDegrees(it) },
            anyAboveRadianCircle = headingAngles.any { exceedsFullCircleInRadians(it) },
            rawHeading = rawHeading ?: rawTrueHeading,
        )
        store.noteAngleSnapshot(
            family = IFStateMappingStore.AngleFamily.ENVIRONMENT,
            provesDegrees = rawWindDirection?.let { provesDegrees(it) } ?: false,
            anyAboveRadianCircle = rawWindDirection?.let { exceedsFullCircleInRadians(it) } ?: false,
            // The wind direction barely moves over a flight, so it can never sweep the compass
            // the way a nose does; its proof is corroborated but not contradicted this way.
            rawHeading = null,
        )
        val anglesInDegrees = store.anglesProvedDegrees
        val heading = rawHeading?.let { normalizeAngle(it, anglesInDegrees) }
        val trueHeading = rawTrueHeading?.let { normalizeAngle(it, anglesInDegrees) }
        val track = rawTrack?.let { normalizeAngle(it, anglesInDegrees) }
        val reportedWindDirectionTrue =
            rawWindDirection?.let { normalizeAngle(it, store.windAnglesProvedDegrees) }

        // The sim reports wind speed in m/s, like every other speed it exposes.
        val reportedWindSpeedKnots = double(client, IFStateMappingStore.Logical.WIND_VELOCITY)
            ?.times(METRES_PER_SECOND_TO_KNOTS)
        val verticalSpeed = double(client, IFStateMappingStore.Logical.VERTICAL_SPEED)
            ?.times(METRES_PER_SECOND_TO_FEET_PER_MINUTE)
        val onGround = bool(client, IFStateMappingStore.Logical.ON_GROUND)
        val approachModeEngaged = bool(client, IFStateMappingStore.Logical.APPROACH_MODE)
        val parkingBrakeSet = bool(client, IFStateMappingStore.Logical.PARKING_BRAKE)
        val gForce = double(client, IFStateMappingStore.Logical.G_FORCE)
        // Bank and pitch are angles out of the same API in the same convention as the
        // headings above, so they follow the snapshot's units decision instead of being
        // passed through raw. Raw, a build reporting radians handed a 25° bank over as
        // `0.44`, and every degree-scaled test of it silently passed: the wings-level guard
        // on the wind sample (`HeadingSolver.MAX_SAMPLE_BANK_DEGREES`) never once tripped, so
        // the triangle was solved *mid-turn* — differencing a ~450 kt air vector against a
        // ~450 kt ground vector whose directions were seconds apart in a roll, which invents
        // tens of knots of wind that was never there and crabs every weather vector for it.
        // Unlike a heading these are small signed angles — a left bank is negative — so they
        // wrap to −180…180 rather than onto the 0–360 compass rose, which would turn a −4°
        // bank into 356° and read wings-level as knife-edge.
        val bankAngle = double(client, IFStateMappingStore.Logical.BANK_ANGLE)
            ?.let { normalizeSignedAngle(it, anglesInDegrees) }
        val pitch = double(client, IFStateMappingStore.Logical.PITCH)
            ?.let { normalizeSignedAngle(it, anglesInDegrees) }
        val aircraftName = string(client, IFStateMappingStore.Logical.AIRCRAFT_NAME)
        val liveryName = string(client, IFStateMappingStore.Logical.LIVERY_NAME)
        val nearestAirport = string(client, IFStateMappingStore.Logical.NEAREST_AIRPORT_ICAO)

        return AircraftState(
            latitude = latitude,
            longitude = longitude,
            altitudeMSL = altitudeMSL,
            altitudeAGL = altitudeAGL,
            groundSpeed = groundSpeed,
            indicatedAirspeed = indicatedAirspeed,
            trueAirspeed = trueAirspeed,
            heading = heading,
            trueHeading = trueHeading,
            track = track,
            verticalSpeed = verticalSpeed,
            onGround = onGround,
            approachModeEngaged = approachModeEngaged,
            parkingBrakeSet = parkingBrakeSet,
            gForce = gForce,
            bankAngle = bankAngle,
            pitch = pitch,
            reportedWindDirectionTrue = reportedWindDirectionTrue,
            reportedWindSpeedKnots = reportedWindSpeedKnots,
            nearestAirport = nearestAirport,
            aircraftName = aircraftName,
            liveryName = liveryName,
            lastUpdateMillis = lastUpdateMillis,
        )
    }

    /**
     * The raw flight-plan strings Infinite Flight exposes. Any field may be absent
     * depending on the IF version / manifest.
     */
    data class FlightPlanPayloads(
        /**
         * `aircraft/0/flightplan/full_info` — the detailed JSON document with per-fix
         * planned altitudes and nested SID/STAR/approach procedure groups. This is the
         * richest source (the cruise altitude and procedure names come from here).
         */
        val fullInfo: String? = null,
        /**
         * `aircraft/0/flightplan` — the full plan (rich JSON on some versions, a
         * collapsed summary of the legs on others).
         */
        val full: String? = null,
        /** `aircraft/0/flightplan/route` — the textual route (every enroute fix). */
        val route: String? = null,
        /** `aircraft/0/flightplan/coordinates` — per-fix coordinates. */
        val coordinates: String? = null,
    ) {
        val isEmpty: Boolean
            get() = fullInfo == null && full == null && route == null && coordinates == null
    }

    /** Read the raw flight-plan string (`aircraft/0/flightplan`), if exposed. */
    suspend fun readFlightPlanRaw(client: IFConnectClient): String? =
        readFlightPlanPayloads(client).full

    /**
     * Read every flight-plan-related state Infinite Flight exposes. The detailed
     * route/coordinate states are read alongside the summary so a sparse summary can
     * be enriched with the full fix list.
     */
    suspend fun readFlightPlanPayloads(client: IFConnectClient): FlightPlanPayloads {
        suspend fun read(logical: IFStateMappingStore.Logical): String? {
            val entry = store.entry(logical) ?: return null
            val raw = attempt { client.readState(entry).stringValue } ?: return null
            // `.trim()` here matches the Swift's `.whitespacesAndNewlines`: a payload of
            // only blank lines is "no plan", not an empty plan.
            if (raw.trim().isEmpty()) return null
            return raw
        }
        return FlightPlanPayloads(
            fullInfo = read(IFStateMappingStore.Logical.FLIGHT_PLAN_FULL_INFO),
            full = read(IFStateMappingStore.Logical.FLIGHT_PLAN),
            route = read(IFStateMappingStore.Logical.FLIGHT_PLAN_ROUTE),
            coordinates = read(IFStateMappingStore.Logical.FLIGHT_PLAN_COORDINATES),
        )
    }

    /**
     * Read multiplayer / ATC-staffing context, if exposed. All signals optional.
     * The tuned COM1 frequency name is the location-aware standby signal — it names the
     * frequency the pilot is actually on, so the companion defers only when the pilot
     * has tuned a staffed human controller (not when a human is merely controlling some
     * other airport in the session).
     */
    suspend fun readATCStatus(client: IFConnectClient): LiveATCStatus {
        // Read in the Swift's argument-evaluation order: the wire order is 1→7, and the
        // same "which stale frame lands where" argument applies as in `readState`.
        val atcActive = bool(client, IFStateMappingStore.Logical.ATC_ACTIVE)
        val controllerName = string(client, IFStateMappingStore.Logical.ATC_FACILITY_NAME)
        val facilityCount = int(client, IFStateMappingStore.Logical.ATC_FACILITY_COUNT)
        val online = bool(client, IFStateMappingStore.Logical.IS_ONLINE)
        val serverName = string(client, IFStateMappingStore.Logical.SERVER_NAME)
        val tunedFrequencyName = string(client, IFStateMappingStore.Logical.TUNED_COM_NAME)
        val tunedFrequencyMHz = double(client, IFStateMappingStore.Logical.TUNED_COM_FREQUENCY)
        return LiveATCDetector().status(
            atcActive = atcActive,
            controllerName = controllerName,
            facilityCount = facilityCount,
            online = online,
            serverName = serverName,
            tunedFrequencyName = tunedFrequencyName,
            tunedFrequencyMHz = tunedFrequencyMHz,
        )
    }

    // region Per-value reads

    private suspend fun double(
        client: IFConnectClient,
        logical: IFStateMappingStore.Logical,
    ): Double? {
        val entry = store.entry(logical) ?: return null
        return attempt { client.readState(entry).doubleValue }
    }

    private suspend fun bool(
        client: IFConnectClient,
        logical: IFStateMappingStore.Logical,
    ): Boolean? {
        val entry = store.entry(logical) ?: return null
        return attempt { client.readState(entry).boolValue }
    }

    private suspend fun int(
        client: IFConnectClient,
        logical: IFStateMappingStore.Logical,
    ): Int? {
        val entry = store.entry(logical) ?: return null
        val d = attempt { client.readState(entry).doubleValue } ?: return null
        return d.toInt()
    }

    private suspend fun string(
        client: IFConnectClient,
        logical: IFStateMappingStore.Logical,
    ): String? {
        val entry = store.entry(logical) ?: return null
        return attempt { client.readState(entry).stringValue }
    }

    /**
     * Swift's `try?`: an unresolved key or *any* read failure yields null rather than
     * propagating, so one missing state never costs the whole snapshot. Cancellation is
     * the exception — it is re-thrown so a torn-down poll ends instead of quietly
     * publishing the half-read snapshot the tear-down produced.
     */
    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    // endregion

    companion object {
        /** Metres-per-second → knots (Infinite Flight reports speeds in m/s). */
        const val METRES_PER_SECOND_TO_KNOTS = 1.943_844

        /** Metres-per-second → feet-per-minute (vertical speed). */
        const val METRES_PER_SECOND_TO_FEET_PER_MINUTE = 196.850_4

        /** A full circle in degrees, with slack for a reading that rounds past 360. */
        const val FULL_CIRCLE_IN_DEGREES = 360.5

        /**
         * Whether a raw angular reading is too large to be radians, so its state is being
         * reported in degrees. Used to settle the units for a whole snapshot at once — see
         * the heading reads in [readState].
         */
        fun exceedsFullCircleInRadians(value: Double): Boolean = abs(value) > (2 * PI + 0.01)

        /**
         * Whether a raw angular reading is evidence that this connection reports angles in
         * degrees: too large to be radians, and still small enough to *be* an angle in
         * degrees. Anything past a full circle is not a heading in either convention — it is
         * a reading that belongs to some other state — so it proves nothing about the units.
         */
        fun provesDegrees(value: Double): Boolean =
            value.isFinite() &&
                exceedsFullCircleInRadians(value) &&
                abs(value) <= FULL_CIRCLE_IN_DEGREES

        /**
         * IF often reports heading/track in radians; normalize to 0–360 degrees.
         *
         * [alreadyDegrees] carries the decision made for the whole state snapshot. On its own
         * a reading of `4` is ambiguous — 004° or 4 rad — so guessing per value silently
         * mangles every heading near north on a build that reports degrees. The
         * single-argument form keeps the old per-value guess for callers with no snapshot to
         * reason over.
         */
        fun normalizeAngle(value: Double, alreadyDegrees: Boolean): Double {
            var deg = if (alreadyDegrees) value else value * 180 / PI
            // Kotlin's `%` on Double is IEEE remainder-toward-zero, the same as Swift's
            // `truncatingRemainder`: it keeps the sign of the dividend.
            deg %= 360
            if (deg < 0) deg += 360
            return deg
        }

        fun normalizeAngle(value: Double): Double =
            normalizeAngle(value, exceedsFullCircleInRadians(value))

        /**
         * The same conversion for an attitude angle — bank, pitch — which is signed about
         * zero rather than measured round a compass rose. Wrapped to −180…180 so "how far
         * from level" stays `abs(value)`.
         */
        fun normalizeSignedAngle(value: Double, alreadyDegrees: Boolean): Double {
            val deg = normalizeAngle(value, alreadyDegrees)
            return if (deg > 180) deg - 360 else deg
        }
    }
}
