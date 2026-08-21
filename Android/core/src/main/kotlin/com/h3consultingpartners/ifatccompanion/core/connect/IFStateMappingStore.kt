package com.h3consultingpartners.ifatccompanion.core.connect

import kotlin.math.PI
import kotlin.math.min

/**
 * Maps logical aircraft-state concepts onto concrete manifest entries discovered at
 * runtime. No aircraft-specific state ids are hardcoded — instead each logical key
 * has a list of candidate name signatures matched against the live manifest, with
 * fallbacks. Resolved ids are cached here.
 *
 * Ported from `IFATCCompanion/Connect/IFStateMappingStore.swift`.
 */
class IFStateMappingStore {

    /** Logical states the app reads. */
    enum class Logical(val rawValue: String) {
        LATITUDE("latitude"),
        LONGITUDE("longitude"),
        ALTITUDE_MSL("altitudeMSL"),
        ALTITUDE_AGL("altitudeAGL"),
        GROUND_SPEED("groundSpeed"),
        INDICATED_AIRSPEED("indicatedAirspeed"),
        TRUE_AIRSPEED("trueAirspeed"),
        HEADING("heading"),

        /**
         * True (geographic) heading, distinct from the magnetic [HEADING]. Used to orient
         * the aircraft symbol on the true-north map.
         */
        TRUE_HEADING("trueHeading"),
        TRACK("track"),
        VERTICAL_SPEED("verticalSpeed"),
        ON_GROUND("onGround"),

        /** Autopilot approach mode (APPR) armed/engaged. */
        APPROACH_MODE("approachMode"),

        /** Parking brake set/released. */
        PARKING_BRAKE("parkingBrake"),
        G_FORCE("gForce"),
        BANK_ANGLE("bankAngle"),
        PITCH("pitch"),
        AIRCRAFT_NAME("aircraftName"),
        LIVERY_NAME("liveryName"),
        NEAREST_AIRPORT_ICAO("nearestAirportICAO"),

        /** Full flight plan as a string (`aircraft/0/flightplan`), parsed best-effort. */
        FLIGHT_PLAN("flightPlan"),

        /**
         * The detailed flight-plan document (`aircraft/0/flightplan/full_info`). This is
         * the rich JSON Infinite Flight serves with per-fix planned altitudes and nested
         * SID/STAR/approach procedure groups — the plain `flightplan` state only returns a
         * collapsed summary of the legs, so the cruise altitude and procedure names live
         * here.
         */
        FLIGHT_PLAN_FULL_INFO("flightPlanFullInfo"),

        /**
         * The textual route (`aircraft/0/flightplan/route`). Across IF versions the
         * `flightplan` state often serves only a collapsed summary of the legs, while the
         * route string carries every enroute fix — so it is read as a richer fallback when
         * the summary is sparse.
         */
        FLIGHT_PLAN_ROUTE("flightPlanRoute"),

        /**
         * Per-fix coordinates (`aircraft/0/flightplan/coordinates`), read so the route can
         * be drawn even when the summary carries no coordinates.
         */
        FLIGHT_PLAN_COORDINATES("flightPlanCoordinates"),

        // Multiplayer / ATC-staffing detection (all optional; coverage varies).
        ATC_ACTIVE("atcActive"),
        ATC_FACILITY_NAME("atcFacilityName"),
        ATC_FACILITY_COUNT("atcFacilityCount"),
        IS_ONLINE("isOnline"),
        SERVER_NAME("serverName"),

        /**
         * The name of the frequency the pilot is currently tuned to on COM1
         * (`aircraft/0/systems/comm_radios/com_1/name`) — e.g. "Ground", "KSFO Tower",
         * "Unicom". This is the location-aware standby signal: it names the frequency the
         * pilot is actually on, so the companion can defer only when that frequency is a
         * staffed human controller.
         */
        TUNED_COM_NAME("tunedComName"),

        /**
         * The COM1 frequency in MHz
         * (`aircraft/0/systems/comm_radios/com_1/frequency`), read for
         * diagnostics/logging.
         */
        TUNED_COM_FREQUENCY("tunedComFrequency"),

        /**
         * `environment/wind_velocity` — the wind speed at the aircraft, in metres per
         * second, as the sim itself models it. The app has always *solved* the wind by
         * inverting the wind triangle rather than reading it, because the states weren't
         * known to exist; read directly it needs no differencing of two ~450 kt vectors and
         * survives the regimes the triangle can't solve (no track, no TAS, low speed).
         */
        WIND_VELOCITY("windVelocity"),

        /**
         * `environment/wind_direction_true` — the wind direction, in radians, true. Whether
         * that is the direction the wind blows **from** (the meteorological convention the
         * app uses internally) or the direction it blows **toward** is not something the
         * state name settles, so it is reported alongside the solved wind rather than
         * trusted blind.
         */
        WIND_DIRECTION_TRUE("windDirectionTrue"),
        ;

        /**
         * Candidate name signatures (normalised, lowercased, separators removed), in
         * priority order.
         */
        val signatures: List<String>
            get() = when (this) {
                LATITUDE -> listOf("aircraftlatitude", "latitude")
                LONGITUDE -> listOf("aircraftlongitude", "longitude")
                ALTITUDE_MSL -> listOf("altitudemsl", "msl", "altitude")
                ALTITUDE_AGL -> listOf("altitudeagl", "agl")
                GROUND_SPEED -> listOf("groundspeed")
                INDICATED_AIRSPEED -> listOf("indicatedairspeed", "ias")
                TRUE_AIRSPEED -> listOf("trueairspeed", "tas")
                HEADING -> listOf("headingmagnetic", "heading", "magneticheading")
                TRUE_HEADING -> listOf("headingtrue", "trueheading")
                // `aircraft/0/course` is the course over the ground on the builds that expose
                // it, and is the only track-like *measurement* in the manifest — the entries
                // actually ending in "track" are flight-plan booleans
                // (`is_on_flight_plan_track`), which the numeric filter now keeps out of this
                // key entirely.
                TRACK -> listOf("gpstrack", "track", "courseovertheground", "course")
                VERTICAL_SPEED -> listOf("verticalspeed", "vspeed", "verticalspeedfpm")
                ON_GROUND -> listOf("isonground", "onground")
                APPROACH_MODE -> listOf(
                    "autopilotapproach", "approachmode", "apprmode", "isapproach", "appr",
                    "approachhold",
                )
                PARKING_BRAKE -> listOf("parkingbrake", "parkbrake", "brakeparking")
                G_FORCE -> listOf("gforce", "accelerationgforce")
                BANK_ANGLE -> listOf("bankangledegrees", "bankangle", "bank")
                PITCH -> listOf("pitchdegrees", "pitch")
                AIRCRAFT_NAME -> listOf("aircraftname", "aircraftstate.name", "name")
                LIVERY_NAME -> listOf("liveryname", "livery")
                NEAREST_AIRPORT_ICAO -> listOf("nearestairporticao", "nearestairport")
                FLIGHT_PLAN -> listOf("flightplan", "flightplanstring", "fpl")
                FLIGHT_PLAN_FULL_INFO -> listOf(
                    "flightplanfullinfo", "fullinfo", "flightplandetailed", "flightplaninfo",
                )
                FLIGHT_PLAN_ROUTE -> listOf("flightplanroute", "planroute")
                FLIGHT_PLAN_COORDINATES -> listOf("flightplancoordinates", "plancoordinates")
                ATC_ACTIVE -> listOf("isatcactive", "atcactive", "atcisactive", "controlleractive")
                ATC_FACILITY_NAME -> listOf(
                    "activeatcfacilityname", "atcfacilityname", "controllerfacility",
                    "atcfacilit", "atcname", "atcusername", "controllername",
                )
                ATC_FACILITY_COUNT -> listOf(
                    "activeatcfacilitycount", "atcfacilitycount", "activeatccount", "atccount",
                )
                IS_ONLINE -> listOf("ismultiplayer", "isonline", "online", "multiplayer")
                SERVER_NAME -> listOf("servername", "sessionname", "server")
                TUNED_COM_NAME -> listOf(
                    "com1name", "comm1name", "commradioscom1name", "activefrequencyname",
                )
                TUNED_COM_FREQUENCY -> listOf(
                    "com1frequency", "comm1frequency", "commradioscom1frequency",
                )
                // `wind_gust_velocity` normalises to "windgustvelocity", which neither ends
                // with nor contains "windvelocity", so the steady wind is never read off the
                // gust.
                WIND_VELOCITY -> listOf("windvelocity", "windspeed")
                WIND_DIRECTION_TRUE -> listOf("winddirectiontrue", "winddirection")
            }

        /**
         * What kind of value this key stands for. Name matching alone is not enough to
         * identify a state: Infinite Flight's manifest carries ~1700 entries plus every
         * command, and a signature matched across all of them lands on whatever shares a
         * word. `track` matched `aircraft/0/is_on_flight_plan_track` — a *bool* — so the
         * ground track read as 0° or 57°, and `parkingbrake` matched the `commands/…` entry
         * beside the state. Filtering the candidates by type first makes the match mean what
         * the name says.
         */
        val valueKind: ValueKind
            get() = when (this) {
                ON_GROUND, APPROACH_MODE, PARKING_BRAKE, ATC_ACTIVE, IS_ONLINE -> ValueKind.BOOLEAN
                AIRCRAFT_NAME, LIVERY_NAME, NEAREST_AIRPORT_ICAO, FLIGHT_PLAN,
                FLIGHT_PLAN_FULL_INFO, FLIGHT_PLAN_ROUTE, FLIGHT_PLAN_COORDINATES,
                ATC_FACILITY_NAME, SERVER_NAME, TUNED_COM_NAME,
                -> ValueKind.TEXT

                else -> ValueKind.NUMERIC
            }
    }

    /**
     * The family of manifest types a logical key may resolve onto. Commands
     * ([IFDataType.UNKNOWN]) are excluded from all three — they are actions, never
     * readable values.
     */
    enum class ValueKind {
        NUMERIC,
        BOOLEAN,
        TEXT,
        ;

        fun accepts(type: IFDataType): Boolean = when (this) {
            // A measurement is never a bool; an int one (a count, an enum-backed state) is fine.
            NUMERIC -> type == IFDataType.INT32 || type == IFDataType.FLOAT ||
                type == IFDataType.DOUBLE || type == IFDataType.LONG
            // Some builds expose an on/off state as an int rather than a bool.
            BOOLEAN -> type == IFDataType.BOOLEAN || type == IFDataType.INT32
            TEXT -> type == IFDataType.STRING
        }
    }

    var resolved: Map<Logical, IFManifestEntry> = emptyMap()
        private set

    /**
     * A group of states that must be read in one angular convention, because they come
     * out of one part of the sim together.
     *
     * **Each family decides its own units, from its own readings.** They were decided
     * together, on the reasoning that every angle comes out of "the same API in the same
     * convention" — and that is exactly where the field failure came from:
     * `environment/wind_direction_true` reports the weather in degrees on builds whose
     * *aircraft* states are radians. One wind from 331 then witnessed "degrees" on every
     * single snapshot, which pinned the aircraft's heading — 084° magnetic arrives as
     * 1.466 rad, and read as degrees it is shown as 001° — and kept re-witnessing, so the
     * contradiction below never got a run to accumulate either. The nose sat on north on
     * the Flight tab, the taxi map and the weather map at once.
     *
     * The aircraft's own attitude states (`aircraft/0/heading_magnetic`, `heading_true`,
     * the ground track, and the bank/pitch that follow them) genuinely are one group. The
     * weather is a different subsystem and gets no vote on the nose.
     */
    enum class AngleFamily {
        /** `aircraft/0/…` — heading, true heading, ground track, bank, pitch. */
        AIRCRAFT,

        /** `environment/…` — the reported wind direction. */
        ENVIRONMENT,
    }

    private val units = mutableMapOf<AngleFamily, AngleUnits>()

    /** Whether the aircraft's angle states are currently read as **degrees** rather than radians. */
    val anglesProvedDegrees: Boolean get() = units[AngleFamily.AIRCRAFT]?.provedDegrees == true

    /** The same decision for the sim's reported wind direction, made from its own readings. */
    val windAnglesProvedDegrees: Boolean
        get() = units[AngleFamily.ENVIRONMENT]?.provedDegrees == true

    /** One angle exactly as the sim reported it, before any conversion. */
    data class RawAngleReading(val name: String, val value: Double)

    /**
     * The raw angle readings behind the current units decisions. Logged whenever a
     * decision changes: the whole radians-vs-degrees question turns on the *magnitude* of
     * these numbers, and until now nothing anywhere recorded them — so a heading shown as
     * 001° while the sim's own panel read 084° could only be argued about.
     */
    var lastRawAngles: List<RawAngleReading> = emptyList()
        private set

    fun noteRawAngles(readings: List<RawAngleReading>) {
        lastRawAngles = readings
    }

    /**
     * Record what one telemetry snapshot's angles witnessed about one family's units.
     *
     * @param family which group of states these readings came from. A family is never
     *   told about another's readings — see [AngleFamily].
     * @param provesDegrees an angle in the snapshot was too large to be radians *and*
     *   still a plausible compass angle. A reading beyond a full circle in degrees is a
     *   corrupt read, not evidence of the units.
     * @param anyAboveRadianCircle any angle exceeded a full circle in radians, plausible
     *   or not. Only used to keep the radians disproof honest — a build genuinely
     *   reporting degrees produces these constantly, so they reset the disproof run.
     * @param rawHeading the snapshot's raw heading, before any conversion. The disproof
     *   needs a value that sweeps the compass over a flight, so only the aircraft family
     *   has one.
     */
    fun noteAngleSnapshot(
        family: AngleFamily,
        provesDegrees: Boolean,
        anyAboveRadianCircle: Boolean,
        rawHeading: Double?,
    ) {
        val state = units.getOrPut(family) { AngleUnits() }
        state.note(provesDegrees, anyAboveRadianCircle, rawHeading)
    }

    /**
     * One family's radians-vs-degrees decision, and the evidence behind it.
     *
     * The decision persists across snapshots, because one snapshot can fail to witness
     * anything: with the nose and the track both within ~6° of north there is no angle too
     * large to be radians, so a build reporting degrees was read as radians and every
     * angle in it multiplied by 57.3 — a 4° nose becoming 229°, and the two headings'
     * one-degree difference becoming tens of degrees of "variation" that went straight
     * into the departure vector. A north-facing runway lines an aircraft up for exactly
     * that and holds it there.
     *
     * But it is **not** taken on a single reading and **not** irreversible, because both
     * of those turn one bad number into a session-long fault — a radians build read as
     * degrees shows every heading in 0…6.28 as 0–6°, so the nose reads north whichever way
     * it points:
     *
     * - **Proof needs corroboration** ([DEGREE_WITNESSES_TO_PROVE] consecutive snapshots).
     *   A genuine degrees build witnesses on every snapshot the nose is off north, so it
     *   still settles within a second; a lone anomalous reading — a desynchronised
     *   response frame, a state that isn't the angle its name suggests — no longer settles
     *   anything.
     * - **The proof can be contradicted.** No single reading can prove radians (every
     *   radian value is also a valid degree value), but a *run* of them can: a heading
     *   that visits three of the four quadrants of the 0…2π circle without one reading
     *   ever exceeding a full circle in radians is an aircraft turning through the
     *   compass, not one holding within a 6° arc of north for a dozen samples. That clears
     *   the proof and the headings come right without a relaunch.
     */
    private class AngleUnits {
        var provedDegrees = false
            private set

        private var consecutiveDegreeWitnesses = 0
        private var radianOnlySamples = 0
        private val radianHeadingQuadrants = mutableSetOf<Int>()

        fun note(provesDegrees: Boolean, anyAboveRadianCircle: Boolean, rawHeading: Double?) {
            if (!provedDegrees) {
                if (provesDegrees) {
                    consecutiveDegreeWitnesses += 1
                    if (consecutiveDegreeWitnesses >= DEGREE_WITNESSES_TO_PROVE) {
                        provedDegrees = true
                        resetDisproof()
                    }
                } else {
                    consecutiveDegreeWitnesses = 0
                }
                return
            }
            // Proved — watch for the contradiction that means it was taken in error.
            if (anyAboveRadianCircle) {
                resetDisproof()
                return
            }
            if (rawHeading == null || !rawHeading.isFinite()) return
            radianOnlySamples += 1
            radianHeadingQuadrants += radianQuadrant(rawHeading)
            if (radianOnlySamples >= RADIAN_SAMPLES_TO_DISPROVE &&
                radianHeadingQuadrants.size >= RADIAN_QUADRANTS_TO_DISPROVE
            ) {
                provedDegrees = false
                consecutiveDegreeWitnesses = 0
                resetDisproof()
            }
        }

        private fun resetDisproof() {
            radianOnlySamples = 0
            radianHeadingQuadrants.clear()
        }
    }

    /**
     * Resolve all logical keys against a freshly parsed manifest. Matching is
     * exact-suffix first, then substring, honouring signature priority. A fresh manifest
     * means a fresh connection, so the units proof starts over with it — the next session
     * may be a different Infinite Flight build.
     */
    fun resolve(entries: List<IFManifestEntry>) {
        units.clear()
        resolved = buildMap {
            for (logical in Logical.entries) {
                bestMatch(logical.signatures, entries, logical.valueKind)?.let { put(logical, it) }
            }
        }
    }

    fun entry(logical: Logical): IFManifestEntry? = resolved[logical]

    val unresolvedKeys: List<Logical>
        get() = Logical.entries.filter { resolved[it] == null }

    /**
     * Find the best manifest entry for an ordered list of candidate signatures,
     * considering only entries that could actually carry the value the logical key stands
     * for.
     */
    private fun bestMatch(
        signatures: List<String>,
        entries: List<IFManifestEntry>,
        kind: ValueKind,
    ): IFManifestEntry? {
        val candidates = entries.filter { kind.accepts(it.type) }
        for (sig in signatures) {
            // Prefer an entry whose normalised key ends with the signature.
            candidates.firstOrNull { it.matchKey.endsWith(sig) }?.let { return it }
            // Then any entry containing the signature.
            candidates.firstOrNull { it.matchKey.contains(sig) }?.let { return it }
        }
        return null
    }

    companion object {
        /** Consecutive witnessing snapshots required before degrees is taken as proved. */
        const val DEGREE_WITNESSES_TO_PROVE = 2

        /** Radian-only snapshots required before a degrees proof is treated as contradicted. */
        const val RADIAN_SAMPLES_TO_DISPROVE = 12

        /** Distinct quadrants of the 0…2π circle the heading must visit in that run. */
        const val RADIAN_QUADRANTS_TO_DISPROVE = 3

        /** Which quarter of the 0…2π circle a raw heading falls in (0–3), wrapping negatives. */
        fun radianQuadrant(value: Double): Int {
            val circle = 2 * PI
            var wrapped = value % circle
            if (wrapped < 0) wrapped += circle
            return min(3, (wrapped / (circle / 4)).toInt())
        }
    }
}
