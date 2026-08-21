package com.h3consultingpartners.ifatccompanion.core.surface

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.surface.routing.SurfaceGraph
import com.h3consultingpartners.ifatccompanion.core.surface.routing.SurfaceGraphBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * What the app knows about the airport surface right now: the loaded extract, the routing
 * graph built from it, and why it isn't loaded when it isn't.
 */
data class SurfaceSessionState(
    val departure: AirportSurfaceModel? = null,
    val arrival: AirportSurfaceModel? = null,
    val departureGraph: SurfaceGraph? = null,
    val arrivalGraph: SurfaceGraph? = null,
    val cachedAirports: List<String> = emptyList(),
    val cacheBytes: Int = 0,
    val lastError: String? = null,
    val loading: Boolean = false,
) {
    /** A one-line summary for the Settings row, or null before anything has been cached. */
    val cacheSummary: String?
        get() {
            if (cachedAirports.isEmpty()) return null
            val kilobytes = (cacheBytes + 512) / 1024
            return "${cachedAirports.size} airport${if (cachedAirports.size == 1) "" else "s"} · $kilobytes KB"
        }
}

/**
 * Owns the OpenStreetMap airport-surface extracts for the current flight: fetching them,
 * building the routing graph, and reporting what is loaded.
 *
 * This is the surface half of what iOS calls `AirportSurfaceCoordinator`. It lives in
 * `:core` rather than in the Android module for the usual reason — every decision here
 * (which field to load, when to refuse, what the diagnostics say) is testable logic, and
 * none of it needs Android. The app only supplies the HTTP and file ports.
 *
 * **Attribution.** Everything this loads is OpenStreetMap data under the ODbL. The
 * "Surface data © OpenStreetMap contributors" credit shown in Settings and on the taxi map
 * is not decoration — it is the licence condition, and it is carried in [OSMSurface] so it
 * cannot drift from the data it describes.
 */
class SurfaceSessionController(
    private val provider: AirportSurfaceProvider,
    private val airports: AirportDatabase = AirportDatabase,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    /**
     * Where the heavy work runs. Loading a surface parses a multi-megabyte Overpass
     * document, normalizes it, and builds a routing graph with O(edges x segments)
     * geometry loops — hundreds of milliseconds to seconds at a large field, and none of
     * it suspends. On Android the caller was the ViewModel scope, which is the main
     * thread, so a refresh at KLAX or EGLL blocked the UI long enough to fire the ANR
     * watchdog.
     *
     * Confining it here rather than at the call site means a future caller cannot
     * reintroduce that. Defaulted to EmptyCoroutineContext so the engine stays
     * dispatcher-agnostic and the tests keep running on their own test dispatcher; the
     * Android graph passes Dispatchers.Default. Default rather than IO because the cost
     * is CPU, not blocking I/O — IO's much larger pool would let several multi-megabyte
     * parses run at once.
     */
    private val workContext: CoroutineContext = EmptyCoroutineContext,
) {

    private val _state = MutableStateFlow(SurfaceSessionState())
    val state: StateFlow<SurfaceSessionState> = _state.asStateFlow()

    /**
     * Load (or refresh) the surface for both ends of the flight.
     *
     * A field with no OpenStreetMap coverage, or an Overpass outage, is not an error the
     * pilot needs to act on: the taxi map simply doesn't draw and the controller falls back
     * to generic taxi phrasing. So a failure is recorded and reported, never thrown.
     */
    suspend fun refresh(plan: FlightPlan, forceRefresh: Boolean = false) = withContext(workContext) {
        _state.update { it.copy(loading = true, lastError = null) }
        val departure = load(plan.departure, forceRefresh)
        val arrival = load(plan.destination, forceRefresh)
        val info = provider.cacheInfo()
        _state.update {
            it.copy(
                departure = departure ?: it.departure,
                arrival = arrival ?: it.arrival,
                departureGraph = departure?.let(::buildGraph) ?: it.departureGraph,
                arrivalGraph = arrival?.let(::buildGraph) ?: it.arrivalGraph,
                cachedAirports = info.icaos,
                cacheBytes = info.bytes,
                loading = false,
            )
        }
    }

    private suspend fun load(icao: String, forceRefresh: Boolean): AirportSurfaceModel? {
        val id = icao.trim().uppercase()
        if (id.length < 3) return null
        val reference = airports.coordinate(id) ?: run {
            diagnostics.log(
                DiagnosticCategory.SURFACE,
                message = "No reference position for $id — surface not requested",
            )
            return null
        }
        return try {
            provider.surface(id, reference, forceRefresh)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = error.message ?: error.toString()
            diagnostics.log(DiagnosticCategory.SURFACE, message = "Surface load failed for $id: $message")
            _state.update { it.copy(lastError = message) }
            null
        }
    }

    private fun buildGraph(surface: AirportSurfaceModel): SurfaceGraph? =
        runCatching { SurfaceGraphBuilder.build(surface) }.getOrNull()

    /** The surface for the end of the flight the aircraft is at, or null before it loads. */
    fun surfaceFor(arriving: Boolean): AirportSurfaceModel? =
        if (arriving) _state.value.arrival else _state.value.departure

    fun graphFor(arriving: Boolean): SurfaceGraph? =
        if (arriving) _state.value.arrivalGraph else _state.value.departureGraph

    /** Where the assigned stand is, once the extract has resolved it. */
    fun standPosition(arriving: Boolean, gate: String): Coordinate? {
        if (gate.isBlank()) return null
        val surface = surfaceFor(arriving) ?: return null
        return surface.parkingPositions
            .firstOrNull { stand -> stand.matches(gate) }
            ?.coordinate
            ?.let { Coordinate(it.latitude, it.longitude) }
    }

    suspend fun clearCache() = withContext(workContext) {
        provider.clearCache()
        val info = provider.cacheInfo()
        _state.update {
            it.copy(
                departure = null,
                arrival = null,
                departureGraph = null,
                arrivalGraph = null,
                cachedAirports = info.icaos,
                cacheBytes = info.bytes,
            )
        }
        diagnostics.log(DiagnosticCategory.SURFACE, message = "Airport surface cache cleared")
    }

    /** Rows for the Diagnostics panel: what loaded, how good it is, and what went wrong. */
    fun diagnosticRows(): List<Pair<String, String>> {
        val current = _state.value
        fun describe(label: String, surface: AirportSurfaceModel?, graph: SurfaceGraph?): List<Pair<String, String>> {
            if (surface == null) return listOf(label to "not loaded")
            return listOf(
                label to surface.icao,
                "$label confidence" to surface.confidence.title,
                "$label taxiways" to surface.taxiways.size.toString(),
                "$label runways" to surface.runways.size.toString(),
                "$label stands" to surface.standCount.toString(),
                "$label graph" to (graph?.let { "${it.nodes.size} nodes / ${it.edges.size} edges" } ?: "not built"),
            )
        }
        return buildList {
            addAll(describe("Departure", current.departure, current.departureGraph))
            addAll(describe("Arrival", current.arrival, current.arrivalGraph))
            add("Cache" to (current.cacheSummary ?: "empty"))
            add("Surface data" to OSMSurface.ATTRIBUTION_TEXT)
            add("Licence" to OSMSurface.LICENSE_NAME)
        }
    }

    fun lastError(): String? = _state.value.lastError

    /** The shareable diagnostics text, with the ODbL attribution on it as the licence requires. */
    fun exportText(): String = buildString {
        appendLine("IFATC Companion — airport surface diagnostics")
        appendLine(OSMSurface.ATTRIBUTION_TEXT)
        appendLine(OSMSurface.LICENSE_NAME)
        appendLine()
        for ((label, value) in diagnosticRows()) appendLine("$label: $value")
    }
}
