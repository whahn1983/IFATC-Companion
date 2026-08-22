package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.phraseology.AirlineDatabase
import com.h3consultingpartners.ifatccompanion.core.surface.AircraftSizeClass
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Which end of the flight an automatic stand assignment is being made for. The two ends
 * are assigned independently — a blank departure gate is filled from the origin field's
 * stands, a blank arrival gate from the destination's.
 *
 * Ported from `IFATCCompanion/AirportSurface/GateAssignment.swift`.
 */
enum class GateRole(val rawValue: String) {
    DEPARTURE("departure"),
    ARRIVAL("arrival"),
    ;

    val title: String
        get() = when (this) {
            DEPARTURE -> "departure"
            ARRIVAL -> "arrival"
        }
}

/**
 * The marker recorded alongside an automatically-assigned gate: which airport it was
 * picked for, and which stand was picked. It is what lets the automatic assignment tell
 * **its own** value apart from one the pilot typed — the whole feature is conditional on
 * the field having been left blank, so it must never overwrite a gate a pilot entered,
 * and must equally never leave a stale gate from the last flight's airport in place.
 *
 * Encoded as `ICAO:GATE` so it persists in the same string-shaped preference store as
 * every other flight override.
 *
 * A plain class rather than a `data class` because the Swift initializer normalizes both
 * halves (upper-casing the ICAO, trimming both) before storing them, and equality has to
 * be on the normalized values.
 */
class AutoGateStamp(
    icao: String,
    gate: String,
    /**
     * Whether the gate was read off the aircraft's own position — it was parked on that
     * stand — rather than chosen from the field's stand list. A *chosen* gate is worth
     * replacing the moment the aircraft's position says which stand it is actually on; a
     * position-derived one is already the truth and is never re-picked.
     */
    val fromAircraftPosition: Boolean = false,
) {
    val icao: String = icao.uppercase().trim()
    val gate: String = gate.trim()

    /**
     * `"KIAH:C24"`, or `"KIAH:C24:P"` for a gate read off the aircraft's position. Empty
     * when either of the first two halves is missing (nothing to remember).
     */
    val encoded: String
        get() {
            if (this.icao.isEmpty() || this.gate.isEmpty()) return ""
            return if (fromAircraftPosition) "${this.icao}:${this.gate}:$POSITION_FLAG" else "${this.icao}:${this.gate}"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutoGateStamp) return false
        return icao == other.icao && gate == other.gate &&
            fromAircraftPosition == other.fromAircraftPosition
    }

    override fun hashCode(): Int =
        (icao.hashCode() * 31 + gate.hashCode()) * 31 + fromAircraftPosition.hashCode()

    override fun toString(): String =
        "AutoGateStamp(icao=$icao, gate=$gate, fromAircraftPosition=$fromAircraftPosition)"

    companion object {
        private const val POSITION_FLAG = "P"

        /**
         * Decode a stored marker. Returns null for an empty/garbled value, which reads as
         * "the app has not assigned a gate" — the safe direction, because an unrecognised
         * marker leaves whatever is in the field alone. A two-part marker (everything written
         * before the position-derived gate existed) decodes as a chosen gate.
         */
        fun decode(encoded: String): AutoGateStamp? {
            // Swift: `split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)`.
            val parts = encoded.split(":", limit = 3)
            if (parts.size != 2 && parts.size != 3) return null
            val icao = parts[0].uppercase().trim()
            val gate = parts[1].trim()
            if (icao.isEmpty() || gate.isEmpty()) return null
            val fromPosition = parts.size == 3 && parts[2].trim().uppercase() == POSITION_FLAG
            return AutoGateStamp(icao, gate, fromPosition)
        }
    }
}

/**
 * What an OSM stand's tags say about the stand, normalized. Every field is best-effort:
 * stand tagging in OSM is sparse and inconsistent between airports, so an unknown never
 * disqualifies a stand — it only stops it from being *preferred* over one that does carry
 * the matching data.
 *
 * Tags read (see https://wiki.openstreetmap.org/wiki/Key:aeroway):
 *   • `aircraft:type` — what the stand is sized for: an airframe ("A320", "B738;B739"),
 *     a size band ("heavy", "wide_body"), or a category ("helicopter", "light_aircraft").
 *   • `operator`, `operator:en`, `operator:short` — the airline or handler working it.
 *   • `access` — `no`/`private` marks a stand this flight has no business on.
 *   • `ref`/`name` — the identifier a controller says ("B44"). Already folded into
 *     [SurfaceParking.name] by the OSM normalizer, and required: a stand with no
 *     identifier can't be named in a clearance, so it is never assigned.
 */
data class StandProfile(
    /** The largest size class the tags say the stand takes, or null when untagged. */
    val maxClass: AircraftSizeClass?,
    /** The stand's `aircraft:type` names rotorcraft only. */
    val helicopterOnly: Boolean = false,
    /** Operator / handler names found on the stand, lowercased for matching. */
    val operatorNames: List<String> = emptyList(),
    /** `access=no` or `access=private`. */
    val restricted: Boolean = false,
    /** A cargo / freight position, from the operator or the stand name. */
    val cargo: Boolean = false,
    /**
     * A working position rather than a parking stand — de-icing pad, maintenance or
     * hangar stand, engine run-up or compass pad. Never assigned to a flight.
     */
    val servicePosition: Boolean = false,
) {

    /**
     * Whether a stand tagged with this profile can take the aircraft at all, size-wise.
     * An untagged stand is assumed usable (unknown stays unknown).
     */
    fun accepts(aircraft: AircraftSizeClass): Boolean {
        val m = maxClass ?: return true
        return m.rank >= aircraft.rank
    }

    /**
     * How much bigger the stand is than the aircraft needs, in size-class steps. 0 is a
     * snug fit. Untagged stands report 0 — there is nothing to grade them on.
     */
    fun fitGap(aircraft: AircraftSizeClass): Int {
        val m = maxClass ?: return 0
        return max(0, m.rank - aircraft.rank)
    }

    companion object {
        /** Read a stand's tags into a profile. */
        fun from(tags: Map<String, String>, standName: String): StandProfile {
            // Size, from `aircraft:type` (and the `aircraft` / `aircraft:size` variants some
            // mappers use). Tokens are separated by ";" in OSM's multi-value convention;
            // "," and "/" are accepted too because both show up in practice.
            val typeValue = listOfNotNull(tags["aircraft:type"], tags["aircraft"], tags["aircraft:size"])
                .joinToString(";")
            var sawHelicopter = false
            var sawFixedWing = false
            var maxClass: AircraftSizeClass? = null
            for (token in tokenize(typeValue)) {
                when (val classified = classify(token)) {
                    is TypeToken.Helicopter -> sawHelicopter = true
                    is TypeToken.Size -> {
                        sawFixedWing = true
                        val current = maxClass
                        if (current != null) {
                            if (classified.cls.rank > current.rank) maxClass = classified.cls
                        } else {
                            maxClass = classified.cls
                        }
                    }
                    is TypeToken.Unknown -> continue
                }
            }
            val helicopterOnly = sawHelicopter && !sawFixedWing

            // Operator / handler.
            val operators = mutableListOf<String>()
            for (key in listOf("operator", "operator:en", "operator:short", "network", "owner")) {
                val raw = tags[key] ?: continue
                // A multi-operator stand ("Delta;KLM") counts as either airline's.
                for (part in raw.split(';', '|')) {
                    val name = part.trim().lowercase()
                    if (name.isNotEmpty()) operators.add(name)
                }
            }

            val access = tags["access"]?.lowercase()?.trim { it == ' ' || it == '\t' }
            val restricted = access != null && (access == "no" || access == "private")

            // Cargo and service positions have no dedicated OSM key, so they are read from the
            // descriptive tags mappers actually reach for. The two texts are kept apart on
            // purpose: "Lufthansa Cargo" in `operator` does mark a freight stand, but an airport
            // authority's name in `operator` must never be searched for words like "maintenance"
            // — that is what the stand's own purpose tags are for.
            val purposeText = listOf(
                standName, tags["name"] ?: "", tags["description"] ?: "",
                tags["parking_position"] ?: "", tags["gate"] ?: "",
                tags["usage"] ?: "", tags["aeroway:type"] ?: "",
            ).joinToString(" ").lowercase()
            val operatorText = operators.joinToString(" ")
            return StandProfile(
                maxClass = maxClass,
                helicopterOnly = helicopterOnly,
                operatorNames = operators,
                restricted = restricted,
                cargo = cargoWords.any { purposeText.contains(it) || operatorText.contains(it) },
                servicePosition = serviceWords.any { purposeText.contains(it) },
            )
        }

        // MARK: - Tag vocabulary

        private sealed interface TypeToken {
            data class Size(val cls: AircraftSizeClass) : TypeToken
            object Helicopter : TypeToken
            object Unknown : TypeToken
        }

        /**
         * Split a multi-value tag and normalize each token for matching (lowercased, spaces
         * and hyphens folded to "_" so "light aircraft"/"light-aircraft"/"light_aircraft" all
         * land on the same key).
         */
        private fun tokenize(value: String): List<String> =
            value.split(';', ',', '/', '|')
                .map { part ->
                    part.trim().lowercase().replace(" ", "_").replace("-", "_")
                }
                .filter { it.isNotEmpty() }

        private fun classify(token: String): TypeToken {
            if (helicopterWords.any { token.contains(it) }) return TypeToken.Helicopter
            sizeWords[token]?.let { return TypeToken.Size(it) }
            // Not a size band — try it as an airframe designator ("a320", "b77w"). Strict, so
            // a token that matches nothing stays unknown rather than defaulting to a 737 stand.
            AircraftSizeClass.classifyStrict(token)?.let { return TypeToken.Size(it) }
            return TypeToken.Unknown
        }

        private val helicopterWords = listOf("helicopter", "helipad", "rotor")

        /**
         * `aircraft:type` values that name a size band rather than an airframe. Matched
         * exactly (not by substring) so short words never swallow an unrelated token.
         * The ICAO aerodrome reference codes are included in their `code_x` spelling: C is the
         * 737/A320 band, D the 767, E the 777/747, F the A380.
         */
        private val sizeWords: Map<String, AircraftSizeClass> = mapOf(
            "light" to AircraftSizeClass.LIGHT,
            "light_aircraft" to AircraftSizeClass.LIGHT,
            "lightaircraft" to AircraftSizeClass.LIGHT,
            "general_aviation" to AircraftSizeClass.LIGHT,
            "ga" to AircraftSizeClass.LIGHT,
            "glider" to AircraftSizeClass.LIGHT,
            "ultralight" to AircraftSizeClass.LIGHT,
            "microlight" to AircraftSizeClass.LIGHT,
            "piston" to AircraftSizeClass.LIGHT,
            "single_engine" to AircraftSizeClass.LIGHT,
            "code_a" to AircraftSizeClass.LIGHT,
            "code_b" to AircraftSizeClass.SMALL,
            "small" to AircraftSizeClass.SMALL,
            "commuter" to AircraftSizeClass.SMALL,
            "regional" to AircraftSizeClass.SMALL,
            "regional_jet" to AircraftSizeClass.SMALL,
            "turboprop" to AircraftSizeClass.SMALL,
            "prop" to AircraftSizeClass.SMALL,
            "props" to AircraftSizeClass.SMALL,
            "medium" to AircraftSizeClass.MEDIUM,
            "narrow_body" to AircraftSizeClass.MEDIUM,
            "narrowbody" to AircraftSizeClass.MEDIUM,
            "narrow" to AircraftSizeClass.MEDIUM,
            "code_c" to AircraftSizeClass.MEDIUM,
            "large" to AircraftSizeClass.LARGE,
            "wide_body" to AircraftSizeClass.LARGE,
            "widebody" to AircraftSizeClass.LARGE,
            "wide" to AircraftSizeClass.LARGE,
            "code_d" to AircraftSizeClass.LARGE,
            "jet" to AircraftSizeClass.LARGE,
            "airliner" to AircraftSizeClass.LARGE,
            "heavy" to AircraftSizeClass.HEAVY,
            "jumbo" to AircraftSizeClass.HEAVY,
            "super" to AircraftSizeClass.HEAVY,
            "code_e" to AircraftSizeClass.HEAVY,
            "code_f" to AircraftSizeClass.HEAVY,
        )

        private val cargoWords = listOf("cargo", "freight", "fracht", "fret", "carga")

        /**
         * Purpose words that mark a working position rather than a stand a flight parks on.
         * Deliberately narrow — these are matched against the stand's *purpose* tags only, and
         * a word that could plausibly appear in an airport or city name is left out so a real
         * stand is never dropped for looking like a de-icing pad.
         */
        private val serviceWords = listOf(
            "deic", "de-ic", "de_ic", "anti_ice", "anti-ice",
            "maintenance", "hangar", "hanger", "workshop", "engine_run", "run_up",
            "compass_swing", "aircraft_wash", "abandoned", "disused",
        )
    }
}

/**
 * Airline brand names as they actually appear in OSM `operator` tags, keyed by ICAO
 * designator, plus the cargo carriers.
 *
 * The *spoken* telephony name in [AirlineDatabase] is frequently not the brand a mapper
 * types — "Speedbird" is British Airways, "Airfrans" is Air France — so the carriers whose
 * two differ are listed here. For everyone else the telephony name itself is tried as a
 * fragment, which already covers the many airlines whose call name *is* their brand
 * ("Lufthansa", "KLM", "Delta", "Emirates"). Best-effort by design: a miss simply means the
 * stand is chosen on size and type rather than on the airline.
 */
object StandOperators {

    /** Brand fragments (lowercased) for an ICAO or IATA airline designator. */
    fun brandNames(designator: String): List<String> {
        val key = designator.uppercase().trim { it == ' ' || it == '\t' }
        if (key.isEmpty()) return emptyList()
        return brands[key] ?: emptyList()
    }

    /**
     * Whether a designator belongs to a cargo carrier — a freight flight belongs on a
     * cargo stand, and a passenger flight does not.
     */
    fun isCargoDesignator(designator: String): Boolean =
        cargoDesignators.contains(designator.uppercase().trim { it == ' ' || it == '\t' })

    private val brands: Map<String, List<String>> = mapOf(
        // North America
        "AAL" to listOf("american airlines", "american"), "DAL" to listOf("delta"), "UAL" to listOf("united"),
        "SWA" to listOf("southwest"), "ASA" to listOf("alaska"), "JBU" to listOf("jetblue"), "NKS" to listOf("spirit"),
        "FFT" to listOf("frontier"), "HAL" to listOf("hawaiian"), "SCX" to listOf("sun country"),
        "AAY" to listOf("allegiant"), "SKW" to listOf("skywest"), "ENY" to listOf("envoy"),
        "RPA" to listOf("republic"), "EDV" to listOf("endeavor"), "QXE" to listOf("horizon"),
        "ACA" to listOf("air canada"), "WJA" to listOf("westjet"), "TSC" to listOf("air transat"),
        "POE" to listOf("porter"), "AMX" to listOf("aeromexico", "aeroméxico"), "VOI" to listOf("volaris"),
        // Europe
        "BAW" to listOf("british airways"), "VIR" to listOf("virgin atlantic"), "EZY" to listOf("easyjet"),
        "RYR" to listOf("ryanair"), "EXS" to listOf("jet2"), "DLH" to listOf("lufthansa"),
        "EWG" to listOf("eurowings"), "AFR" to listOf("air france"), "KLM" to listOf("klm"),
        "TRA" to listOf("transavia"), "SAS" to listOf("scandinavian", "sas"), "IBE" to listOf("iberia"),
        "VLG" to listOf("vueling"), "AEA" to listOf("air europa"), "AZA" to listOf("alitalia"),
        "ITY" to listOf("ita airways"), "SWR" to listOf("swiss"), "AUA" to listOf("austrian"),
        "BEL" to listOf("brussels airlines"), "TAP" to listOf("tap", "air portugal"),
        "FIN" to listOf("finnair"), "NAX" to listOf("norwegian"), "NOZ" to listOf("norwegian"),
        "WZZ" to listOf("wizz air"), "LOT" to listOf("lot"), "CSA" to listOf("czech airlines"),
        "AEE" to listOf("aegean"), "THY" to listOf("turkish airlines"), "AFL" to listOf("aeroflot"),
        "ICE" to listOf("icelandair"), "EIN" to listOf("aer lingus"), "TVS" to listOf("smartwings"),
        // Middle East / Africa
        "UAE" to listOf("emirates"), "QTR" to listOf("qatar airways"), "ETD" to listOf("etihad"),
        "SVA" to listOf("saudia"), "MSR" to listOf("egyptair"), "ETH" to listOf("ethiopian"),
        "RJA" to listOf("royal jordanian"), "ELY" to listOf("el al"), "SAA" to listOf("south african"),
        "RAM" to listOf("royal air maroc"), "KQA" to listOf("kenya airways"),
        // Asia / Pacific / South America
        "QFA" to listOf("qantas"), "ANZ" to listOf("air new zealand"), "VOZ" to listOf("virgin australia"),
        "SIA" to listOf("singapore airlines"), "CPA" to listOf("cathay"), "JAL" to listOf("japan airlines"),
        "ANA" to listOf("all nippon", "ana"), "KAL" to listOf("korean air"), "AAR" to listOf("asiana"),
        "CCA" to listOf("air china"), "CES" to listOf("china eastern"), "CSN" to listOf("china southern"),
        "THA" to listOf("thai airways"), "MAS" to listOf("malaysia airlines"), "GIA" to listOf("garuda"),
        "PAL" to listOf("philippine airlines"), "AIC" to listOf("air india"), "IGO" to listOf("indigo"),
        "LAN" to listOf("latam"), "TAM" to listOf("latam"), "GLO" to listOf("gol"), "AZU" to listOf("azul"),
        "AVA" to listOf("avianca"), "CMP" to listOf("copa"), "ARG" to listOf("aerolineas argentinas"),
        // Cargo
        "FDX" to listOf("fedex", "federal express"), "UPS" to listOf("ups", "united parcel"),
        "GTI" to listOf("atlas air"), "CLX" to listOf("cargolux"), "GEC" to listOf("lufthansa cargo"),
        "CJT" to listOf("cargojet"), "NCA" to listOf("nippon cargo"), "CKS" to listOf("kalitta"),
        "ABX" to listOf("abx air"), "ATN" to listOf("air transport international"),
        "BOX" to listOf("aerologic"), "DHK" to listOf("dhl"), "BCS" to listOf("dhl"), "TAY" to listOf("dhl"),
        "MPH" to listOf("martinair"), "SQC" to listOf("singapore airlines cargo"),
    )

    private val cargoDesignators: Set<String> = setOf(
        "FDX", "UPS", "GTI", "CLX", "GEC", "CJT", "NCA", "CKS", "ABX", "ATN",
        "BOX", "DHK", "BCS", "TAY", "MPH", "SQC", "5X", "FX",
    )
}

/**
 * Picks a realistic stand for a flight from an airport's OpenStreetMap stand data.
 *
 * The assignment is deliberately data-led rather than invented. When the aircraft is already
 * **parked on a mapped stand** at the departure field, that stand is the gate — nothing is
 * chosen at all, it is simply read off the aircraft's position. Otherwise the stand comes from
 * the airport's own OSM extract, and the tags that extract carries are used for as much of the
 * choice as they support —
 *   • the airline's **own** stands when `operator` names the carrier flying;
 *   • a stand **sized for the aircraft** when `aircraft:type` says what fits, preferring
 *     the snuggest fit so a 737 doesn't take the widebody stand next to an empty 737 one;
 *   • a terminal `aeroway=gate` for an airliner, a remote `parking_position` for light GA;
 *   • cargo positions for freight flights and only for freight flights.
 * Where the tags say nothing — the common case at most fields — it falls back to a random
 * pick among the plausible stands, which is all the user asked for: a real stand at the
 * real airport that the taxi router can take them to.
 *
 * Nothing here is authoritative. OSM stand data is community-sourced and incomplete, and a
 * real gate assignment comes from the airline, not from a map. This is a simulation aid.
 */
object GateAssigner {

    /** What the picker knows about the flight being assigned. */
    class FlightContext(
        val callsign: String = "",
        val airline: String = "",
        val aircraftName: String? = null,
        aircraftClass: AircraftSizeClass? = null,
        /**
         * Where the aircraft is **parked**, when it is: supplied only when telemetry says
         * the aircraft is stopped on the ground, so a non-null value always means "this is
         * where the aircraft is sitting". Null while airborne, taxiing, or before the first
         * fix. When it lands on a mapped stand, the departure gate stops being a guess.
         */
        val parkedPosition: GeoCoordinate? = null,
    ) {
        /** Defaults to the class implied by [aircraftName] (MEDIUM when unreported). */
        val aircraftClass: AircraftSizeClass = aircraftClass ?: AircraftSizeClass.classify(aircraftName)

        /**
         * The airline designator flown, from the callsign ("UAL598" → "UAL"). Empty for a
         * tail number or an unparseable callsign.
         */
        val designator: String get() = AirlineDatabase.parse(callsign)?.designator ?: ""

        /**
         * Brand fragments to look for in a stand's `operator` tag: the designator's known
         * brand names, the resolved telephony name, and the airline name on the plan (which
         * for a plan read from Infinite Flight is already the telephony name).
         */
        val operatorFragments: List<String>
            get() {
                val out = StandOperators.brandNames(designator).toMutableList()
                AirlineDatabase.parse(callsign)?.telephony?.let { out.add(it.lowercase()) }
                val planAirline = airline.trim().lowercase()
                if (planAirline.length >= 3) out.add(planAirline)
                // Two-letter fragments would match half the alphabet inside a longer name.
                return out.filter { it.length >= 3 }.toSet().toList()
            }

        /** Whether this is a freight flight (so cargo stands are the right ones). */
        val isCargo: Boolean
            get() {
                if (StandOperators.isCargoDesignator(designator)) return true
                val text = "$airline $callsign".lowercase()
                return text.contains("cargo") || text.contains("freight")
            }

        /** Airliners belong at a terminal gate; light GA belongs on a parking stand. */
        val prefersTerminalGate: Boolean get() = aircraftClass != AircraftSizeClass.LIGHT

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FlightContext) return false
            return callsign == other.callsign && airline == other.airline &&
                aircraftName == other.aircraftName && aircraftClass == other.aircraftClass &&
                parkedPosition == other.parkedPosition
        }

        override fun hashCode(): Int {
            var r = callsign.hashCode()
            r = r * 31 + airline.hashCode()
            r = r * 31 + (aircraftName?.hashCode() ?: 0)
            r = r * 31 + aircraftClass.hashCode()
            r = r * 31 + (parkedPosition?.hashCode() ?: 0)
            return r
        }
    }

    /**
     * One automatic assignment, with enough detail for the diagnostics log to explain
     * *why* this stand was chosen when a pilot asks.
     */
    data class Assignment(
        val gate: String,
        val osmID: String,
        val coordinate: GeoCoordinate,
        val matchedOperator: Boolean,
        val matchedAircraftType: Boolean,
        /**
         * Whether the stand was read off the aircraft's own position — it is parked there —
         * rather than chosen from the field's stand list. A `true` here is not a guess.
         */
        val fromAircraftPosition: Boolean,
        /** How many stands were in the winning band the pick was drawn from. */
        val tiedCandidates: Int,
        /** How many usable stands the field offered at all. */
        val totalCandidates: Int,
        /** Short human-readable rationale, e.g. "operator match, A320-class stand". */
        val reason: String,
    )

    /**
     * Whether the app may write a gate into a field for [icao].
     *
     * This is the "if and only if the pilot left it blank" rule, and it is the whole safety
     * story of the feature:
     *   • blank field → assign;
     *   • field still holding the value the app itself stamped, for a **different** airport
     *     → the last flight's automatic gate is stale, so reassign;
     *   • field holding the app's own stamp for **this** airport → already assigned, leave
     *     it (so the gate doesn't re-roll on every telemetry tick);
     *   • anything else → the pilot typed it. Never touch it.
     */
    fun mayAssign(current: String, stamp: String, icao: String): Boolean {
        val entered = current.trim()
        if (entered.isEmpty()) return true
        val decoded = AutoGateStamp.decode(stamp) ?: return false
        if (!decoded.gate.equals(entered, ignoreCase = true)) return false
        val key = icao.uppercase().trim()
        return decoded.icao != key
    }

    /**
     * Whether a gate value is one the app assigned itself (so clearing the feature may
     * clear it), rather than one the pilot typed.
     */
    fun isAppAssigned(current: String, stamp: String): Boolean =
        stampOwning(current, stamp) != null

    /**
     * The app's marker when it still owns what is in the field, else null (the pilot's gate,
     * a blank field, or a marker that no longer matches).
     */
    private fun stampOwning(current: String, stamp: String): AutoGateStamp? {
        val entered = current.trim()
        if (entered.isEmpty()) return null
        val decoded = AutoGateStamp.decode(stamp) ?: return null
        if (!decoded.gate.equals(entered, ignoreCase = true)) return null
        return decoded
    }

    /**
     * Whether a gate the app *chose* for this airport could still be improved on by reading
     * the aircraft's position — i.e. the field holds the app's own chosen gate for this
     * field, so a stand the aircraft turns out to be parked on should replace it.
     *
     * This is the "is it worth looking again" test, used before there is an assignment to
     * judge; [mayUpgrade] is the test applied to the assignment that comes back. Both are
     * false for a gate the pilot typed and for one already read off the aircraft's position.
     */
    fun couldUpgrade(current: String, stamp: String, icao: String): Boolean {
        val decoded = stampOwning(current, stamp) ?: return false
        return !decoded.fromAircraftPosition && decoded.icao == icao.uppercase().trim()
    }

    /**
     * Whether [assignment] may replace what the app itself last wrote for the same airport
     * because it is better information: a stand the aircraft is demonstrably parked on beats
     * one chosen from the field's stand list. Never touches a gate the pilot typed, and never
     * swaps a position-derived gate back for a chosen one.
     */
    fun mayUpgrade(current: String, stamp: String, icao: String, assignment: Assignment): Boolean =
        assignment.fromAircraftPosition && couldUpgrade(current, stamp, icao)

    /**
     * Pick a stand for the flight, or null when the field's extract carries no stand that
     * could be taxied to and named.
     *
     * [role] decides one thing: only a **departure** gate may be read off the aircraft's
     * parked position. The arrival gate is picked while the aircraft is still at the origin
     * (or enroute), where its position says nothing about which stand it will end up on —
     * and on a there-and-back leg, where origin and destination are the same field, reading
     * it would hand back the stand the flight is leaving. Beyond that both ends are chosen
     * from the same stand data in the same way.
     *
     * [random] is seedable so the choice can be driven deterministically in tests; the
     * Swift takes an `inout RandomNumberGenerator` for exactly the same reason.
     */
    fun assign(
        surface: AirportSurfaceModel,
        flight: FlightContext,
        role: GateRole,
        random: Random = Random.Default,
    ): Assignment? {
        val candidates = candidates(surface, flight)
        if (candidates.isEmpty()) return null

        // The aircraft is parked on one of these stands: that stand *is* the departure gate,
        // and no amount of tag matching beats knowing. Short-circuits the pick entirely.
        if (role == GateRole.DEPARTURE) {
            val parked = standAircraftIsParkedOn(candidates, flight)
            if (parked != null) {
                return assignment(
                    parked.candidate, fromAircraftPosition = true,
                    band = 1, total = candidates.size,
                    reason = "aircraft is parked on it " +
                        "(${parked.distanceMeters.roundToInt()} m from the mapped stand)",
                )
            }
        }

        // Lowest penalty wins, and the winner is drawn at random from everything tied on it
        // — so a field hands out a different one of its equally-suitable stands each flight
        // rather than always the first one in the extract.
        val bestPenalty = candidates.minOfOrNull { it.penalty } ?: return null
        val band = candidates.filter { it.penalty == bestPenalty }
        if (band.isEmpty()) return null
        val winner = band[random.nextInt(band.size)]
        return assignment(
            winner, fromAircraftPosition = false,
            band = band.size, total = candidates.size,
            reason = reason(winner, flight, band.size),
        )
    }

    /**
     * How close to a mapped stand a parked aircraft has to be for that stand to be read as
     * the gate it is sitting on. Matches the radius the arrival completion already uses
     * (`AppModel.gateArrivalRadiusMeters`), and for the same reason: an OSM stand is a single
     * node, mapped anywhere from the jet-bridge head to the nose-wheel stop line, and the
     * Infinite Flight scenery it is being compared against is a different survey again. The
     * *nearest* stand wins, so at a tightly packed concourse the radius only decides whether
     * the aircraft is on a stand at all, not which one.
     */
    const val PARKED_AT_STAND_METERS: Double = 80.0

    private data class ParkedHit(val candidate: Candidate, val distanceMeters: Double)

    /**
     * The stand a parked aircraft is sitting on, if any: the nearest assignable stand within
     * [PARKED_AT_STAND_METERS]. Null when the aircraft isn't parked, has no position yet, or
     * is nowhere near a mapped stand (out on a taxiway, or a field whose stands aren't mapped).
     *
     * Deliberately drawn from the same candidate list as the chosen gate, so the two hard
     * exclusions still hold: a stand with no identifier can't be named in a clearance even if
     * the aircraft is on it, and a de-icing pad or maintenance stand is not a gate to be
     * pushed back off.
     */
    private fun standAircraftIsParkedOn(candidates: List<Candidate>, flight: FlightContext): ParkedHit? {
        val parked = flight.parkedPosition?.toCoordinate() ?: return null
        if (!parked.isValid) return null
        var best: ParkedHit? = null
        for (candidate in candidates) {
            val distance = SurfaceGeometry.distanceMeters(parked, candidate.stand.coordinate.toCoordinate())
            if (distance > PARKED_AT_STAND_METERS) continue
            val current = best
            if (current != null && distance >= current.distanceMeters) continue
            best = ParkedHit(candidate, distance)
        }
        return best
    }

    private fun assignment(
        candidate: Candidate,
        fromAircraftPosition: Boolean,
        band: Int,
        total: Int,
        reason: String,
    ): Assignment = Assignment(
        gate = candidate.stand.name.trim(),
        osmID = candidate.stand.osmID,
        coordinate = candidate.stand.coordinate,
        matchedOperator = candidate.matchedOperator,
        matchedAircraftType = candidate.profile.maxClass != null,
        fromAircraftPosition = fromAircraftPosition,
        tiedCandidates = band,
        totalCandidates = total,
        reason = reason,
    )

    // MARK: - Candidates

    private data class Candidate(
        val stand: SurfaceParking,
        val profile: StandProfile,
        val matchedOperator: Boolean,
        /**
         * Lower is better. Built from the mismatches below, so every preference is a
         * *soft* one: when a field offers nothing better, the least-bad stand is still
         * assigned rather than leaving the pilot with no gate at all.
         */
        val penalty: Int,
        val fitGap: Int,
    )

    /**
     * Every stand at the field that could be assigned, each scored. Two exclusions are
     * hard, because assigning them would be worse than assigning nothing: a stand with no
     * identifier (it cannot be named in a clearance) and a service position (no flight
     * parks on the de-icing pad).
     */
    private fun candidates(surface: AirportSurfaceModel, flight: FlightContext): List<Candidate> {
        val fragments = flight.operatorFragments
        val out = mutableListOf<Candidate>()
        // `routableStands` so a field that maps one stand as both a `gate` node and a
        // `parking_position` (KIAD) offers it once, as the stand an aircraft can park on.
        for (stand in surface.routableStands) {
            val name = stand.name.trim()
            if (name.isEmpty()) continue
            val profile = StandProfile.from(stand.tags, name)
            if (profile.servicePosition) continue

            val matchedOperator = fragments.isNotEmpty() && profile.operatorNames.any { op ->
                fragments.any { op.contains(it) }
            }

            var penalty = 0
            // The airline's own stand beats a stranger's.
            if (!matchedOperator) penalty += 10
            // A stand whose tags say it fits beats one that simply isn't tagged, and among
            // tagged stands the snuggest fit wins: a 737 takes the 737 stand next to the
            // empty widebody one, not the widebody stand.
            val fitGap = profile.fitGap(flight.aircraftClass)
            if (profile.maxClass == null) penalty += 4 else penalty += min(fitGap, 3)
            // Terminal gate vs. remote stand, by aircraft.
            val isTerminalGate = stand.kind == SurfaceParking.Kind.GATE
            if (isTerminalGate != flight.prefersTerminalGate) penalty += 2
            // Freight and passenger stands are not interchangeable.
            if (profile.cargo != flight.isCargo) penalty += 30
            // A stand the tags say is too small, a rotorcraft pad, or one the flight has no
            // access to: usable only when the field offers nothing else.
            if (!profile.accepts(flight.aircraftClass)) penalty += 40
            if (profile.helicopterOnly) penalty += 60
            if (profile.restricted) penalty += 50

            out.add(Candidate(stand, profile, matchedOperator, penalty, fitGap))
        }
        return out
    }

    private fun reason(candidate: Candidate, flight: FlightContext, band: Int): String {
        val parts = mutableListOf<String>()
        if (candidate.matchedOperator) {
            parts.add("operator match (${candidate.profile.operatorNames.firstOrNull() ?: "airline"})")
        }
        val maxClass = candidate.profile.maxClass
        if (maxClass != null) {
            val fit = if (candidate.fitGap == 0) "exact fit" else "roomier than needed"
            parts.add(
                "${maxClass.title.lowercase()}-class stand for a " +
                    "${flight.aircraftClass.title.lowercase()} aircraft ($fit)",
            )
        } else {
            parts.add("no aircraft-size tag")
        }
        parts.add(if (candidate.stand.kind == SurfaceParking.Kind.GATE) "terminal gate" else "parking stand")
        if (candidate.profile.cargo) parts.add("cargo position")
        if (candidate.profile.restricted) parts.add("access-restricted (nothing better available)")
        parts.add(if (band > 1) "random pick of $band equal stands" else "only stand in its band")
        return parts.joinToString(", ")
    }
}
