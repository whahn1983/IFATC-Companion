package com.h3consultingpartners.ifatccompanion.core.connect

import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Everything the Connect link publishes, as one immutable snapshot.
 *
 * iOS spreads these across five `@Published` properties on an `ObservableObject`; a
 * single [StateFlow] of this type is the Android equivalent, so a consumer recomposes
 * once per change rather than once per property.
 */
data class IFConnectState(
    val connectionState: IFConnectConnectionState = IFConnectConnectionState.Disconnected,
    val manifestEntries: List<IFManifestEntry> = emptyList(),
    val liveATC: LiveATCStatus = LiveATCStatus.none,
    val lastError: String? = null,
    /** Last raw flight-plan payload key read from Infinite Flight (for diagnostics). */
    val liveFlightPlanRaw: String? = null,
)

/**
 * Orchestrates the Infinite Flight Connect link: connection lifecycle, manifest
 * discovery, state polling, and command sending. Fully isolated — if Infinite
 * Flight is unavailable, every path degrades gracefully and never crashes.
 *
 * Ported from `IFATCCompanion/Connect/IFConnectManager.swift`, a `@MainActor
 * ObservableObject`. The Kotlin equivalent takes the [scope] it runs on: pass a
 * single-threaded scope (`Dispatchers.Main.immediate` in the app, a `TestScope` under
 * test) so the token/task bookkeeping keeps the same "one mutator" guarantee the main
 * actor gives on iOS.
 */
class IFConnectManager(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    val mappingStore: IFStateMappingStore = IFStateMappingStore(),
    private val client: IFConnectClient = IFConnectClient(clock = clock),
    private val discovery: IFDeviceDiscovering = IFDiscoveryService(scope),
    private val manifestService: IFConnectManifestService = IFConnectManifestService(),
) {

    private val _state = MutableStateFlow(IFConnectState())
    val state: StateFlow<IFConnectState> = _state.asStateFlow()

    private val reader = IFConnectStateReader(mappingStore, clock)

    private var pollTask: Job? = null
    private var discoveryTimeoutTask: Job? = null

    /** The in-flight connect attempt, and the watchdog that gives up on its address. */
    private var connectTask: Job? = null
    private var rediscoverWatchdog: Job? = null

    /**
     * Bumped for every connect attempt so a superseded one — one the watchdog has
     * already given up on — can't write state or start a second search behind it.
     * Wraps on overflow, exactly like the Swift's `&+=`.
     */
    private var connectToken = 0

    /** Last angular conventions written to Diagnostics, so only the changes are logged. */
    private data class AngleUnitsSnapshot(
        val aircraftInDegrees: Boolean,
        val windInDegrees: Boolean,
    )

    private var lastLoggedAngleUnits: AngleUnitsSnapshot? = null

    /** Pushed live aircraft states (the app model subscribes). */
    var onState: ((AircraftState) -> Unit)? = null

    /** Pushed parsed flight plan whenever the live plan changes (the app model subscribes). */
    var onFlightPlan: ((FlightPlan) -> Unit)? = null

    /**
     * How long to wait for an Infinite Flight discovery broadcast before giving up
     * and pointing the user at manual IP entry. Seconds.
     */
    var discoveryTimeout: Double = 25.0

    /** Seconds between poll ticks (≈1 Hz). */
    var pollInterval: Double = 1.0

    /**
     * How often (in poll ticks) to re-read the flight-plan string. The plan rarely
     * changes mid-flight, so this is throttled relative to state polling.
     */
    var flightPlanReadEveryTicks: Int = 15

    /**
     * How many times [connect] attempts the TCP-connect + manifest-discovery
     * handshake before surfacing a failure. Returning from the background often
     * makes Infinite Flight answer the first manifest request with a partial or
     * garbled frame (which decodes to "Failed to decode a response"), so a single
     * attempt would spuriously fail even though a retry a moment later succeeds.
     */
    var connectMaxAttempts: Int = 4

    /** Base delay between connect attempts, in seconds; backs off linearly per attempt. */
    var connectRetryDelay: Double = 0.6

    /**
     * How long to wait after the TCP socket opens before requesting the manifest.
     * A short settle (300–500 ms) avoids the partial/garbled first frame Infinite
     * Flight sometimes serves the instant the connection is ready.
     */
    var manifestSettleDelay: Double = 0.4

    /**
     * Hard deadline (seconds) on *reaching* a configured address before the search for
     * Infinite Flight's current address takes over, when the caller allowed rediscovery.
     * The socket's own timeout normally trips first; this bounds the stalls it can't see —
     * a connection left sitting behind a route that no longer exists, for one. Only the
     * reaching part is bounded: once Infinite Flight has answered and the manifest is
     * coming in, the address is proven right and is given as long as it needs.
     */
    var rediscoverAfter: Double = 10.0

    // region Connection

    /**
     * Bring the link up against [host].
     *
     * When [rediscoverOnFailure] is set and nothing answers at that address, the
     * stored address is treated as stale rather than authoritative: auto-discovery
     * runs again and the link is retried against whatever the search finds, with the
     * new endpoint handed back through [onRediscovered] so the caller can persist it.
     * This is what makes a saved address survive a change of network — the iPad's IP
     * moves with the Wi-Fi it's on, and the previously discovered one simply stops
     * existing.
     */
    fun connect(
        host: String,
        port: Int,
        rediscoverOnFailure: Boolean = false,
        onRediscovered: ((IFDiscoveryService.Device) -> Unit)? = null,
    ) {
        if (_state.value.connectionState.isActive) return
        _state.update {
            it.copy(connectionState = IFConnectConnectionState.Connecting, lastError = null)
        }
        diagnostics.log(DiagnosticCategory.CONNECTION, message = "Connecting to $host:$port…")

        connectToken += 1
        val token = connectToken
        val attempts = maxOf(1, connectMaxAttempts)

        val work = scope.launch {
            var lastFailure: Throwable? = null
            for (attempt in 1..attempts) {
                try {
                    performConnect(host, port, attempt)
                    return@launch
                } catch (cancellation: CancellationException) {
                    return@launch
                } catch (error: Throwable) {
                    // An intentional disconnect cancelled the socket mid-handshake —
                    // don't retry, or we'd reconnect against the user's wishes.
                    if (error is IFConnectError.Cancelled) return@launch
                    lastFailure = error
                    // `InvalidHost` won't fix itself on a retry — give up immediately.
                    if (error is IFConnectError.InvalidHost) break
                    // Nothing answered at this address at all. Retrying a dead address
                    // only delays the search for the live one, so stop early and let the
                    // rediscovery fallback below take over.
                    if (rediscoverOnFailure && isUnreachable(error)) break
                    val message = errorMessage(error)
                    if (attempt < attempts) {
                        diagnostics.log(
                            DiagnosticCategory.CONNECTION,
                            message = "Connect attempt $attempt failed ($message). Retrying…",
                        )
                        // Drop the half-open socket so the next attempt starts clean,
                        // then back off briefly to let Infinite Flight settle.
                        client.disconnect()
                        delay(seconds(connectRetryDelay * attempt))
                    }
                }
            }
            // The watchdog may already have given up on this address and started the
            // search. This attempt is then history: it must not report a failure over
            // the search, nor start a second one.
            if (connectToken != token) return@launch
            val failure = lastFailure
            val message = failure?.let { errorMessage(it) } ?: "Connection failed."
            val detail = failure?.let { errorDetail(it) }

            // Nothing is listening where we were told to look. If the caller allows it,
            // go and find Infinite Flight's current address instead of failing on an
            // address that may simply belong to an old network. A manifest failure is
            // *not* this case — there, Infinite Flight answered, so the address is right
            // and searching for another would only find the same device again.
            if (rediscoverOnFailure && failure != null && isUnreachable(failure)) {
                client.disconnect()
                diagnostics.log(
                    DiagnosticCategory.CONNECTION,
                    message = "No Infinite Flight at $host:$port ($message). " +
                        "Searching the local network for its current address…",
                )
                rediscover(host, onRediscovered)
                return@launch
            }

            _state.update {
                it.copy(
                    connectionState = IFConnectConnectionState.Failed(message, detail),
                    lastError = detail ?: message,
                )
            }
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Connect failed after $attempts attempt(s): ${detail ?: message}",
            )
        }
        connectTask = work

        if (!rediscoverOnFailure) return
        // Bound the *reaching* stage. The socket's own six-second timeout normally trips
        // first and the loop above falls back on its own; this catches the attempt that
        // simply never comes back — a connection parked behind a route to a network this
        // device has left — so a stale address can never hold the app at "Connecting…"
        // indefinitely.
        rediscoverWatchdog?.cancel()
        val deadline = maxOf(1.0, rediscoverAfter)
        rediscoverWatchdog = scope.launch {
            delay(seconds(deadline))
            if (connectToken != token) return@launch
            // Only still-reaching counts. `ReceivingManifest` means Infinite Flight
            // answered — the address is right and a slow handshake deserves its time —
            // and any other state means this attempt has already resolved.
            if (_state.value.connectionState != IFConnectConnectionState.Connecting) {
                return@launch
            }
            // Retire this attempt so its own tail can't also report or re-search.
            connectToken += 1
            work.cancel()
            client.disconnect()
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Still nothing from $host:$port after ${deadline.toInt()}s. " +
                    "Searching the local network for Infinite Flight's current address…",
            )
            rediscover(host, onRediscovered)
        }
    }

    /**
     * Re-run auto-discovery after [previousHost] stopped answering, then connect to
     * whatever is found. The discovered endpoint is reported through [onRediscovered]
     * first so the caller can overwrite the address it had stored. The retry itself
     * does not fall back again — one search per connect attempt, so a network with no
     * Infinite Flight on it surfaces the normal "not found" failure instead of looping.
     */
    private fun rediscover(
        previousHost: String,
        onRediscovered: ((IFDiscoveryService.Device) -> Unit)?,
    ) {
        // Clear `Connecting` so `startAutoDiscover`'s own connect isn't short-circuited
        // by the `isActive` guard at the top of `connect`.
        _state.update { it.copy(connectionState = IFConnectConnectionState.Disconnected) }
        startAutoDiscover { device ->
            if (device.address != previousHost) {
                diagnostics.log(
                    DiagnosticCategory.CONNECTION,
                    message = "Infinite Flight is now at ${device.address}:${device.port} — " +
                        "replacing the stored address $previousHost.",
                )
            }
            onRediscovered?.invoke(device)
            connect(device.address, device.port)
        }
    }

    /**
     * One attempt of the connect + manifest-discovery handshake. Throws on any
     * failure so the caller can retry; only reaches `Connected` and starts polling
     * once the manifest has been read successfully.
     */
    private suspend fun performConnect(host: String, port: Int, attempt: Int) {
        client.connect(host, port)
        diagnostics.log(
            DiagnosticCategory.CONNECTION,
            message = if (attempt > 1) "TCP connected (attempt $attempt)." else "TCP connected.",
        )
        // Give Infinite Flight a beat after the socket opens before asking for the
        // manifest — requesting the instant it's ready often returns a partial or
        // garbled first frame (especially right after returning from the background).
        delay(seconds(manifestSettleDelay))
        _state.update { it.copy(connectionState = IFConnectConnectionState.ReceivingManifest) }
        diagnostics.log(DiagnosticCategory.MANIFEST, message = "Requesting manifest…")
        // One hop per event, mirroring the Swift's `Task { @MainActor in … }`: events are
        // ordered among themselves but not synchronised with the read that produced them.
        val onEvent: (IFConnectManifestEvent) -> Unit = { event ->
            scope.launch { handleManifestEvent(event) }
        }
        val entries = try {
            manifestService.discover(client, mappingStore, onEvent)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error is IFConnectError.Cancelled) throw error
            // The specific cause (timeout, decode/UTF-8 failure, closed early, …) is
            // already in Diagnostics via `onEvent`. Collapse it to the single
            // user-facing "Manifest Unavailable" — which the connect loop only lets
            // reach the UI once its reconnect-and-retry attempts are exhausted.
            throw IFConnectError.ManifestUnavailable
        }
        _state.update { it.copy(manifestEntries = entries) }
        diagnostics.log(
            DiagnosticCategory.MANIFEST,
            message = "Manifest discovered: ${entries.size} entries. " +
                "Resolved ${mappingStore.resolved.size} logical states.",
        )
        if (mappingStore.unresolvedKeys.isNotEmpty()) {
            val names = mappingStore.unresolvedKeys.joinToString(", ") { it.rawValue }
            diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Unresolved (use manual override if needed): $names",
            )
        }
        logATCRelatedStates(entries)
        logAngleStates()
        // A fresh manifest re-decides the angular conventions, so log them afresh too.
        lastLoggedAngleUnits = null
        _state.update { it.copy(connectionState = IFConnectConnectionState.Connected) }
        readFlightPlan()
        startPolling()
    }

    /**
     * Map a granular manifest event to the Diagnostics log and, while the handshake
     * is still in progress, to the "Receiving manifest…" status. Replaces the old
     * opaque "Manifest Unavailable" line with a step-by-step trace so a stuck read
     * can be diagnosed from the log alone.
     */
    private fun handleManifestEvent(event: IFConnectManifestEvent) {
        when (event) {
            is IFConnectManifestEvent.RequestSent -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = if (event.attempt > 1) {
                    "Manifest request sent (retry ${event.attempt})."
                } else {
                    "Manifest request sent."
                },
            )

            is IFConnectManifestEvent.HeaderReceived -> {
                markReceivingManifest()
                diagnostics.log(
                    DiagnosticCategory.MANIFEST,
                    message = "Header bytes received (id ${event.id}). " +
                        "Expected payload size: ${event.payloadLength} bytes.",
                )
            }

            is IFConnectManifestEvent.Progress -> {
                markReceivingManifest()
                diagnostics.log(
                    DiagnosticCategory.MANIFEST,
                    message = "Receiving manifest: ${event.received}/${event.expected} bytes.",
                )
            }

            is IFConnectManifestEvent.WaitingForHeader -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Partial manifest — waiting for more data " +
                    "(${event.received} header byte(s) so far).",
            )

            is IFConnectManifestEvent.InvalidResponseId -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Invalid response ID: ${event.id} " +
                    "(expected ${IFConnectClient.MANIFEST_COMMAND_ID}).",
            )

            is IFConnectManifestEvent.InvalidPayloadLength -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Invalid payload length: ${event.length}.",
            )

            is IFConnectManifestEvent.InvalidStringLength -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Invalid string length: ${event.length}.",
            )

            IFConnectManifestEvent.Utf8DecodeFailed -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "UTF-8 decode failure while reading the manifest.",
            )

            IFConnectManifestEvent.ConnectionClosedEarly -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Connection closed before the full manifest arrived.",
            )

            is IFConnectManifestEvent.Parsed -> diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "Manifest parsed successfully: ${event.stateCount} states.",
            )
        }
    }

    /**
     * Move to `ReceivingManifest` only while still handshaking, so a late progress
     * event (e.g. from an abandoned read) can't downgrade an already-connected or
     * already-failed link.
     */
    private fun markReceivingManifest() {
        _state.update {
            when (it.connectionState) {
                IFConnectConnectionState.Connecting,
                IFConnectConnectionState.ReceivingManifest,
                -> it.copy(connectionState = IFConnectConnectionState.ReceivingManifest)

                else -> it
            }
        }
    }

    private fun errorMessage(error: Throwable): String =
        (error as? IFConnectError)?.errorDescription ?: error.message ?: error.toString()

    /**
     * The fuller error text — short summary plus any recovery instructions — for UI with
     * room to show it. Null when the error has no extra detail beyond [errorMessage].
     */
    private fun errorDetail(error: Throwable): String? {
        val suggestion = (error as? IFConnectError)?.recoverySuggestion ?: return null
        return "${errorMessage(error)}. $suggestion"
    }

    fun disconnect() {
        // Retire any in-flight connect attempt: a deliberate disconnect must not be
        // followed a moment later by that attempt's failure — or by the search its
        // watchdog would have started.
        connectToken += 1
        rediscoverWatchdog?.cancel()
        rediscoverWatchdog = null
        connectTask?.cancel()
        connectTask = null
        pollTask?.cancel()
        pollTask = null
        // Swift hops to the client actor here; the Kotlin client's teardown is
        // synchronous, so the socket is already closed under any in-flight read by the
        // time the poll task's cancellation check runs.
        client.disconnect()
        _state.update {
            it.copy(
                connectionState = IFConnectConnectionState.Disconnected,
                // Forget the last plan read with the link that read it. `readFlightPlan`
                // emits only on change, so a cache outliving its socket makes the next
                // connection's handshake read silently do nothing — and a reconnect is
                // exactly when the app most needs the sim to re-state the plan (the pilot
                // may have swapped flights).
                liveFlightPlanRaw = null,
            )
        }
        diagnostics.log(DiagnosticCategory.CONNECTION, message = "Disconnected.")
    }

    // endregion

    // region Polling

    private fun startPolling() {
        pollTask?.cancel()
        pollTask = scope.launch {
            var tick = 0
            while (isActive) {
                if (!client.isConnected) {
                    if (_state.value.connectionState.isConnected) {
                        _state.update {
                            it.copy(
                                connectionState =
                                    IFConnectConnectionState.Failed("Connection lost"),
                            )
                        }
                        diagnostics.log(DiagnosticCategory.CONNECTION, message = "Connection lost.")
                    }
                    break
                }
                val snapshot = reader.readState(client)
                // Reading a snapshot is a long chain of individual socket round-trips, and
                // cancelling this task doesn't interrupt one that is already part-way
                // through — `disconnect()` tears the link down under it, so the reads that
                // hadn't run yet simply fail. What comes back is a half-read snapshot: the
                // fields read before the tear-down, null for the rest. Publishing that hands
                // the app a position and an altitude with no on-ground flag, which is
                // exactly the shape that used to read as "airborne". A cancelled poll has
                // nothing true left to say, so drop it. (This fires on every forced
                // reconnect — the one the app performs on returning from the background.)
                if (!isActive) break
                onState?.invoke(snapshot)
                logTelemetryHealth(snapshot)
                val atc = reader.readATCStatus(client)
                _state.update { it.copy(liveATC = atc) }
                tick += 1
                if (tick % flightPlanReadEveryTicks == 0) readFlightPlan()
                delay(seconds(pollInterval))
            }
        }
    }

    /**
     * Log the two things that decide whether every heading in the app is right, and that
     * were previously invisible: which angular convention the connection has been read as,
     * and whether the link has desynchronised. A heading pinned to north on the maps is one
     * of these two — a radians build read as degrees shows every heading as 0–6° — and
     * neither left a trace in a diagnostics export before.
     */
    private fun logTelemetryHealth(state: AircraftState) {
        val units = AngleUnitsSnapshot(
            aircraftInDegrees = mappingStore.anglesProvedDegrees,
            windInDegrees = mappingStore.windAnglesProvedDegrees,
        )
        if (units != lastLoggedAngleUnits) {
            lastLoggedAngleUnits = units
            val heading = state.heading?.let { String.format(Locale.US, "%.0f°", it) } ?: "—"
            // The raw readings are the whole argument: 084° magnetic arrives as 1.466 on a
            // build reporting radians, and a wind on 331 sitting beside it is the weather
            // reporting degrees — two conventions on one connection, which is what these two
            // decisions exist to keep apart.
            val raw = mappingStore.lastRawAngles.joinToString(", ") {
                "${it.name} ${String.format(Locale.US, "%.3f", it.value)}"
            }
            diagnostics.log(
                DiagnosticCategory.STATE,
                message = "Angle units — aircraft: " +
                    (if (units.aircraftInDegrees) "DEGREES" else "RADIANS") +
                    ", wind: " + (if (units.windInDegrees) "DEGREES" else "RADIANS") +
                    ". Heading now $heading. Raw: ${raw.ifEmpty { "—" }}.",
            )
        }
        val mismatched = client.takeMismatchedFrameCount()
        if (mismatched > 0) {
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Discarded $mismatched response frame(s) answering a state we " +
                    "didn't ask for — link resynchronised.",
            )
        }
    }

    /**
     * Force an immediate re-read of the flight plan, bypassing the change guard, so
     * the pilot can pull in an edit they made mid-flight without waiting for the
     * next throttled poll. No-op when not connected.
     */
    suspend fun refreshFlightPlan() {
        if (!_state.value.connectionState.isConnected) return
        _state.update { it.copy(liveFlightPlanRaw = null) }
        readFlightPlan()
    }

    /** Read and parse the flight plan; emit [onFlightPlan] only when it changes. */
    private suspend fun readFlightPlan() {
        val payloads = reader.readFlightPlanPayloads(client)
        if (payloads.isEmpty) return

        // Change-detection key spans every payload so an edit to any of them re-reads.
        val key = listOfNotNull(payloads.fullInfo, payloads.full, payloads.route, payloads.coordinates)
            .joinToString(PAYLOAD_KEY_SEPARATOR)
        if (key == _state.value.liveFlightPlanRaw) return
        _state.update { it.copy(liveFlightPlanRaw = key) }

        // Log the full raw payloads (verbose) so the exact IF format is visible — the
        // shape of these states varies across IF versions, and the parser is built
        // against whatever is observed here.
        logRawFlightPlan(payloads)

        val plan = IFFlightPlanParser.parse(
            fullInfo = payloads.fullInfo,
            full = payloads.full,
            route = payloads.route,
            coordinates = payloads.coordinates,
        )
        if (plan == null) {
            diagnostics.log(DiagnosticCategory.STATE, message = "Flight plan present but unparseable.")
            return
        }
        val located = plan.waypoints.count { it.coordinate != null }
        val withAltitude = plan.waypoints.count { (it.altitude ?: 0.0) > 0 }
        diagnostics.log(
            DiagnosticCategory.STATE,
            message = "Flight plan from IF: ${plan.departure}→${plan.destination}, " +
                "${plan.waypoints.size} fixes ($located located, $withAltitude with alt), " +
                "cruise ${if (plan.cruiseAltitude > 0) "${plan.cruiseAltitude} ft" else "—"}, " +
                "SID ${plan.sid.ifEmpty { "—" }}, STAR ${plan.star.ifEmpty { "—" }}, " +
                "APP ${plan.approach.ifEmpty { "—" }}.",
        )
        // The parsed fix list itself, not just its size: when a report says fixes are
        // missing from the route, this is what separates "the parser dropped them" from
        // "the parser has them and something downstream doesn't". A fix with no
        // coordinate is marked, since an unlocated fix draws nothing on the map.
        if (plan.waypoints.isNotEmpty()) {
            val names = plan.waypoints.joinToString("→") {
                if (it.coordinate == null) "${it.name}(no-pos)" else it.name
            }
            diagnostics.log(DiagnosticCategory.STATE, message = "Flight plan fixes: $names")
        }
        onFlightPlan?.invoke(plan)
    }

    /**
     * Emit the raw flight-plan payloads to diagnostics (truncated) so the actual IF
     * wire format can be inspected when the parsed result looks wrong.
     */
    private fun logRawFlightPlan(payloads: IFConnectStateReader.FlightPlanPayloads) {
        // Keep the *tail* as well as the head: the detailed document runs to ~8 KB on a
        // full route, and the STAR/approach groups — the part in question whenever fixes
        // are reported missing — sit at the very end, exactly what a head-only cut drops.
        fun trimmed(s: String): String {
            if (s.length <= MAX_RAW_PLAN_CHARS) return s
            val half = MAX_RAW_PLAN_CHARS / 2
            return s.take(half) + "…[${s.length} chars, middle elided]…" + s.takeLast(half)
        }
        payloads.fullInfo?.let {
            diagnostics.log(DiagnosticCategory.STATE, message = "Raw flightplan/full_info: ${trimmed(it)}")
        }
        payloads.full?.let {
            diagnostics.log(DiagnosticCategory.STATE, message = "Raw flightplan: ${trimmed(it)}")
        }
        payloads.route?.let {
            diagnostics.log(DiagnosticCategory.STATE, message = "Raw flightplan/route: ${trimmed(it)}")
        }
        payloads.coordinates?.let {
            diagnostics.log(DiagnosticCategory.STATE, message = "Raw flightplan/coordinates: ${trimmed(it)}")
        }
    }

    /**
     * Log every manifest state whose path looks ATC/COM/multiplayer-related, plus which
     * logical staffing keys they resolved to. The set of states Infinite Flight exposes
     * for ATC only appears when connected to a session with a controller and varies by
     * version, so surfacing the exact paths here is how the tuned-frequency and
     * staffing signatures are verified and refined against a real session.
     */
    private fun logATCRelatedStates(entries: List<IFManifestEntry>) {
        val related = entries.filter { entry ->
            val key = entry.matchKey
            ATC_NEEDLES.any { key.contains(it) }
        }
        if (related.isEmpty()) {
            diagnostics.log(
                DiagnosticCategory.MANIFEST,
                message = "No ATC/COM-related states found in manifest.",
            )
            return
        }
        val list = related.joinToString(", ") { "${it.name} [${it.type.shortName}]" }
        diagnostics.log(
            DiagnosticCategory.MANIFEST,
            message = "ATC/COM-related states (${related.size}): $list",
        )
        for (key in RESOLVED_ATC_KEYS) {
            // Note the two leading spaces, and that an unresolved key emits nothing here.
            mappingStore.entry(key)?.let {
                diagnostics.log(
                    DiagnosticCategory.MANIFEST,
                    message = "  ${key.rawValue} → ${it.name}",
                )
            }
        }
    }

    /**
     * Log which manifest state each angle was resolved onto, with its declared type. These
     * are the readings the aircraft symbol, the departure vector and the wind are all built
     * from, and a signature landing on the wrong entry — a bool named `…_track`, a command
     * sharing a word with a state — is otherwise indistinguishable from the sim reporting
     * something odd.
     */
    private fun logAngleStates() {
        val described = ANGLE_KEYS.map { key ->
            val entry = mappingStore.entry(key)
                ?: return@map "${key.rawValue} → —"
            "${key.rawValue} → ${entry.name} [${entry.type.shortName}]"
        }
        diagnostics.log(
            DiagnosticCategory.MANIFEST,
            message = "Angle states: ${described.joinToString(", ")}",
        )
    }

    // endregion

    // region Discovery

    fun startAutoDiscover(onFound: (IFDiscoveryService.Device) -> Unit) {
        _state.update { it.copy(connectionState = IFConnectConnectionState.Discovering) }
        diagnostics.log(
            DiagnosticCategory.CONNECTION,
            message = "Searching for Infinite Flight on the local network…",
        )
        discovery.start { device ->
            discoveryTimeoutTask?.cancel()
            discoveryTimeoutTask = null
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Discovered ${device.name} at ${device.address}:${device.port}",
            )
            discovery.stop()
            // Clear the active `Discovering` state so the connect call made from
            // `onFound` isn't short-circuited by its `isActive` guard — otherwise the
            // search would appear to keep running and never connect until the user
            // manually reconnected.
            _state.update { it.copy(connectionState = IFConnectConnectionState.Disconnected) }
            onFound(device)
        }
        discoveryTimeoutTask?.cancel()
        discoveryTimeoutTask = scope.launch {
            delay(seconds(discoveryTimeout))
            if (_state.value.connectionState != IFConnectConnectionState.Discovering) {
                return@launch
            }
            discovery.stop()
            val message = "No Infinite Flight found on the network."
            val detail = "No Infinite Flight found on the network. Check that Infinite Flight " +
                "is running with the Connect API enabled and that both devices are on the " +
                "same Wi-Fi, or enter the iPad's IP manually in Settings."
            _state.update {
                it.copy(
                    connectionState = IFConnectConnectionState.Failed(message, detail),
                    lastError = detail,
                )
            }
            diagnostics.log(
                DiagnosticCategory.CONNECTION,
                message = "Auto-discovery timed out after ${discoveryTimeout.toInt()}s.",
            )
        }
    }

    fun stopAutoDiscover() {
        discoveryTimeoutTask?.cancel()
        discoveryTimeoutTask = null
        discovery.stop()
        if (_state.value.connectionState == IFConnectConnectionState.Discovering) {
            _state.update { it.copy(connectionState = IFConnectConnectionState.Disconnected) }
        }
    }

    // endregion

    private fun seconds(value: Double): Long = (value * 1000).toLong()

    companion object {
        /**
         * Whether a connect failure means *nothing answered* at the address — as opposed
         * to Infinite Flight answering badly (a partial or garbled manifest), which a
         * retry against the same address fixes. Any foreign error is not this case.
         */
        fun isUnreachable(error: Throwable): Boolean = when (error) {
            IFConnectError.InvalidHost,
            IFConnectError.Timeout,
            IFConnectError.NotConnected,
            -> true

            is IFConnectError.ConnectionFailed -> true

            IFConnectError.ManifestUnavailable,
            IFConnectError.UnknownState,
            IFConnectError.DecodingFailed,
            IFConnectError.Cancelled,
            -> false

            else -> false
        }

        /** Max characters of a raw flight-plan payload written to Diagnostics. */
        const val MAX_RAW_PLAN_CHARS = 4000

        /**
         * UNIT SEPARATOR (0x1F) joins the four flight-plan payloads into the
         * change-detection key: it cannot occur inside any of them.
         */
        val PAYLOAD_KEY_SEPARATOR: String = Char(0x1F).toString()

        private val ATC_NEEDLES = listOf(
            "atc", "controller", "unicom", "comm", "com1", "com2",
            "frequency", "facilit", "online", "server", "multiplayer",
        )

        private val RESOLVED_ATC_KEYS = listOf(
            IFStateMappingStore.Logical.ATC_ACTIVE,
            IFStateMappingStore.Logical.ATC_FACILITY_NAME,
            IFStateMappingStore.Logical.ATC_FACILITY_COUNT,
            IFStateMappingStore.Logical.IS_ONLINE,
            IFStateMappingStore.Logical.SERVER_NAME,
            IFStateMappingStore.Logical.TUNED_COM_NAME,
            IFStateMappingStore.Logical.TUNED_COM_FREQUENCY,
        )

        private val ANGLE_KEYS = listOf(
            IFStateMappingStore.Logical.HEADING,
            IFStateMappingStore.Logical.TRUE_HEADING,
            IFStateMappingStore.Logical.TRACK,
            IFStateMappingStore.Logical.WIND_DIRECTION_TRUE,
            IFStateMappingStore.Logical.BANK_ANGLE,
            IFStateMappingStore.Logical.PITCH,
        )
    }
}
