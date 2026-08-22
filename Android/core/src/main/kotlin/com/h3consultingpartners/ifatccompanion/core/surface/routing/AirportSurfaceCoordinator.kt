package com.h3consultingpartners.ifatccompanion.core.surface.routing

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.surface.ActiveCrossingSummary
import com.h3consultingpartners.ifatccompanion.core.surface.AircraftSizeClass
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceDiagnostics
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceModel
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceProvider
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceStatusText
import com.h3consultingpartners.ifatccompanion.core.surface.GeoCoordinate
import com.h3consultingpartners.ifatccompanion.core.surface.MockAirportSurface
import com.h3consultingpartners.ifatccompanion.core.surface.OSMSurface
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceConfidence
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceGraphSummary
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceParking
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceRunway
import com.h3consultingpartners.ifatccompanion.core.surface.TaxiRouteSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Live display aircraft used by the taxi map (real telemetry in live mode, a
 * simulated track in mock mode).
 */
data class TaxiAircraft(
    val coordinate: GeoCoordinate,
    val headingDegrees: Double,
    val onGround: Boolean,
    val groundSpeedKnots: Double,
)

/** Loading/availability status of the airport surface. */
sealed interface AirportSurfaceStatus {
    object Idle : AirportSurfaceStatus
    object Loading : AirportSurfaceStatus
    object Ready : AirportSurfaceStatus
    data class Unavailable(val reason: String) : AirportSurfaceStatus
    data class Error(val reason: String) : AirportSurfaceStatus

    val isReady: Boolean get() = this == Ready

    /** The wording `AirportSurfaceDiagnostics` renders for this status. */
    val text: String
        get() = when (this) {
            Idle -> AirportSurfaceStatusText.IDLE
            Loading -> AirportSurfaceStatusText.LOADING
            Ready -> AirportSurfaceStatusText.READY
            is Unavailable -> AirportSurfaceStatusText.unavailable(reason)
            is Error -> AirportSurfaceStatusText.error(reason)
        }
}

/** Which taxi phase the coordinator is servicing. */
enum class TaxiKind { NONE, DEPARTURE, ARRIVAL }

/**
 * Response actions surfaced on the taxi map for the crossing / off-route flows.
 *
 * PARITY NOTE: the Swift enum also carries a `systemImage` SF Symbol name. That is an iOS
 * asset identifier with no meaning here, so the Android UI picks its own icon per action;
 * [rawValue] and [title] are carried across verbatim.
 */
enum class TaxiMapAction(val rawValue: String) {
    HOLD_POSITION("holdPosition"),
    REQUEST_CROSSING("requestCrossing"),
    REQUEST_ALTERNATE_ROUTE("requestAlternateRoute"),
    RECALCULATE("recalculate"),
    CONTINUE_ORIGINAL_ROUTE("continueOriginalRoute"),
    REQUEST_NEW_TAXI("requestNewTaxi"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            HOLD_POSITION -> "Hold Position"
            REQUEST_CROSSING -> "Request Crossing"
            REQUEST_ALTERNATE_ROUTE -> "Alt Route"
            RECALCULATE -> "Recalculate"
            CONTINUE_ORIGINAL_ROUTE -> "Continue"
            REQUEST_NEW_TAXI -> "New Taxi"
        }
}

/**
 * Everything the taxi UI observes, as one immutable snapshot.
 *
 * The Swift coordinator is an `ObservableObject` with two dozen `@Published` properties;
 * per the porting conventions those aggregate into a single [StateFlow] of this value so a
 * Compose screen recomposes once per tick rather than once per property.
 */
data class AirportSurfaceState(
    val status: AirportSurfaceStatus = AirportSurfaceStatus.Idle,
    val taxiMapVisible: Boolean = false,
    val mapExpanded: Boolean = false,
    val kind: TaxiKind = TaxiKind.NONE,
    val route: SurfaceTaxiRoute? = null,
    val surface: AirportSurfaceModel? = null,
    val datasetConfidence: SurfaceConfidence = SurfaceConfidence.UNAVAILABLE,
    val routeConfidence: SurfaceConfidence = SurfaceConfidence.UNAVAILABLE,
    val crossingState: RunwayCrossingState = RunwayCrossingState.NO_CROSSING_PENDING,
    val activeCrossing: RouteCrossing? = null,
    val progress: RouteTracker.Progress? = null,
    val displayAircraft: TaxiAircraft? = null,
    val nextInstruction: String = "",
    val offRoute: Boolean = false,
    val reachedDestination: Boolean = false,
    /**
     * Latches true once a **departure** taxi comes within [OSMSurface.MONITOR_TOWER_LEAD_METERS]
     * of the runway hold-short — the cue for Ground to hand the pilot to Tower to *monitor*
     * ("monitor Tower on …"). One-shot per taxi; the app model consumes it and issues the call.
     * OSM has no explicit "monitor tower" line, so this is derived from the route, not a
     * mapped feature.
     */
    val approachingRunwayHandoff: Boolean = false,
    /**
     * Latches true once a **departure** taxi comes within `holdIssueMeters` of the runway
     * hold-short — the same lead distance at which the automatic runway-*crossing* clearance
     * is issued. This is the cue for Tower to issue "line up and wait" while the aircraft is
     * still rolling up to the runway (so it can make a rolling line-up rather than stopping
     * short first). One-shot per taxi.
     */
    val approachingRunwayLineup: Boolean = false,
    val lastError: String? = null,
    val awaitingCrossingReadback: Boolean = false,
    val awaitingTaxiReadback: Boolean = false,
    /** Manual overrides (Settings / taxi map). Default per requirements. */
    val autoCrossingCalls: Boolean = true,
    val autoRecalculate: Boolean = false,
) {

    val crossingActions: List<TaxiMapAction>
        get() {
            if (!taxiMapVisible || activeCrossing == null) return emptyList()
            if (awaitingCrossingReadback) {
                return listOf(TaxiMapAction.HOLD_POSITION, TaxiMapAction.REQUEST_ALTERNATE_ROUTE)
            }
            return when (crossingState) {
                RunwayCrossingState.HOLDING_SHORT,
                RunwayCrossingState.LOW_CONFIDENCE_CROSSING_DATA,
                RunwayCrossingState.HOLD_SHORT_INSTRUCTION_ISSUED,
                RunwayCrossingState.APPROACHING_HOLDING_POSITION,
                -> listOf(
                    TaxiMapAction.REQUEST_CROSSING,
                    TaxiMapAction.HOLD_POSITION,
                    TaxiMapAction.REQUEST_ALTERNATE_ROUTE,
                )
                else -> emptyList()
            }
        }

    val offRouteActions: List<TaxiMapAction>
        get() = if (offRoute) {
            listOf(
                TaxiMapAction.RECALCULATE,
                TaxiMapAction.CONTINUE_ORIGINAL_ROUTE,
                TaxiMapAction.REQUEST_NEW_TAXI,
            )
        } else {
            emptyList()
        }
}

/**
 * Coordinates the OpenStreetMap airport-surface feature: loads/normalizes/caches the
 * surface, builds the graph, calculates taxi routes, drives the taxi-map state, and runs
 * the simulated Ground runway-crossing workflow. Owned by the app model, which calls into
 * it at the taxi/hand-off/arrival lifecycle points and forwards telemetry; the ATC/taxi
 * views observe [state].
 *
 * It knows nothing about any renderer: it publishes geometry and instructions, and the map
 * layer draws them.
 *
 * Everything is framed as flight-simulation only and never presents OSM data as
 * authoritative.
 *
 * Ported from `IFATCCompanion/AirportSurface/AirportSurfaceCoordinator.swift`. The Swift is
 * `@MainActor`; this class is likewise **not** thread-safe — call it from one dispatcher (the
 * app's main/immediate one). Asynchronous work runs on the injected [scope].
 */
class AirportSurfaceCoordinator(
    private val provider: AirportSurfaceProvider? = null,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
) {

    private val _state = MutableStateFlow(AirportSurfaceState())
    val state: StateFlow<AirportSurfaceState> = _state.asStateFlow()

    private inline fun update(block: (AirportSurfaceState) -> AirportSurfaceState) {
        _state.value = block(_state.value)
    }

    // MARK: - Dependencies

    private val tracker = RouteTracker()
    private var phraseology = TaxiPhraseology(PhraseologyEngine())

    /** Posts a simulated ATC transmission into the transcript (and speaks it). */
    var emitATC: ((ATCTransmission) -> Unit)? = null

    /** Provides the current callsign for crossing/hold phraseology. */
    var callsignProvider: (() -> PhraseologyEngine.Callsign)? = null

    /**
     * Called with an airport's ICAO whenever its extract becomes available — loaded into the
     * coordinator, pre-cached for the simulated demo, or warmed for the arrival field.
     *
     * Anything that needs the extract but isn't the taxi itself (today: the automatic gate
     * assignment) hangs off this rather than off the prefetch happening to finish first. A
     * large field can take a minute to arrive, and before this the gate assignment simply
     * raced the download and kept whatever it had if it lost. Deliberately fired only from
     * the *prefetch/load* paths, never from [surfaceModel], so a listener that reads the
     * surface in response can't re-trigger itself.
     */
    var onSurfaceAvailable: ((String) -> Unit)? = null

    // MARK: - Convenience accessors mirroring the Swift's published properties

    val route: SurfaceTaxiRoute? get() = _state.value.route
    val surface: AirportSurfaceModel? get() = _state.value.surface
    val status: AirportSurfaceStatus get() = _state.value.status
    val kind: TaxiKind get() = _state.value.kind
    val crossingState: RunwayCrossingState get() = _state.value.crossingState
    val activeCrossing: RouteCrossing? get() = _state.value.activeCrossing
    val offRoute: Boolean get() = _state.value.offRoute
    val reachedDestination: Boolean get() = _state.value.reachedDestination
    val awaitingCrossingReadback: Boolean get() = _state.value.awaitingCrossingReadback
    val awaitingTaxiReadback: Boolean get() = _state.value.awaitingTaxiReadback
    val taxiMapVisible: Boolean get() = _state.value.taxiMapVisible
    val routeConfidence: SurfaceConfidence get() = _state.value.routeConfidence
    val datasetConfidence: SurfaceConfidence get() = _state.value.datasetConfidence
    val displayAircraft: TaxiAircraft? get() = _state.value.displayAircraft
    val nextInstruction: String get() = _state.value.nextInstruction
    val approachingRunwayHandoff: Boolean get() = _state.value.approachingRunwayHandoff
    val approachingRunwayLineup: Boolean get() = _state.value.approachingRunwayLineup

    var mapExpanded: Boolean
        get() = _state.value.mapExpanded
        set(v) = update { it.copy(mapExpanded = v) }

    var autoCrossingCalls: Boolean
        get() = _state.value.autoCrossingCalls
        set(v) = update { it.copy(autoCrossingCalls = v) }

    var autoRecalculate: Boolean
        get() = _state.value.autoRecalculate
        set(v) = update { it.copy(autoRecalculate = v) }

    // MARK: - Internal state

    private var graph: SurfaceGraph? = null
    private var icao = ""
    private var reference = Coordinate.zero
    private var aircraftClass: AircraftSizeClass = AircraftSizeClass.MEDIUM
    private var assignedRunway = ""
    private var gate = ""

    /**
     * Whether the aircraft is driven by the simulated (mock) ticker rather than live
     * telemetry. The surface itself may still be the real, pre-cached OSM field.
     */
    private var mockMode = false
    private var startGate = ""

    /** The arrival runway (simulated demo): where the rollout starts on a real surface. */
    private var arrivalRunway = ""

    /**
     * Whether the loaded surface is the synthetic offline fallback rather than a real OSM
     * extract. Drives whether a route uses the synthetic demo geometry or the real field's
     * gates and runways.
     */
    private var syntheticSurface = false

    /**
     * Real, pre-cached OSM surfaces for the simulated (mock) demo's airports, keyed by
     * ICAO. When a simulated taxi begins for one of these fields the real field is used —
     * so the demo taxis the actual airport — otherwise the synthetic fallback is built.
     */
    private val simulatedSurfaces = mutableMapOf<String, AirportSurfaceModel>()

    /**
     * The reference coordinate each simulated-demo airport was prepared with. Recorded for
     * the demo's origin/destination so a mock taxi can asynchronously (re)load the real
     * surface when it wasn't pre-cached in time (a large destination like KMSP whose extract
     * is still fetching) — without ever firing a network fetch for an arbitrary/test field.
     */
    private val simulatedReferences = mutableMapOf<String, Coordinate>()

    /**
     * Real runway-end idents (e.g. `["16L","34R","09","27"]`) for every airport whose surface
     * has loaded or pre-cached this session, keyed by ICAO. Populated as departure/arrival
     * surfaces load, and read synchronously by the ambient-chatter generator so background
     * runway calls reference runways that actually exist at the origin/destination field.
     */
    private val runwayIdentsByICAO = mutableMapOf<String, List<String>>()

    private var taxiReadBack = false

    /**
     * A generic Ground taxi clearance was issued before the surface finished loading
     * (uncached live airports load asynchronously). Supersede it with the detailed OSM
     * route clearance once [recomputeRoute] produces a credible route.
     */
    private var pendingDetailedClearance = false

    /**
     * Signature of the taxi instruction last issued to the pilot (taxiway sequence, assigned
     * runway / gate, first hold-short crossing). A recalculation that resolves to a materially
     * different route — a different signature — re-issues a Ground taxi clearance with its own
     * read-back; an identical route stays silent so recalculating doesn't repeat the same
     * instruction (see [recalculateRoute]).
     */
    private var lastIssuedTaxiClearanceSignature: String? = null

    /**
     * The live position of the last route retry, so the "surface ready but no route yet"
     * recovery only re-runs the A* once the aircraft has actually moved (see [updateLive]).
     */
    private var lastRouteRetryCoordinate: Coordinate? = null

    private var lastAlong = 0.0
    private var offRouteTicks = 0
    private var unauthorizedTicks = 0
    private var holdSettleTicks = 0

    private var workedCrossingIndex: Int? = null
    private var authorizedCrossingIndex: Int? = null
    private var pendingCrossingIndex: Int? = null
    private val issuedHoldShortFor = mutableSetOf<Int>()
    private val issuedClearanceFor = mutableSetOf<Int>()
    private val completedCrossings = mutableSetOf<Int>()
    private val userRequestedCrossingFor = mutableSetOf<Int>()

    /**
     * Crossings the pilot explicitly asked to hold position at — suppresses the automatic
     * crossing clearance until they tap Request Crossing.
     */
    private val pilotHeldFor = mutableSetOf<Int>()
    private val emittedResumeFor = mutableSetOf<Int>()

    private var loadGeneration = 0
    private var mockTask: Job? = null
    private var mockAlong = 0.0

    // Workflow tuning (meters unless noted).
    private val detectAheadMeters = 350.0
    private val approachMeters = 300.0

    /**
     * How far back from the hold point the crossing clearance is issued. Tripled from the
     * original 90 m so a pilot taxiing at ~25 kt gets "cross runway …" well before the
     * threshold and has time to read it back before reaching the runway — the same lead-time
     * reasoning as the Ground→Tower monitor hand-off ([OSMSurface.MONITOR_TOWER_LEAD_METERS]).
     */
    private val holdIssueMeters = 270.0
    private val atHoldMeters = 20.0
    private val corridorEnterMeters = 30.0
    private val vacateMarginMeters = 42.0
    private val holdBeforeCrossingMeters = 25.0
    private val settleTicks = 2
    private val offRouteTickThreshold = 4
    private val mockStepMeters = 4.0
    private val mockTickMillis = 400L

    fun configure(
        engine: PhraseologyEngine,
        emit: (ATCTransmission) -> Unit,
        callsign: () -> PhraseologyEngine.Callsign,
    ) {
        phraseology = TaxiPhraseology(engine)
        emitATC = emit
        callsignProvider = callsign
        provider?.configure(diagnostics)
    }

    fun updateEngine(engine: PhraseologyEngine) {
        phraseology = TaxiPhraseology(engine)
    }

    // MARK: - Prefetch / load

    /**
     * Begin loading an airport's surface ahead of time (no map reveal). Safe to call
     * repeatedly; ignored when the surface is already loaded for that ICAO.
     */
    fun prefetch(icao: String, reference: Coordinate, mock: Boolean) {
        val key = icao.uppercase()
        if (surface?.icao == key) return
        scope.launch { loadSurface(key, reference, mock, forceRefresh = false) }
    }

    /**
     * Cache both the flight's departure and arrival airport surfaces at flight load,
     * rather than lazily right before taxi. The departure surface is loaded into the
     * coordinator so its taxi routes synchronously and issues the detailed clearance
     * immediately; the arrival surface is fetched into the provider cache (disk + memory)
     * so its later load is instant and works offline. Live surfaces only — mock airports
     * build synthetic surfaces on demand, so there is nothing to pre-cache.
     */
    fun prefetchFlightSurfaces(
        departure: String,
        departureReference: Coordinate?,
        arrival: String,
        arrivalReference: Coordinate?,
    ) {
        val dep = departure.uppercase()
        val arr = arrival.uppercase()
        // The departure surface goes into the coordinator so its taxi routes synchronously
        // — but only between flights, never clobbering an active taxi's loaded surface.
        if (kind == TaxiKind.NONE && dep.length >= 3 &&
            departureReference != null && departureReference.isValid
        ) {
            prefetch(dep, departureReference, mock = false)
        }
        // The arrival only warms the provider cache (it never touches the coordinator's
        // active surface), so it is always safe to run — including in cruise before the
        // arrival taxi begins.
        if (arr.length >= 3 && arr != dep && arrivalReference != null && arrivalReference.isValid) {
            warmCache(arr, arrivalReference)
        }
    }

    /**
     * Pre-cache the real OSM surfaces for the simulated (mock) demo's origin and
     * destination, so the demo taxis the actual airports (not the synthetic offline field)
     * and works offline afterward. Each real extract is fetched into the provider cache
     * (disk + memory) and held for synchronous use when the simulated taxi begins.
     * Best-effort: a field that can't be fetched (offline first-run, no OSM data) simply
     * falls back to the synthetic surface when its taxi begins.
     */
    fun prepareSimulatedSurfaces(airports: List<Pair<String, Coordinate?>>) {
        for ((rawIcao, ref) in airports) {
            val key = rawIcao.uppercase().trim { it == ' ' || it == '\t' }
            if (key.length < 3 || ref == null || !ref.isValid) continue
            // Remember the reference for this demo airport so a later mock taxi can re-load
            // its real surface if the pre-cache is still in flight when the taxi begins.
            simulatedReferences[key] = ref
            if (simulatedSurfaces[key] != null) continue
            val p = provider ?: continue
            scope.launch {
                try {
                    val model = p.surface(key, ref, forceRefresh = false)
                    storeSimulatedSurface(model, key)
                } catch (e: Exception) {
                    val message = e.message ?: e.toString()
                    log("Mock demo surface unavailable for $key: $message (synthetic fallback)")
                }
            }
        }
    }

    private fun storeSimulatedSurface(model: AirportSurfaceModel, key: String) {
        if (!model.hasUsableGeometry) return
        simulatedSurfaces[key] = model
        recordRunwayIdents(model)
        log(
            "Mock demo surface pre-cached for $key: ${model.runways.size} rwy, " +
                "${model.taxiways.size} twy, ${model.confidence.title}",
        )
        onSurfaceAvailable?.invoke(key)
    }

    /**
     * Remember an airport's real runway-end idents so the ambient chatter can reference them
     * synchronously (see [cachedRunwayIdents]). No-op for a surface with no parsed runways.
     */
    private fun recordRunwayIdents(model: AirportSurfaceModel) {
        val key = model.icao.uppercase().trim { it == ' ' || it == '\t' }
        val idents = model.allRunwayIdents
        if (key.isEmpty() || idents.isEmpty()) return
        runwayIdentsByICAO[key] = idents
    }

    /**
     * The real runway-end idents cached for an airport this session, or an empty list when
     * its surface hasn't loaded yet. Read by the ambient chatter so its background runway
     * references match the origin/destination field's actual runways rather than a made-up one.
     */
    fun cachedRunwayIdents(icao: String): List<String> {
        val key = icao.uppercase().trim { it == ' ' || it == '\t' }
        if (key.isEmpty()) return emptyList()
        return runwayIdentsByICAO[key] ?: emptyList()
    }

    /**
     * The best available surface for an airport, for callers that need to **read** its data
     * rather than taxi on it — today the automatic gate assignment, which needs the field's
     * stand list and their OSM tags.
     *
     * Never disturbs the active taxi: the coordinator's own loaded surface is returned when
     * it happens to be the same field, then a pre-cached simulated (demo) surface, then the
     * provider's memory/disk cache or a fetch. The provider coalesces identical requests, so
     * when this runs alongside the flight-load prefetch it joins that request rather than
     * making a second one. Returns null when the field has no usable data (offline first run,
     * no OSM coverage) — the caller then simply leaves the gate as it is.
     */
    suspend fun surfaceModel(icao: String, reference: Coordinate): AirportSurfaceModel? {
        val key = icao.uppercase().trim { it == ' ' || it == '\t' }
        if (key.length < 3) return null
        val loaded = surface
        if (loaded != null && loaded.icao == key && !syntheticSurface) return loaded
        simulatedSurfaces[key]?.let { return it }
        if (!reference.isValid) return null
        val p = provider ?: return null
        return try {
            p.surface(key, reference, forceRefresh = false)
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            log("No airport surface data for $key: $message")
            null
        }
    }

    /**
     * Warm the disk/memory surface cache for an airport without disturbing the active
     * taxi surface. Used to pre-cache the arrival field while the departure surface stays
     * loaded in the coordinator, so the arrival's later load is instant and offline.
     */
    private fun warmCache(icao: String, reference: Coordinate) {
        val key = icao.uppercase()
        if (key.length < 3 || !reference.isValid) return
        val p = provider ?: return
        scope.launch {
            try {
                val model = p.surface(key, reference, forceRefresh = false)
                recordRunwayIdents(model)
                log("OSM surface pre-cached for $key")
                onSurfaceAvailable?.invoke(key)
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                log("OSM pre-cache failed for $key: $message")
            }
        }
    }

    private suspend fun loadSurface(
        icao: String,
        reference: Coordinate,
        mock: Boolean,
        forceRefresh: Boolean,
    ) {
        val key = icao.uppercase()
        if (key.length < 3 || !reference.isValid) return
        loadGeneration += 1
        val generation = loadGeneration
        update { it.copy(status = AirportSurfaceStatus.Loading) }
        this.icao = key
        this.reference = reference
        this.mockMode = mock

        if (mock) {
            loadSimulatedSurface(generation)
            return
        }

        val p = provider
        if (p == null) {
            if (generation != loadGeneration) return
            failLoad(key, generation, "No airport surface provider is configured.")
            return
        }
        try {
            val model = p.surface(key, reference, forceRefresh = forceRefresh)
            if (generation != loadGeneration) return
            applyLoaded(model, generation)
        } catch (e: Exception) {
            if (generation != loadGeneration) return
            failLoad(key, generation, e.message ?: e.toString())
        }
    }

    private fun failLoad(key: String, generation: Int, message: String) {
        if (generation != loadGeneration) return
        graph = null
        update {
            it.copy(
                lastError = message,
                surface = null,
                datasetConfidence = SurfaceConfidence.UNAVAILABLE,
                status = AirportSurfaceStatus.Unavailable(message),
            )
        }
        log("OSM surface unavailable for $key: $message")
        recomputeRoute()
    }

    private fun applyLoaded(model: AirportSurfaceModel, generation: Int) {
        if (generation != loadGeneration) return
        val builtGraph = SurfaceGraphBuilder.build(model)
        val m = model.copy(
            confidence = SurfaceConfidenceEvaluator.datasetConfidence(model, builtGraph),
        )
        recordRunwayIdents(m)
        graph = builtGraph
        update {
            it.copy(
                surface = m,
                datasetConfidence = m.confidence,
                lastError = null,
                status = if (m.hasUsableGeometry) {
                    AirportSurfaceStatus.Ready
                } else {
                    AirportSurfaceStatus.Unavailable("No usable airport surface geometry.")
                },
            )
        }
        log(
            "OSM surface ready ${m.icao}: ${builtGraph.nodes.size} nodes, " +
                "${builtGraph.edges.size} edges, ${m.confidence.title}",
        )
        if (m.hasUsableGeometry) onSurfaceAvailable?.invoke(m.icao)
        recomputeRoute()
    }

    // MARK: - Departure / arrival entry

    /** Prepare a departure taxi to the assigned runway from the gate/current position. */
    fun beginDeparture(
        icao: String,
        reference: Coordinate,
        aircraftName: String?,
        runway: String,
        gate: String,
        startCoordinate: Coordinate,
        mock: Boolean,
    ) {
        update { it.copy(kind = TaxiKind.DEPARTURE) }
        aircraftClass = AircraftSizeClass.classify(aircraftName)
        assignedRunway = runway
        arrivalRunway = ""
        this.gate = gate
        this.startGate = gate
        this.mockMode = mock
        this.pendingStart = startCoordinate
        resetTaxiProgress()
        loadForTaxi(icao, reference, mock)
    }

    /**
     * Prepare an arrival taxi to the gate from the runway exit / current position. The
     * arrival runway lets the simulated demo start the rollout where an aircraft exits
     * after landing on a real surface (ignored for the synthetic field / live telemetry).
     */
    fun beginArrival(
        icao: String,
        reference: Coordinate,
        aircraftName: String?,
        gate: String,
        startCoordinate: Coordinate,
        mock: Boolean,
        arrivalRunway: String = "",
    ) {
        update { it.copy(kind = TaxiKind.ARRIVAL) }
        aircraftClass = AircraftSizeClass.classify(aircraftName)
        assignedRunway = ""
        this.arrivalRunway = arrivalRunway
        this.gate = gate
        this.startGate = ""
        this.mockMode = mock
        this.pendingStart = startCoordinate
        resetTaxiProgress()
        loadForTaxi(icao, reference, mock)
    }

    /**
     * Re-anchor the active taxi route at the aircraft's current position and recompute, so
     * the route starts where the aircraft actually is at the moment taxi is requested — not
     * where it was when the surface was first warmed. For an arrival that earlier warm point
     * is the runway exit, so without this the route would begin behind the aircraft, which
     * has since taxied clear of the runway.
     *
     * Live only: the mock demo drives a scripted start. No-op unless a taxi is being
     * serviced and the coordinate is valid, and harmless while the surface is still loading
     * — [recomputeRoute] no-ops and the stored start is used once the load resolves.
     */
    fun updateTaxiStart(coordinate: Coordinate) {
        if (mockMode || kind == TaxiKind.NONE || !coordinate.isValid) return
        pendingStart = coordinate
        // Keep the live re-route throttle in step so the next telemetry sample doesn't
        // immediately re-run the A* from this same point (see `retryRouteFromLivePosition`).
        lastRouteRetryCoordinate = coordinate
        recomputeRoute()
    }

    /**
     * Ensure the surface is available for a taxi. Mock builds synchronously (so the
     * OSM taxi clearance is ready immediately); a cached live surface routes at once;
     * an uncached live surface loads asynchronously and reveals the map when ready.
     */
    private fun loadForTaxi(icao: String, reference: Coordinate, mock: Boolean) {
        if (mock) {
            loadGeneration += 1
            val generation = loadGeneration
            this.icao = icao.uppercase()
            this.reference = reference
            this.mockMode = true
            update { it.copy(status = AirportSurfaceStatus.Loading) }
            loadSimulatedSurface(generation)
        } else if (surface?.icao == icao.uppercase() && graph != null) {
            this.reference = reference
            recomputeRoute()
        } else {
            // Mark the load in progress synchronously so a taxi-clearance decision made on the
            // same run loop (before the async fetch actually starts) already sees it and
            // withholds the clearance until the surface resolves. `loadSurface` sets it again
            // when the coroutine runs.
            update { it.copy(status = AirportSurfaceStatus.Loading) }
            scope.launch { loadSurface(icao, reference, mock = false, forceRefresh = false) }
        }
    }

    /**
     * Whether the airport surface is still being fetched/normalized (no Ready,
     * Unavailable, or Error result yet). Ground uses this to withhold the arrival
     * taxi clearance until the data has fully loaded, rather than issuing a generic call
     * it would then have to supersede.
     */
    val surfaceLoadInProgress: Boolean get() = status == AirportSurfaceStatus.Loading

    private var pendingStart: Coordinate? = null

    /**
     * Load the surface for a simulated (mock) taxi. For the demo's own origin **and**
     * destination (recorded by [prepareSimulatedSurfaces]) the real, full OSM field is always
     * used — the pre-cached extract when it's ready, otherwise the taxi *waits* for the real
     * field to finish downloading rather than dropping onto the bundled synthetic surface.
     * `icao`/`reference` are already set by the caller.
     *
     * While the real field downloads the surface stays Loading, so the taxi map / mock
     * drive only begins once it's ready ([revealIfReady] gates on `status.isReady`): the map
     * then animates in on the actual airport, and Ground's generic clearance is superseded by
     * the detailed OSM route. This is what stops a large destination like KMSP — whose extract
     * takes longer to cache than a short demo takes to reach taxi-in — from being stuck on the
     * tiny synthetic field. Only a field with no OSM reference at all (a synthetic-only / test
     * airport), or a real extract that genuinely can't be produced (offline / no OSM data),
     * falls back to the synthetic field.
     */
    private fun loadSimulatedSurface(generation: Int) {
        val real = simulatedSurfaces[icao]
        if (real != null && real.hasUsableGeometry) {
            syntheticSurface = false
            applyLoaded(real, generation)
            return
        }
        // A demo origin/destination: download and cache the full real field, waiting for it
        // instead of using the bundled synthetic surface. Synthetic only when there is no OSM
        // reference to fetch (e.g. a unit-test airport).
        if (simulatedReferences[icao] == null) {
            installSyntheticSurface(generation)
            return
        }
        fetchRealSimulatedSurface(generation)
    }

    /**
     * Download the real OSM surface for the mock demo airport currently being taxied and adopt
     * it once ready, holding the surface Loading until it arrives so the taxi uses the
     * actual airport (not the bundled synthetic field). Only runs for the demo's own
     * origin/destination (recorded by [prepareSimulatedSurfaces]). The provider coalesces this
     * with any in-flight pre-cache fetch for the same field, so it never duplicates the
     * request. Only a genuine failure (offline, no OSM data, or unusable geometry) falls back
     * to the synthetic field so the demo still taxis.
     */
    private fun fetchRealSimulatedSurface(generation: Int) {
        val ref = simulatedReferences[icao]
        val p = provider
        if (ref == null || p == null) {
            installSyntheticSurface(generation)
            return
        }
        val key = icao
        syntheticSurface = false
        update { it.copy(status = AirportSurfaceStatus.Loading) }
        scope.launch {
            try {
                val model = p.surface(key, ref, forceRefresh = false)
                if (model.hasUsableGeometry) {
                    adoptRealSimulatedSurface(model, key, generation)
                } else {
                    fallBackToSyntheticSimulatedSurface(key, generation, "no usable geometry")
                }
            } catch (e: Exception) {
                fallBackToSyntheticSimulatedSurface(key, generation, e.message ?: e.toString())
            }
        }
    }

    /**
     * Adopt a just-downloaded real surface for the mock demo. Always cached for the next taxi
     * at this field; applied to the active taxi only while it is still waiting for the real
     * field (held Loading) or on the ready synthetic fallback — and only before the
     * simulated drive starts, since swapping mid-drive would teleport the aircraft (the real
     * field is then used the next time the demo taxis here). Requiring Loading or a ready
     * synthetic surface also skips a fetch that resolves after the map has been hidden
     * (hand-off / cleared → Idle), so it never re-reveals a taxi map that is already gone.
     */
    private fun adoptRealSimulatedSurface(model: AirportSurfaceModel, key: String, generation: Int) {
        if (!model.hasUsableGeometry) return
        simulatedSurfaces[key] = model
        val ready = status == AirportSurfaceStatus.Loading || (syntheticSurface && status.isReady)
        if (generation != loadGeneration || !mockMode || kind == TaxiKind.NONE || icao != key ||
            mockTask != null || reachedDestination || !ready
        ) {
            return
        }
        syntheticSurface = false
        log(
            "Mock demo: real surface ready for $key — ${model.runways.size} rwy, " +
                "${model.taxiways.size} twy",
        )
        applyLoaded(model, generation)
    }

    /**
     * Fall back to the synthetic offline field for the demo taxi when the real extract can't
     * be produced (offline / no OSM data), so the demo still taxis. No-op once the drive has
     * started, the taxi is over, or a surface is already in place.
     */
    private fun fallBackToSyntheticSimulatedSurface(key: String, generation: Int, reason: String) {
        if (generation != loadGeneration || !mockMode || kind == TaxiKind.NONE || icao != key ||
            mockTask != null || reachedDestination || syntheticSurface ||
            status != AirportSurfaceStatus.Loading
        ) {
            return
        }
        log("Mock demo surface unavailable for $key: $reason (synthetic fallback)")
        installSyntheticSurface(generation)
    }

    /**
     * Build and load the synthetic offline field for the current `icao`/`reference`.
     * Used for a field with no OSM reference (e.g. a unit-test airport), and as a fallback
     * when a real surface can't be downloaded or can't be routed in the demo.
     */
    private fun installSyntheticSurface(generation: Int) {
        syntheticSurface = true
        val model = MockAirportSurface.model(
            icao = icao, reference = reference,
            primaryRunwayIdent = mockPrimaryRunway(), gate = mockGate(),
            nowMillis = clock.nowMillis(),
        )
        applyLoaded(model, generation)
    }

    private fun resetTaxiProgress() {
        syntheticSurface = false
        taxiReadBack = false
        pendingDetailedClearance = false
        lastIssuedTaxiClearanceSignature = null
        lastRouteRetryCoordinate = null
        lastAlong = 0.0
        offRouteTicks = 0
        unauthorizedTicks = 0
        holdSettleTicks = 0
        workedCrossingIndex = null
        authorizedCrossingIndex = null
        pendingCrossingIndex = null
        issuedHoldShortFor.clear()
        issuedClearanceFor.clear()
        completedCrossings.clear()
        userRequestedCrossingFor.clear()
        pilotHeldFor.clear()
        emittedResumeFor.clear()
        mockAlong = 0.0
        update {
            it.copy(
                offRoute = false,
                reachedDestination = false,
                approachingRunwayHandoff = false,
                approachingRunwayLineup = false,
                crossingState = RunwayCrossingState.NO_CROSSING_PENDING,
                activeCrossing = null,
                awaitingCrossingReadback = false,
            )
        }
    }

    /** (Re)compute the taxi route for the current kind/params from the loaded graph. */
    private fun recomputeRoute() {
        val g = graph ?: return
        val s = surface ?: return
        if (kind == TaxiKind.NONE) return
        val engine = TaxiRouteEngine(g, s)
        // Pass the aircraft heading only while it is actually taxiing, so the route sets off in
        // the direction of travel and never opens with a 180° pivot in place. A parked or
        // stopped aircraft (at the gate, or held short) reports ~0 kt — its orientation isn't a
        // taxi direction — so no heading is applied. The engine additionally ignores heading
        // while the start is anchored at the stand.
        val ac = displayAircraft
        val heading: Double? = if ((ac?.groundSpeedKnots ?: 0.0) > 3) ac?.headingDegrees else null
        val request: TaxiRouteEngine.Request = if (kind == TaxiKind.DEPARTURE) {
            val p = departureRouteParams(s)
            TaxiRouteEngine.Request(
                startCoordinate = p.start,
                startGateName = p.gate,
                isDeparture = true,
                assignedRunwayIdent = p.runway,
                arrivalGateName = null,
                aircraft = aircraftClass,
                aircraftHeadingDegrees = heading,
            )
        } else {
            val p = arrivalRouteParams(s)
            TaxiRouteEngine.Request(
                startCoordinate = p.start,
                startGateName = null,
                isDeparture = false,
                assignedRunwayIdent = null,
                arrivalGateName = p.gate,
                aircraft = aircraftClass,
                aircraftHeadingDegrees = heading,
            )
        }

        val r = engine.route(request)
        if (r != null) {
            update { it.copy(route = r, routeConfidence = r.confidence) }
            if (mockMode) {
                assignedRunway = r.holdShortRunway ?: assignedRunway
                gate = r.arrivalGate ?: gate
            }
            log(
                "Taxi route ${if (kind == TaxiKind.DEPARTURE) "DEP" else "ARR"} " +
                    "${r.destinationLabel}: ${r.distanceMeters.toInt()} m, " +
                    "${r.crossings.size} crossing(s), ${r.confidence.title}",
            )
        } else if (mockMode && !syntheticSurface) {
            // A real, pre-cached surface couldn't be routed in the simulated demo — swap to
            // the synthetic field so the mock taxi map and drive still work.
            log("Mock demo: real surface unroutable for $icao; using synthetic fallback")
            installSyntheticSurface(loadGeneration)
            return
        } else {
            update {
                it.copy(
                    route = null,
                    routeConfidence = if (s.hasUsableGeometry) {
                        SurfaceConfidence.LOW
                    } else {
                        SurfaceConfidence.UNAVAILABLE
                    },
                )
            }
            log("Taxi route could not be calculated (${if (kind == TaxiKind.DEPARTURE) "DEP" else "ARR"})")
        }
        updateInstruction()
        // A generic clearance issued while the surface was still loading is now
        // superseded by the detailed OSM route clearance.
        issueDeferredTaxiClearanceIfNeeded()
        // If the pilot already read back the taxi clearance, reveal the map now.
        if (taxiReadBack) revealIfReady()
    }

    private data class DepartureParams(val start: Coordinate, val gate: String?, val runway: String)

    /**
     * Start coordinate, gate name, and runway for the departure route request.
     * The synthetic field uses its built-in demo gate/runway; a real surface (live or the
     * pre-cached mock demo) uses the entered gate + assigned runway. In the simulated demo
     * the start is anchored at the real gate stand, since the mock ticker teleports the
     * aircraft to the route start.
     */
    private fun departureRouteParams(surface: AirportSurfaceModel): DepartureParams {
        if (syntheticSurface) {
            return DepartureParams(
                MockAirportSurface.gateCoordinate(reference), mockGate(), mockPrimaryRunway(),
            )
        }
        if (mockMode) {
            val stand = simulatedGateStand(surface, startGate)
            if (stand != null) {
                return DepartureParams(stand.coordinate.toCoordinate(), stand.name, assignedRunway)
            }
        }
        return DepartureParams(
            pendingStart ?: reference,
            if (startGate.isEmpty()) null else startGate,
            assignedRunway,
        )
    }

    private data class ArrivalParams(val start: Coordinate, val gate: String?)

    /**
     * Start coordinate and gate name for the arrival route request. The synthetic field
     * uses its built-in runway-exit / demo gate; a real surface uses the entered gate,
     * starting the rollout where an aircraft would exit after landing.
     */
    private fun arrivalRouteParams(surface: AirportSurfaceModel): ArrivalParams {
        if (syntheticSurface) {
            return ArrivalParams(MockAirportSurface.runwayExitCoordinate(reference), mockGate())
        }
        if (mockMode) {
            val start = simulatedRolloutStart(surface) ?: pendingStart ?: reference
            // Resolve the entered gate to a real stand so the taxi ends at (and names) an
            // actual United-area gate even when the exact gate isn't in the OSM data.
            val resolved = simulatedGateStand(surface, gate)?.name
                ?: (if (gate.isEmpty()) null else gate)
            return ArrivalParams(start, resolved)
        }
        return ArrivalParams(pendingStart ?: reference, if (gate.isEmpty()) null else gate)
    }

    /**
     * Resolve the pilot's entered gate to a real stand on the loaded surface for the mock
     * demo: the exact gate when present, else a gate on the same concourse (same leading
     * letter — e.g. a United "C…" gate), else any gate/stand. This keeps the simulated taxi
     * starting/ending at a real stand even when the exact gate isn't mapped.
     */
    private fun simulatedGateStand(surface: AirportSurfaceModel, name: String): SurfaceParking? {
        val key = name.trim { it == ' ' || it == '\t' }
        if (key.isNotEmpty()) {
            surface.parking(key)?.let { return it }
        }
        val letter = key.takeWhile { it.isLetter() }.uppercase()
        // Restricted to `routableStands`: a gate node superseded by its own `parking_position`
        // has no node in the graph, so falling back to one would strand the simulated route.
        val stands = surface.routableStands
        if (letter.isNotEmpty()) {
            stands.firstOrNull { it.name.uppercase().startsWith(letter) }?.let { return it }
        }
        return stands.firstOrNull() ?: surface.parkingPositions.firstOrNull()
    }

    /**
     * Where the simulated arrival rollout begins on a real surface: the far end of the
     * arrival runway (where an aircraft exits after landing), snapped to the surface by the
     * route engine. Falls back to the longest runway's far end when the specific runway
     * isn't known or mapped.
     */
    private fun simulatedRolloutStart(surface: AirportSurfaceModel): Coordinate? {
        val ident = arrivalRunway.trim { it == ' ' || it == '\t' }
        if (ident.isNotEmpty()) {
            surface.runwayEnd(ident)?.let { return it.oppositeThreshold.toCoordinate() }
        }
        val longest = surface.runways.maxByOrNull { runwayLengthMeters(it) }
        val firstIdent = longest?.idents?.firstOrNull()
        if (firstIdent != null) {
            surface.runwayEnd(firstIdent)?.let { return it.oppositeThreshold.toCoordinate() }
        }
        return surface.runwayEnds.firstOrNull()?.oppositeThreshold?.toCoordinate()
    }

    private fun runwayLengthMeters(r: SurfaceRunway): Double {
        val a = r.centerline.firstOrNull()?.toCoordinate() ?: return 0.0
        val b = r.centerline.lastOrNull()?.toCoordinate() ?: return 0.0
        return SurfaceGeometry.distanceMeters(a, b)
    }

    /**
     * Issue the detailed OSM taxi clearance that couldn't be sent when the taxi began
     * because the live surface was still loading (an uncached field loads asynchronously).
     * Emits the route clearance — the departure runway route, or the arrival gate route —
     * superseding the generic one, and re-arms its read-back so the pilot's acknowledgement
     * reveals the taxi map. Implements the "its Ground clearance replaces the generic one"
     * behavior for both departure and arrival asynchronously loaded surfaces.
     */
    private fun issueDeferredTaxiClearanceIfNeeded() {
        if (!pendingDetailedClearance || kind == TaxiKind.NONE) return
        val r = route ?: return
        if (!routeConfidence.allowsDetailedRouting) return
        pendingDetailedClearance = false
        emit(routeClearance(r, cs()))
        if (kind == TaxiKind.DEPARTURE) {
            val runway = r.holdShortRunway ?: assignedRunway
            logATC(
                "OSM taxi route ready — superseding generic clearance with detailed route to runway $runway",
            )
        } else {
            val g = r.arrivalGate ?: gate
            logATC(
                "OSM taxi route ready — superseding generic clearance with detailed route to " +
                    if (g.isEmpty()) "parking" else "gate $g",
            )
        }
        update { it.copy(awaitingTaxiReadback = true) }
        lastIssuedTaxiClearanceSignature = taxiClearanceSignature(r)
    }

    // MARK: - Taxi clearance text (for the app model to post)

    /**
     * The Ground taxi clearance for the current route, or a conservative fallback when
     * confidence is too low / no route. Returns null when the caller should keep its own
     * generic clearance (route not computed yet).
     */
    fun taxiClearance(callsign: PhraseologyEngine.Callsign): ATCTransmission? {
        if (kind == TaxiKind.NONE) return null
        val r = route
        if (r != null && routeConfidence.allowsDetailedRouting) return routeClearance(r, callsign)
        // Route unavailable/low: conservative departure fallback (arrival keeps generic).
        if (kind == TaxiKind.DEPARTURE && status == AirportSurfaceStatus.Ready) {
            return phraseology.lowConfidenceTaxi(callsign, assignedRunway)
        }
        if (kind == TaxiKind.DEPARTURE && status is AirportSurfaceStatus.Unavailable) {
            return phraseology.lowConfidenceTaxi(callsign, assignedRunway)
        }
        return null
    }

    /**
     * Mark that an OSM-based taxi clearance was issued (so a subsequent Read Back
     * reveals the taxi map). Pass [supersedeWhenRouteReady] = true when the clearance
     * that went out was the generic fallback because the live surface was still
     * loading — the detailed route clearance is then issued automatically once the
     * asynchronous load resolves.
     */
    fun taxiClearanceIssued(supersedeWhenRouteReady: Boolean = false) {
        update { it.copy(awaitingTaxiReadback = true) }
        pendingDetailedClearance = supersedeWhenRouteReady
        // Record what the pilot was just cleared for so a later recalculation can tell whether
        // the route materially changed. When the clearance that went out was the generic
        // fallback (no detailed route yet), this is null until the deferred detailed clearance
        // resolves and records its own signature.
        lastIssuedTaxiClearanceSignature = taxiClearanceSignature(route)
    }

    /** Called by the app model after the pilot reads back the taxi clearance. */
    fun taxiReadBackComplete() {
        update { it.copy(awaitingTaxiReadback = false) }
        taxiReadBack = true
        revealIfReady()
    }

    private fun revealIfReady() {
        if (!taxiReadBack) return
        if (route == null && !status.isReady) return
        update { it.copy(taxiMapVisible = true) }
        updateInstruction()
        if (mockMode) startMockDrive()
    }

    /**
     * Re-reveal the taxi map after an app relaunch mid-taxi. The pilot already read the
     * clearance back before the app was swiped away, so there is no fresh read-back to
     * wait on — mark it acknowledged and show the map as soon as the route is available
     * (immediately if the surface was cached, otherwise once the async load resolves via
     * [recomputeRoute]). No-op unless a taxi is being serviced. Live only: the map is
     * then driven by resuming telemetry, not the mock ticker.
     */
    fun resumeTaxiAfterRelaunch() {
        if (kind == TaxiKind.NONE) return
        update { it.copy(awaitingTaxiReadback = false) }
        taxiReadBack = true
        revealIfReady()
    }

    // MARK: - Hide / clear

    /** Hide the taxi map (Ground→Tower hand-off, or ramp/gate phase after arrival). */
    fun hideTaxiMap() {
        update { it.copy(taxiMapVisible = false, mapExpanded = false) }
        // The ground-taxi phase is over — don't let a late-resolving surface load
        // supersede the clearance with a stray Ground call after the hand-off.
        pendingDetailedClearance = false
        stopMockDrive()
        // Clear the drawn geometry so a removed map never briefly shows the previous
        // airport's surface while the next one loads (e.g. the arrival map popping up
        // still showing the departure field). The correct field's surface reloads —
        // from the warm cache — when the next taxi begins.
        clearMapGeometry()
    }

    /**
     * Drop the drawn map geometry (route, surface, graph, aircraft) so a removed map
     * leaves nothing behind for the next taxi to briefly show. [beginDeparture] /
     * [beginArrival] reload the correct field's surface before the map is shown again.
     */
    private fun clearMapGeometry() {
        graph = null
        update {
            it.copy(
                route = null,
                surface = null,
                routeConfidence = SurfaceConfidence.UNAVAILABLE,
                datasetConfidence = SurfaceConfidence.UNAVAILABLE,
                displayAircraft = null,
                progress = null,
                nextInstruction = "",
                offRoute = false,
                reachedDestination = false,
                approachingRunwayHandoff = false,
                approachingRunwayLineup = false,
                status = AirportSurfaceStatus.Idle,
            )
        }
    }

    /** Fully reset the taxi feature (clear flight / reset app data). */
    fun clear() {
        hideTaxiMap()
        update { it.copy(kind = TaxiKind.NONE, route = null, routeConfidence = SurfaceConfidence.UNAVAILABLE) }
        resetTaxiProgress()
        update {
            it.copy(
                awaitingTaxiReadback = false,
                nextInstruction = "",
                displayAircraft = null,
                progress = null,
            )
        }
    }

    // MARK: - Live telemetry

    /**
     * Forward live aircraft telemetry (live mode only). No-op in mock mode or when the
     * taxi map isn't showing.
     *
     * The aircraft marker is placed as soon as the map is visible — it is deliberately
     * **not** gated on a route existing yet. Right after landing at an uncached field the
     * surface can be Ready while the route is momentarily uncomputable from the runway
     * rollout point; the old `route != null` guard dropped every sample in that window, so
     * the plane never appeared and — since nothing re-ran the routing during a steady taxi
     * — the map stayed blank until the app was relaunched. Now the plane always shows, and
     * while the route is still missing we re-route from the live position so it fills in
     * (and its detailed Ground clearance supersedes the generic one) the moment the
     * aircraft reaches a routable point.
     */
    fun updateLive(
        coordinate: Coordinate?,
        heading: Double?,
        onGround: Boolean?,
        groundSpeed: Double?,
    ) {
        if (mockMode || kind == TaxiKind.NONE || !taxiMapVisible) return
        if (coordinate == null || !coordinate.isValid) return
        update {
            it.copy(
                displayAircraft = TaxiAircraft(
                    coordinate = GeoCoordinate(coordinate),
                    headingDegrees = heading ?: it.displayAircraft?.headingDegrees ?: 0.0,
                    onGround = onGround ?: true,
                    groundSpeedKnots = groundSpeed ?: 0.0,
                ),
            )
        }
        if (route != null) {
            advanceTracking()
        } else {
            retryRouteFromLivePosition(coordinate)
        }
    }

    /**
     * Recover a taxi whose surface loaded but couldn't be routed at the earlier start
     * point (e.g. straight off the runway). Re-runs the route from the live position —
     * throttled so the A* only re-runs once the aircraft has moved — so the route appears
     * as soon as the aircraft is somewhere routable instead of the map staying empty. No-op
     * until the surface is ready (a still-loading / unavailable surface has nothing to
     * route on and [recomputeRoute] would no-op anyway).
     */
    private fun retryRouteFromLivePosition(coordinate: Coordinate) {
        if (!status.isReady) return
        val last = lastRouteRetryCoordinate
        if (last != null && SurfaceGeometry.distanceMeters(last, coordinate) < 10) return
        lastRouteRetryCoordinate = coordinate
        pendingStart = coordinate
        recomputeRoute()
    }

    // MARK: - Mock drive

    private fun startMockDrive() {
        stopMockDrive()
        mockAlong = 0.0
        lastAlong = 0.0
        val r = route
        val first = r?.line?.firstOrNull()
        if (first != null) {
            update {
                it.copy(
                    displayAircraft = TaxiAircraft(
                        coordinate = GeoCoordinate(first),
                        headingDegrees = mockHeading(0.0),
                        onGround = true,
                        groundSpeedKnots = 0.0,
                    ),
                )
            }
        }
        mockTask = scope.launch {
            while (isActive) {
                delay(mockTickMillis)
                if (!isActive) break
                mockTick()
            }
        }
    }

    private fun stopMockDrive() {
        mockTask?.cancel()
        mockTask = null
    }

    private fun mockTick() {
        if (!mockMode || !taxiMapVisible) return
        val r = route ?: return
        val line = r.line
        if (line.size < 2) return

        // How far the aircraft may advance right now: stop at the first not-yet-cleared
        // crossing's hold-short point.
        var allowed = r.distanceMeters
        for (c in r.crossings) {
            if (completedCrossings.contains(c.index)) continue
            if (authorizedCrossingIndex == c.index) continue
            val holdAlong = max(0.0, c.alongMeters - holdBeforeCrossingMeters)
            if (holdAlong >= mockAlong - 1) {
                allowed = min(allowed, holdAlong)
                break
            }
        }
        val previous = mockAlong
        mockAlong = min(allowed, mockAlong + mockStepMeters)
        val moved = mockAlong - previous > 0.05

        val point = SurfaceGeometry.pointAlong(line, mockAlong)
        if (point != null) {
            update {
                it.copy(
                    displayAircraft = TaxiAircraft(
                        coordinate = GeoCoordinate(point),
                        headingDegrees = mockHeading(mockAlong),
                        onGround = true,
                        groundSpeedKnots = if (moved) 16.0 else 0.0,
                    ),
                )
            }
        }
        advanceTracking()
        if (mockAlong >= r.distanceMeters - 1) stopMockDrive()
    }

    private fun mockHeading(along: Double): Double {
        val line = route?.line ?: return 0.0
        if (line.size < 2) return 0.0
        val a = SurfaceGeometry.pointAlong(line, along) ?: line[0]
        val b = SurfaceGeometry.pointAlong(line, along + 12) ?: line[line.size - 1]
        if (SurfaceGeometry.distanceMeters(a, b) < 0.5) return displayAircraft?.headingDegrees ?: 0.0
        return Geo.bearing(a, b)
    }

    // MARK: - Tracking + crossing workflow

    private fun advanceTracking() {
        val route = this.route ?: return
        val ac = displayAircraft ?: return
        val prog = tracker.progress(ac.coordinate.toCoordinate(), route, minAlong = lastAlong)
        lastAlong = max(lastAlong, prog.alongMeters)
        update { it.copy(progress = prog) }

        // Off-route (live only; mock stays on the synthetic line). Requires the aircraft
        // to stay beyond the (generous) cross-track threshold for several consecutive
        // ticks before the banner shows, so a brief wander or an OSM/scenery mismatch
        // near a turn doesn't flap the "off route" state.
        if (!mockMode) {
            if (!prog.onRoute) {
                offRouteTicks += 1
                if (offRouteTicks >= offRouteTickThreshold) {
                    // Auto-recalculate when enabled and route confidence is still acceptable:
                    // re-plan from the current position and, if the route materially changes,
                    // Ground issues a fresh taxi clearance with a read-back (never a silent
                    // swap). Otherwise just raise the off-route banner and let the pilot choose
                    // Recalculate / Continue / Request New Taxi.
                    if (autoRecalculate && routeConfidence.allowsDetailedRouting) {
                        // Re-plans (and updates instructions) from the current position; the
                        // rest of this tick would run against the now-superseded route/progress,
                        // so hand off to the next tick's fresh tracking pass.
                        recalculateRoute()
                        return
                    }
                    update { it.copy(offRoute = true) }
                }
            } else {
                offRouteTicks = 0
                update { it.copy(offRoute = false) }
            }
        }

        // Destination.
        if (prog.reachedDestination && !reachedDestination) {
            update { it.copy(reachedDestination = true) }
        }

        // Approaching the departure runway: a short distance before the hold-short, cue
        // Ground to hand the pilot to Tower to *monitor* ("monitor Tower on …"). One-shot.
        // Requires the aircraft to have left the gate (some distance travelled) so a very
        // short taxi doesn't fire it on the stand. OSM maps no monitor-tower line, so the
        // point is derived from the route rather than a feature.
        if (kind == TaxiKind.DEPARTURE && !approachingRunwayHandoff &&
            prog.alongMeters > 15 && prog.remainingMeters <= OSMSurface.MONITOR_TOWER_LEAD_METERS
        ) {
            update { it.copy(approachingRunwayHandoff = true) }
        }

        // Nearing the runway hold-short — at the same lead distance the automatic
        // runway-crossing clearance uses — cue Tower to issue "line up and wait" while the
        // aircraft is still rolling up, so it can make a rolling line-up. One-shot.
        if (kind == TaxiKind.DEPARTURE && !approachingRunwayLineup &&
            prog.alongMeters > 15 && prog.remainingMeters <= holdIssueMeters
        ) {
            update { it.copy(approachingRunwayLineup = true) }
        }

        runCrossingWorkflow(route, ac, prog)
        updateInstruction()
    }

    private fun runCrossingWorkflow(
        route: SurfaceTaxiRoute,
        ac: TaxiAircraft,
        prog: RouteTracker.Progress,
    ) {
        // Pick the crossing currently being worked.
        if (workedCrossingIndex == null) {
            val idx = prog.nextCrossingIndex
            if (idx != null && !completedCrossings.contains(idx)) workedCrossingIndex = idx
        }
        val wc = workedCrossingIndex
        if (wc == null || wc !in route.crossings.indices) {
            if (crossingState != RunwayCrossingState.NO_CROSSING_PENDING && !crossingState.isAuthorized) {
                setCrossing(RunwayCrossingState.NO_CROSSING_PENDING)
            }
            update { it.copy(activeCrossing = null) }
            return
        }
        val c = route.crossings[wc]
        update { it.copy(activeCrossing = c) }
        val along = prog.alongMeters
        val dCross = c.alongMeters - along
        val holdAlong = max(0.0, c.alongMeters - holdBeforeCrossingMeters)
        val dHold = holdAlong - along
        val authorized = authorizedCrossingIndex == c.index

        if (authorized) {
            if (along >= c.alongMeters + vacateMarginMeters) {
                setCrossing(RunwayCrossingState.RUNWAY_VACATED)
                completedCrossings.add(c.index)
                if (!emittedResumeFor.contains(c.index)) {
                    emittedResumeFor.add(c.index)
                    emit(
                        phraseology.resumeTaxi(
                            cs(), assignedRunway, kind == TaxiKind.DEPARTURE, gate,
                        ),
                    )
                }
                setCrossing(RunwayCrossingState.TAXI_RESUMED)
                workedCrossingIndex = null
                if (authorizedCrossingIndex == c.index) authorizedCrossingIndex = null
            } else if (along >= c.alongMeters) {
                setCrossing(RunwayCrossingState.RUNWAY_CENTERLINE_CROSSED)
            } else if (dCross <= corridorEnterMeters) {
                setCrossing(RunwayCrossingState.CROSSING_IN_PROGRESS)
            } else {
                setCrossing(RunwayCrossingState.CROSSING_AUTHORIZED)
            }
            return
        }

        // Not authorized — unauthorized-entry safety net (live; mock never trips it). This
        // applies **only** in the manual mode (automatic crossing calls off), where the pilot
        // is expected to hold short and Request Crossing: entering the corridor without a
        // clearance then warrants a warning. With automatic calls on the companion ALWAYS
        // clears the crossing (a generous distance back), so it never holds or stops the
        // aircraft short of a runway crossing — the runway may well be clear for the pilot.
        // Once a crossing clearance has been issued (awaiting the pilot's read-back), the
        // aircraft moving up to and across the runway is expected, so the warning is also
        // suppressed once a clearance is outstanding for this crossing.
        if (!autoCrossingCalls && dCross <= corridorEnterMeters && ac.groundSpeedKnots > 1 &&
            headingTowardCrossing(ac, c) && !issuedClearanceFor.contains(c.index)
        ) {
            unauthorizedTicks += 1
            if (unauthorizedTicks >= 2) {
                logUnauthorized(c, along, ac, dHold)
                if (dCross <= 8) {
                    emitOnceUnauthorized(stop = true, c = c)
                } else {
                    emitOnceUnauthorized(stop = false, c = c)
                }
                setCrossing(RunwayCrossingState.UNAUTHORIZED_CROSSING_DETECTED)
            }
            return
        } else {
            unauthorizedTicks = 0
        }

        // Normal pre-authorization sequence.
        if (dCross <= detectAheadMeters && crossingState == RunwayCrossingState.NO_CROSSING_PENDING) {
            setCrossing(RunwayCrossingState.CROSSING_DETECTED_AHEAD)
        }
        if (dHold <= approachMeters && crossingState == RunwayCrossingState.CROSSING_DETECTED_AHEAD) {
            setCrossing(RunwayCrossingState.APPROACHING_HOLDING_POSITION)
        }

        val lowConfidence = c.confidence == SurfaceConfidence.LOW ||
            routeConfidence == SurfaceConfidence.LOW ||
            routeConfidence == SurfaceConfidence.UNAVAILABLE
        // ALWAYS clear to cross when automatic crossing calls are on — regardless of OSM
        // confidence. The companion never holds the pilot short or stops them at a crossing
        // (it could be completely clear for them); it just issues the crossing clearance
        // automatically a generous distance back so a slow taxi has time to read it back
        // before the threshold. Turning automatic crossing calls off (Settings) restores the
        // conservative manual Request-Crossing path below.
        val autoAllowed = autoCrossingCalls

        if (autoAllowed && !pilotHeldFor.contains(c.index)) {
            // Ground proactively issues the crossing clearance a generous distance before the
            // runway threshold — no redundant hold-short call, and never a stop. The taxi
            // clearance already named the first crossing as the clearance limit; the pilot
            // still reads the crossing clearance back before it is authorized.
            if (dHold <= holdIssueMeters && !issuedClearanceFor.contains(c.index)) {
                issueCrossingClearance(c)
            }
        } else {
            // Automatic calls off, or the pilot asked to hold: issue an explicit hold-short
            // and wait for the pilot to Request Crossing before clearing.
            if (dHold <= holdIssueMeters && !issuedHoldShortFor.contains(c.index)) {
                issuedHoldShortFor.add(c.index)
                emit(phraseology.holdShort(cs(), c.runwayIdent))
                setCrossing(RunwayCrossingState.HOLD_SHORT_INSTRUCTION_ISSUED)
            }
            if (dHold <= atHoldMeters && ac.groundSpeedKnots < 4) {
                if (crossingState != RunwayCrossingState.HOLDING_SHORT &&
                    crossingState != RunwayCrossingState.CROSSING_CLEARANCE_ISSUED &&
                    crossingState != RunwayCrossingState.AWAITING_PILOT_READBACK
                ) {
                    setCrossing(
                        if (lowConfidence) {
                            RunwayCrossingState.LOW_CONFIDENCE_CROSSING_DATA
                        } else {
                            RunwayCrossingState.HOLDING_SHORT
                        },
                    )
                }
                holdSettleTicks += 1
            }
            val mayIssue = (
                crossingState == RunwayCrossingState.HOLDING_SHORT ||
                    crossingState == RunwayCrossingState.LOW_CONFIDENCE_CROSSING_DATA
                ) &&
                holdSettleTicks >= settleTicks &&
                !issuedClearanceFor.contains(c.index) &&
                userRequestedCrossingFor.contains(c.index)
            if (mayIssue) {
                issueCrossingClearance(c)
            }
        }
    }

    private fun issueCrossingClearance(c: RouteCrossing) {
        issuedClearanceFor.add(c.index)
        setCrossing(RunwayCrossingState.CROSSING_CLEARANCE_READY)
        val via = crossingTaxiwayName(c)
        emit(phraseology.crossingClearance(cs(), c.runwayIdent, atTaxiway = via))
        pendingCrossingIndex = c.index
        update { it.copy(awaitingCrossingReadback = true) }
        setCrossing(RunwayCrossingState.CROSSING_CLEARANCE_ISSUED)
        setCrossing(RunwayCrossingState.AWAITING_PILOT_READBACK)
    }

    private fun crossingTaxiwayName(c: RouteCrossing): String? =
        graph?.edges?.firstOrNull { it.id == c.edgeID }?.taxiwayName

    /**
     * The runway ident of the first runway crossing along a route (the earliest by
     * along-distance), or null when the route crosses no runway. Used to hold the pilot
     * short of the first crossing in the initial Ground taxi clearance.
     */
    private fun firstCrossingRunway(route: SurfaceTaxiRoute): String? =
        route.crossings.minByOrNull { it.alongMeters }?.runwayIdent

    /**
     * Build the Ground taxi clearance for a computed route — the departure runway route or the
     * arrival gate route — holding the pilot short of the first runway crossing. Shared by the
     * initial clearance, the deferred (async-load) clearance, and the recalculation clearance
     * so all three read identically.
     */
    private fun routeClearance(
        route: SurfaceTaxiRoute,
        callsign: PhraseologyEngine.Callsign,
    ): ATCTransmission {
        if (kind == TaxiKind.DEPARTURE) {
            return phraseology.taxiClearance(
                cs = callsign, route = route,
                runway = route.holdShortRunway ?: assignedRunway,
                holdShortCrossing = firstCrossingRunway(route),
            )
        }
        return phraseology.arrivalTaxi(
            cs = callsign, route = route, gate = route.arrivalGate ?: gate,
            holdShortCrossing = firstCrossingRunway(route),
        )
    }

    /**
     * A stable identity for the taxi *instruction* a route yields — the taxiway sequence, the
     * destination runway/gate, and the first hold-short crossing. Two routes with the same
     * signature produce the same spoken clearance, so a recalculation between them is not
     * re-issued; a different signature means a genuinely new instruction. Null when there is no
     * detail-worthy route to clear (unavailable / low-confidence fallback).
     */
    private fun taxiClearanceSignature(route: SurfaceTaxiRoute?): String? {
        if (route == null || !routeConfidence.allowsDetailedRouting) return null
        val destination = if (kind == TaxiKind.DEPARTURE) {
            route.holdShortRunway ?: assignedRunway
        } else {
            route.arrivalGate ?: gate
        }
        val holdShort = firstCrossingRunway(route) ?: ""
        val kindTag = if (kind == TaxiKind.DEPARTURE) "DEP" else "ARR"
        return "$kindTag|$destination|${route.taxiwaySequence.joinToString(">")}|$holdShort"
    }

    // MARK: - Pilot / user actions

    /** Called by the app model after the pilot reads back a crossing clearance. */
    fun crossingReadbackReceived() {
        val idx = pendingCrossingIndex ?: return
        authorizedCrossingIndex = idx
        pendingCrossingIndex = null
        update { it.copy(awaitingCrossingReadback = false) }
        setCrossing(RunwayCrossingState.CROSSING_AUTHORIZED)
    }

    /** User taps Request Crossing (Medium/Low confidence, or when auto calls are off). */
    fun requestCrossing() {
        val wc = workedCrossingIndex ?: return
        val route = this.route ?: return
        if (wc !in route.crossings.indices) return
        val c = route.crossings[wc]
        userRequestedCrossingFor.add(c.index)
        pilotHeldFor.remove(c.index)
        // The pilot has explicitly reported holding short and requested the crossing, so issue
        // the crossing clearance now rather than waiting on the exact hold-distance / settle
        // heuristics. The OSM hold point rarely lines up with the simulator scenery, so those
        // heuristics could leave the button doing nothing once the aircraft had already
        // stopped at the threshold — the reported "button doesn't work" case.
        if (issuedClearanceFor.contains(c.index) || authorizedCrossingIndex == c.index) return
        issueCrossingClearance(c)
    }

    fun holdPosition() {
        val wc = workedCrossingIndex ?: return
        val route = this.route ?: return
        if (wc !in route.crossings.indices) return
        val c = route.crossings[wc]
        // Remember the pilot chose to hold so the automatic clearance doesn't immediately
        // re-clear them; they resume by tapping Request Crossing. Recording the hold-short
        // also stops the manual path from emitting a second hold-short next tick.
        pilotHeldFor.add(c.index)
        issuedHoldShortFor.add(c.index)
        emit(phraseology.holdShort(cs(), c.runwayIdent))
        setCrossing(RunwayCrossingState.HOLDING_SHORT)
    }

    fun requestAlternateRoute() = recomputeRoute()

    // Off-route actions.
    fun recalculateRoute() {
        offRouteTicks = 0
        lastAlong = 0.0
        update { it.copy(offRoute = false) }
        // Recompute from the current aircraft position.
        displayAircraft?.let { pendingStart = it.coordinate.toCoordinate() }
        val previousSignature = lastIssuedTaxiClearanceSignature
        recomputeRoute()
        issueRecalculatedTaxiClearanceIfChanged(previousSignature)
    }

    /**
     * After a recalculation (the user tapping Recalculate / Request New Taxi Instructions, or
     * an automatic off-route recalculation), issue a fresh Ground taxi clearance — with its own
     * read-back — whenever the recalculated route is a materially different instruction than the
     * one the pilot last read back. An identical route stays silent so recalculating doesn't
     * repeat the same clearance. A recalculation issued while the async detailed clearance is
     * still pending is left to that deferred path (which arms its own read-back).
     */
    private fun issueRecalculatedTaxiClearanceIfChanged(previousSignature: String?) {
        if (pendingDetailedClearance) return
        val route = this.route ?: return
        if (!routeConfidence.allowsDetailedRouting) return
        val newSignature = taxiClearanceSignature(route)
        if (newSignature == previousSignature) return
        emit(routeClearance(route, cs()))
        update { it.copy(awaitingTaxiReadback = true) }
        lastIssuedTaxiClearanceSignature = newSignature
        logATC("Taxi route recalculated — issuing updated Ground clearance for the new route")
    }

    fun continueOriginalRoute() {
        offRouteTicks = 0
        update { it.copy(offRoute = false) }
    }

    fun requestNewTaxiInstructions() = recalculateRoute()

    /** Manual refresh of the airport data (user-initiated). */
    fun refreshData() {
        if (icao.isEmpty()) return
        val key = icao
        val ref = reference
        val mock = mockMode
        scope.launch { loadSurface(key, ref, mock, forceRefresh = true) }
    }

    /** Delete cached surfaces (Settings). */
    fun clearCache() {
        simulatedSurfaces.clear()
        val p = provider ?: return
        scope.launch { p.clearCache() }
    }

    suspend fun cacheInfo(): AirportSurfaceProvider.CacheInfo =
        provider?.cacheInfo() ?: AirportSurfaceProvider.CacheInfo(emptyList(), 0)

    // MARK: - Helpers

    private fun cs(): PhraseologyEngine.Callsign =
        callsignProvider?.invoke() ?: PhraseologyEngine.Callsign("Aircraft", "aircraft")

    /** Gate label used for the mock demo (the flight's gate, else a default). */
    private fun mockGate(): String = gate.ifEmpty { MockAirportSurface.DEFAULT_GATE_NAME }

    /** Primary runway used for the mock demo (the flight's assigned runway, else a default). */
    private fun mockPrimaryRunway(): String =
        assignedRunway.ifEmpty { MockAirportSurface.DEFAULT_RUNWAY_IDENT }

    private fun emit(tx: ATCTransmission) {
        emitATC?.invoke(tx.copy(timestampMillis = clock.nowMillis()))
    }

    private fun setCrossing(state: RunwayCrossingState) {
        if (crossingState != state) update { it.copy(crossingState = state) }
    }

    private fun headingTowardCrossing(ac: TaxiAircraft, c: RouteCrossing): Boolean {
        val bearing = Geo.bearing(ac.coordinate.toCoordinate(), c.point.toCoordinate())
        return Geo.headingDifference(ac.headingDegrees, bearing) < 70
    }

    private fun emitOnceUnauthorized(stop: Boolean, c: RouteCrossing) {
        // Debounced by the caller; emit only on entering the unauthorized state.
        if (crossingState == RunwayCrossingState.UNAUTHORIZED_CROSSING_DETECTED) return
        emit(
            if (stop) {
                phraseology.stopWarning(cs(), c.runwayIdent)
            } else {
                phraseology.holdPositionWarning(cs(), c.runwayIdent)
            },
        )
    }

    private fun logUnauthorized(c: RouteCrossing, along: Double, ac: TaxiAircraft, dHold: Double) {
        logATC(
            String.format(
                Locale.US,
                "Unauthorized runway entry watch RWY %s: state=%s auth=%s gs=%.0f hdg=%.0f dHold=%.0fm conf=%s",
                c.runwayIdent, crossingState.title,
                if (authorizedCrossingIndex == c.index) "yes" else "no",
                ac.groundSpeedKnots, ac.headingDegrees, dHold, c.confidence.title,
            ),
        )
    }

    private fun updateInstruction() {
        if (route == null) {
            update { it.copy(nextInstruction = "") }
            return
        }
        if (offRoute) {
            update { it.copy(nextInstruction = "Off assigned taxi route") }
            return
        }
        // Crossing / hold-short guidance names both directions of the physical runway
        // ("Hold short of runway 6R-24L"), matching the spoken clearances.
        val active = activeCrossing
        if (awaitingCrossingReadback && active != null) {
            val text = "Read back: cross runway ${Phonetic.runwayPairDisplay(active.runwayIdent)}"
            update { it.copy(nextInstruction = text) }
            return
        }
        when (crossingState) {
            RunwayCrossingState.HOLD_SHORT_INSTRUCTION_ISSUED,
            RunwayCrossingState.HOLDING_SHORT,
            RunwayCrossingState.APPROACHING_HOLDING_POSITION,
            RunwayCrossingState.LOW_CONFIDENCE_CROSSING_DATA,
            -> if (active != null) {
                val text = "Hold short of runway ${Phonetic.runwayPairDisplay(active.runwayIdent)}"
                update { it.copy(nextInstruction = text) }
                return
            }
            RunwayCrossingState.CROSSING_AUTHORIZED,
            RunwayCrossingState.CROSSING_IN_PROGRESS,
            RunwayCrossingState.RUNWAY_CENTERLINE_CROSSED,
            -> if (active != null) {
                val text = "Crossing runway ${Phonetic.runwayPairDisplay(active.runwayIdent)}"
                update { it.copy(nextInstruction = text) }
                return
            }
            RunwayCrossingState.UNAUTHORIZED_CROSSING_DETECTED,
            -> if (active != null) {
                val text = "Hold short of runway ${Phonetic.runwayPairDisplay(active.runwayIdent)}"
                update { it.copy(nextInstruction = text) }
                return
            }
            else -> Unit
        }
        if (reachedDestination) {
            val text = if (kind == TaxiKind.DEPARTURE) {
                "Hold short runway ${Phonetic.runwayPairDisplay(assignedRunway)} — monitor Tower"
            } else {
                "Arriving at ${route?.destinationLabel ?: "gate"}"
            }
            update { it.copy(nextInstruction = text) }
            return
        }
        if (kind == TaxiKind.DEPARTURE && approachingRunwayHandoff) {
            update { it.copy(nextInstruction = "Approaching runway $assignedRunway — monitor Tower") }
            return
        }
        val text = if (kind == TaxiKind.DEPARTURE) {
            "Taxi to runway $assignedRunway"
        } else {
            "Taxi to ${route?.destinationLabel ?: "gate"}"
        }
        update { it.copy(nextInstruction = text) }
    }

    // MARK: - Actions surfaced on the taxi map

    val crossingActions: List<TaxiMapAction> get() = _state.value.crossingActions
    val offRouteActions: List<TaxiMapAction> get() = _state.value.offRouteActions

    /**
     * The pilot's entered gate for the active taxi — the departure gate on the way out,
     * the arrival gate on the way in — used to label the taxi map's gate marker. Empty
     * when none was set.
     */
    val activeGate: String get() = gate

    /**
     * The coordinate of the arrival gate this taxi routes to — the stand matching the
     * entered gate name in the loaded surface. Used to confirm the aircraft is actually
     * parked at the gate before the flight is completed. Null when there is no arrival
     * taxi, no entered gate, or the gate isn't in the surface data, so the caller keeps
     * its default full-stop completion. Live only: in Mock Mode the scripted telemetry
     * and the synthetic surface are decoupled, so a distance check would be meaningless.
     */
    val arrivalGateCoordinate: Coordinate?
        get() {
            if (kind != TaxiKind.ARRIVAL || mockMode) return null
            val name = gate.trim { it == ' ' || it == '\t' }
            if (name.isEmpty()) return null
            val parking = surface?.parking(name) ?: return null
            return parking.coordinate.toCoordinate()
        }

    // MARK: - Test hooks
    //
    // Used by unit tests to drive the mock taxi / runway-crossing workflow deterministically
    // (the production mock drive is a coroutine ticker that does not advance within a
    // synchronous test).

    /** Begin a mock taxi and reveal the map without starting the async ticker. */
    fun beginMockTaxiForTesting(kind: TaxiKind, reference: Coordinate, runway: String, gate: String) {
        if (kind == TaxiKind.DEPARTURE) {
            beginDeparture(
                icao = "KTEST", reference = reference, aircraftName = "Boeing 737-800",
                runway = runway, gate = gate,
                startCoordinate = MockAirportSurface.gateCoordinate(reference), mock = true,
            )
        } else {
            beginArrival(
                icao = "KTEST", reference = reference, aircraftName = "Boeing 737-800",
                gate = gate, startCoordinate = MockAirportSurface.runwayExitCoordinate(reference),
                mock = true,
            )
        }
        taxiReadBack = true
        update { it.copy(taxiMapVisible = true) }
    }

    /**
     * Install a specific prebuilt surface (e.g. a low-confidence one) and reveal the
     * map, so the crossing workflow can be driven against controlled data.
     */
    fun installSurfaceForTesting(
        model: AirportSurfaceModel,
        kind: TaxiKind,
        runway: String,
        gate: String,
    ) {
        update { it.copy(kind = kind) }
        this.assignedRunway = runway
        this.gate = gate
        this.startGate = gate
        this.mockMode = true
        resetTaxiProgress()
        // A directly-installed test surface is treated as the synthetic demo field so the
        // route uses the demo gate/runway geometry.
        syntheticSurface = true
        loadGeneration += 1
        val gen = loadGeneration
        this.icao = model.icao
        this.reference = model.reference.toCoordinate()
        applyLoaded(model, gen)
        taxiReadBack = true
        update { it.copy(taxiMapVisible = true) }
    }

    /**
     * Reproduce the uncached live-departure race: a generic Ground taxi clearance is
     * issued while the surface is still loading ([taxiClearanceIssued]), then the
     * asynchronous load resolves with [model]. Mirrors [beginDeparture] +
     * [taxiClearanceIssued] + the async `applyLoaded` without performing a network fetch,
     * so the deferred-clearance path can be driven deterministically.
     */
    fun simulateDeferredDepartureForTesting(model: AirportSurfaceModel, runway: String, gate: String) {
        update { it.copy(kind = TaxiKind.DEPARTURE) }
        aircraftClass = AircraftSizeClass.MEDIUM
        assignedRunway = runway
        this.gate = gate
        this.startGate = gate
        this.mockMode = false
        resetTaxiProgress()
        pendingStart = MockAirportSurface.gateCoordinate(model.reference.toCoordinate())
        loadGeneration += 1
        val gen = loadGeneration
        this.icao = model.icao
        this.reference = model.reference.toCoordinate()
        update { it.copy(status = AirportSurfaceStatus.Loading) }
        // Generic clearance issued because the route wasn't ready yet.
        taxiClearanceIssued(supersedeWhenRouteReady = true)
        // Surface finishes loading → route computed → deferred detailed clearance emitted.
        applyLoaded(model, gen)
    }

    /**
     * Reproduce the uncached live-arrival race: a generic Ground "taxi to parking" goes
     * out while the destination surface is still loading, then the asynchronous load
     * resolves with [model] and the detailed gate route supersedes it.
     */
    fun simulateDeferredArrivalForTesting(
        model: AirportSurfaceModel,
        gate: String,
        start: Coordinate? = null,
    ) {
        update { it.copy(kind = TaxiKind.ARRIVAL) }
        aircraftClass = AircraftSizeClass.MEDIUM
        assignedRunway = ""
        this.gate = gate
        this.startGate = ""
        this.mockMode = false
        resetTaxiProgress()
        pendingStart = start ?: MockAirportSurface.runwayExitCoordinate(model.reference.toCoordinate())
        loadGeneration += 1
        val gen = loadGeneration
        this.icao = model.icao
        this.reference = model.reference.toCoordinate()
        update { it.copy(status = AirportSurfaceStatus.Loading) }
        // Generic clearance issued because the route wasn't ready yet.
        taxiClearanceIssued(supersedeWhenRouteReady = true)
        // Surface finishes loading → route computed → deferred detailed clearance emitted.
        applyLoaded(model, gen)
    }

    /**
     * Simulate the in-progress live surface fetch resolving with [model] (test hook), so
     * the withheld arrival-taxi flow can be driven without a network fetch.
     */
    fun completeSurfaceLoadForTesting(model: AirportSurfaceModel) {
        applyLoaded(model, loadGeneration)
    }

    /** Advance the mock aircraft one step (mirrors one async tick). */
    fun mockTickForTesting() = mockTick()

    /** Feed one synthetic aircraft sample through the tracker/workflow (any mode). */
    fun feedForTesting(
        coordinate: Coordinate,
        heading: Double,
        groundSpeed: Double,
        onGround: Boolean = true,
    ) {
        update {
            it.copy(
                displayAircraft = TaxiAircraft(
                    coordinate = GeoCoordinate(coordinate),
                    headingDegrees = heading,
                    onGround = onGround,
                    groundSpeedKnots = groundSpeed,
                ),
            )
        }
        advanceTracking()
    }

    /** The current calculated route (test inspection). */
    val routeForTesting: SurfaceTaxiRoute? get() = route
    val graphForTesting: SurfaceGraph? get() = graph
    val surfaceForTesting: AirportSurfaceModel? get() = surface

    /** Whether the loaded surface is the synthetic offline fallback (test inspection). */
    val usingSyntheticSurfaceForTesting: Boolean get() = syntheticSurface

    /**
     * Inject a pre-cached "real" surface for a mock airport, as [prepareSimulatedSurfaces]
     * would after fetching from OSM — so the simulated-taxi-over-a-real-surface path can be
     * driven without a network fetch.
     */
    fun injectSimulatedSurfaceForTesting(model: AirportSurfaceModel, icao: String) {
        simulatedSurfaces[icao.uppercase()] = model
    }

    /**
     * Record a demo airport's reference (as [prepareSimulatedSurfaces] would) without fetching,
     * so the "wait for the real field to download" path can be driven in tests.
     */
    fun setSimulatedReferenceForTesting(reference: Coordinate, icao: String) {
        simulatedReferences[icao.uppercase()] = reference
    }

    /**
     * Deliver a real surface to the active mock taxi as the async download would once it
     * resolves (test hook).
     */
    fun deliverSimulatedSurfaceForTesting(model: AirportSurfaceModel, icao: String) {
        adoptRealSimulatedSurface(model, icao.uppercase(), loadGeneration)
    }

    // MARK: - Diagnostics snapshot

    fun diagnosticsSnapshot(): AirportSurfaceDiagnostics {
        val g = graph
        val r = route
        val c = activeCrossing
        return AirportSurfaceDiagnostics.from(
            surface = surface,
            graph = g?.let {
                SurfaceGraphSummary(
                    nodeCount = it.nodes.size, edgeCount = it.edges.size,
                    componentCount = it.componentCount,
                    inferredConnectorCount = it.inferredConnectorCount,
                )
            },
            route = r?.let {
                TaxiRouteSummary(
                    isDeparture = it.isDeparture, destinationLabel = it.destinationLabel,
                    taxiwaysText = it.taxiwaysText, distanceMeters = it.distanceMeters,
                    crossingCount = it.crossings.size,
                )
            },
            statusText = status.text,
            datasetConfidence = datasetConfidence,
            routeConfidence = routeConfidence,
            crossingStateTitle = crossingState.title,
            crossingStateAuthorized = crossingState.isAuthorized,
            activeCrossing = c?.let { ActiveCrossingSummary(it.runwayIdent, it.confidence) },
            awaitingCrossingReadback = awaitingCrossingReadback,
            authorizedCrossingIndex = authorizedCrossingIndex,
            snappedSegment = snappedSegmentDescription,
            lastError = _state.value.lastError,
            nowMillis = clock.nowMillis(),
        )
    }

    /** Exposed for diagnostics: the graph's snapped segment under the aircraft. */
    val snappedSegmentDescription: String
        get() {
            val g = graph ?: return "—"
            val ac = displayAircraft ?: return "—"
            val nearest = g.nearestNode(ac.coordinate.toCoordinate()) ?: return "—"
            val name = nearest.node.name ?: nearest.node.runwayRef ?: nearest.node.kind.rawValue
            // `Int(_:)` in the Swift — truncation, not rounding.
            return "$name (${nearest.distanceMeters.toInt()} m)"
        }

    // MARK: - Logging

    private fun log(message: String) =
        diagnostics.log(DiagnosticCategory.SURFACE, DiagnosticLevel.INFO, message)

    private fun logATC(message: String) =
        diagnostics.log(DiagnosticCategory.ATC, DiagnosticLevel.INFO, message)
}
