package com.h3consultingpartners.ifatccompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.h3consultingpartners.ifatccompanion.audio.AndroidChatterRadio
import com.h3consultingpartners.ifatccompanion.audio.AndroidSpeechService
import com.h3consultingpartners.ifatccompanion.audio.PushToTalkRecognizer
import com.h3consultingpartners.ifatccompanion.audio.RadioAudioEngine
import com.h3consultingpartners.ifatccompanion.billing.PlayBillingRepository
import com.h3consultingpartners.ifatccompanion.core.atis.ATISService
import com.h3consultingpartners.ifatccompanion.core.chatter.AmbientChatterService
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectManager
import com.h3consultingpartners.ifatccompanion.core.diagnostics.DiagnosticsStore
import com.h3consultingpartners.ifatccompanion.core.map.BaseImageryService
import com.h3consultingpartners.ifatccompanion.core.map.CoastlineData
import com.h3consultingpartners.ifatccompanion.core.mock.MockSimulatorFeed
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.net.AppHttp
import com.h3consultingpartners.ifatccompanion.core.net.HttpFetching
import com.h3consultingpartners.ifatccompanion.core.net.OkHttpFetcher
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlightStore
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlightsController
import com.h3consultingpartners.ifatccompanion.core.persistence.SessionStateStore
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyMode
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProfileStore
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import com.h3consultingpartners.ifatccompanion.core.review.ReviewRequestManager
import com.h3consultingpartners.ifatccompanion.core.session.FlightSessionCoordinator
import com.h3consultingpartners.ifatccompanion.core.session.FlightSourceController
import com.h3consultingpartners.ifatccompanion.core.session.GroundHandoffSignals
import com.h3consultingpartners.ifatccompanion.core.session.TaxiClearanceContext
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.settings.SettingsRepository
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceCache
import com.h3consultingpartners.ifatccompanion.core.surface.AirportSurfaceProvider
import com.h3consultingpartners.ifatccompanion.core.surface.SurfaceSessionController
import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.surface.routing.AirportSurfaceCoordinator
import com.h3consultingpartners.ifatccompanion.core.surface.routing.AutoGateController
import com.h3consultingpartners.ifatccompanion.core.surface.routing.GateRole
import com.h3consultingpartners.ifatccompanion.core.surface.routing.TaxiKind
import com.h3consultingpartners.ifatccompanion.core.weather.AviationWeatherService
import com.h3consultingpartners.ifatccompanion.core.weather.WeatherSessionController
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherDeviationController
import com.h3consultingpartners.ifatccompanion.core.weather.radar.PrecipitationOverlayService
import com.h3consultingpartners.ifatccompanion.data.AndroidFileStore
import com.h3consultingpartners.ifatccompanion.data.DataStoreKeyValueStore
import com.h3consultingpartners.ifatccompanion.map.BaseMapImageryLoader
import com.h3consultingpartners.ifatccompanion.map.PrecipitationSampler
import com.h3consultingpartners.ifatccompanion.map.RadarRasterLoader
import com.h3consultingpartners.ifatccompanion.review.PlayReviewLauncher
import com.h3consultingpartners.ifatccompanion.service.ActiveFlightController
import com.h3consultingpartners.ifatccompanion.service.FlightSessionActiveFlightController
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The application's object graph, wired by hand.
 *
 * A dependency-injection framework would earn its keep in a multi-team app with many
 * build variants; here it would add a compiler plugin, build time and indirection to
 * assemble one long-lived graph with no variants. Constructing it explicitly keeps the
 * wiring readable and, more usefully, keeps every engine's dependencies visible as
 * constructor parameters — which is what makes them testable on a plain JVM.
 *
 * Held as a process singleton because the flight session must outlive any Activity: the
 * foreground service, the notification actions and the UI all address the same session.
 */
class AppGraph private constructor(
    private val context: Context,
) {

    /** Survives configuration changes and Activity death; cancelled only with the process. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one scope every part of the flight session runs on.
     *
     * Confining the session to a single thread is what replaces iOS's `@MainActor`: the
     * Connect poll, the ATC state machine and the read-back gate's re-prompt timer all
     * mutate the same un-synchronized state, and the coordinator holds no lock. Giving the
     * gate's timer a different dispatcher from the telemetry ingress — which is what
     * `applicationScope` did — puts two threads into that state every time a pilot lets a
     * call go unanswered for thirty seconds.
     */
    val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val clock: Clock = Clock.system

    val diagnostics: DiagnosticsStore = DiagnosticsStore(clock)

    val settingsStore: DataStoreKeyValueStore =
        DataStoreKeyValueStore.settings(context, applicationScope)

    val entitlementStore: DataStoreKeyValueStore =
        DataStoreKeyValueStore.entitlement(context, applicationScope)

    val fileStore: FileStore = AndroidFileStore(File(context.filesDir, "ifatc"))

    val http: HttpFetching = OkHttpFetcher(
        cacheDirectory = File(context.cacheDir, AppHttp.DEFAULT_CACHE_NAME),
    )

    /**
     * The route map's satellite underlay. Keyless and free — NASA GIBS, the same service
     * the global precipitation estimate already uses — so this adds no provider, no API
     * key, no billing account and no backend. When it cannot be reached the map keeps its
     * bundled coastlines and its graticule, which is the arrangement, not a fallback.
     */
    val baseMapImagery: BaseMapImageryLoader by lazy {
        BaseMapImageryLoader(
            service = BaseImageryService(http),
            diagnostics = diagnostics,
        )
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsStore) }

    /**
     * When to ask the pilot to rate the app, and the Play flow that does the asking.
     *
     * Kept on the settings store rather than a store of its own: the keys are the same ones
     * iOS uses, so the two platforms' vocabularies stay in step.
     */
    val reviewDecision: ReviewRequestManager by lazy {
        ReviewRequestManager(settingsStore, clock).also { it.noteAppStarted() }
    }

    val reviewLauncher: PlayReviewLauncher by lazy {
        PlayReviewLauncher(
            decide = reviewDecision,
            diagnostics = diagnostics,
            activity = ::activityOrNull,
        )
    }

    /**
     * The pilot's library of flights. Ported with tests since the start; wired only now.
     *
     * `defaults` is the settings store rather than the constructor's in-memory default. It
     * holds one value — which flight the session is bound to — and an in-memory one forgets
     * it on every launch, so auto-save would stop writing to the flight being flown and the
     * pilot would be warned it was unsaved for the rest of the flight.
     *
     * It loads itself in its own `init`; there is nothing to call here.
     */
    val savedFlightStore: SavedFlightStore by lazy {
        SavedFlightStore(files = fileStore, defaults = settingsStore, clock = clock)
    }

    /**
     * Save, clear and load a flight.
     *
     * The engines it resets are the ones the coordinator does not own — weather, ATIS, the
     * airport surface, the speech queue. iOS resets all of it inside one `AppModel`; here
     * the split is real, so putting a flight down has to reach each of them.
     */
    val savedFlights: SavedFlightsController by lazy {
        SavedFlightsController(
            store = savedFlightStore,
            diagnostics = diagnostics,
            session = { flightSessionCoordinator.state.value },
            captureSnapshot = { flightSessionCoordinator.captureSnapshot() },
            resetSession = {
                flightSessionCoordinator.resetForNewFlight()
                // Everything the coordinator does not own. iOS resets all of this inside one
                // AppModel; here the split is real, so putting a flight down has to reach
                // each engine that was holding a piece of it. Silence first — a half-spoken
                // clearance for a flight that no longer exists is the most obviously wrong
                // thing the app could do.
                speech.stop()
                chatter.stop()
                surfaceRouting.clear()
                weather.clearSmootherAltitude()
                weatherDeviation.reset()
                precipitationSampler.reset()
                autoGates.reset()
                // The link is bound to whatever flight was live when it opened: after a
                // swap in the sim it keeps serving the previous aircraft's position and
                // plan. This is the same thing the Reconnect button does by hand, which is
                // what pilots were having to do after clearing or loading a flight.
                flightSource.refreshConnection()
            },
            restoreSession = { snapshot ->
                flightSessionCoordinator.restore(snapshot)
                // The world is re-derived rather than restored: the aircraft's real position
                // and the weather on the route are whatever they are now, not what they were
                // when the flight was put away.
                applicationScope.launch {
                    surface.refresh(snapshot.flightPlan ?: flightSessionCoordinator.state.value.flightPlan)
                    weather.refresh()
                    weather.refreshAtis()
                }
                flightSource.refreshConnection()
            },
            clearResumableSession = { sessionStore.clear() },
            settings = { settingsRepository.state.value.autoSaveFlights },
        )
    }

    val entitlements: PlayBillingRepository by lazy {
        PlayBillingRepository(
            context = context,
            scope = applicationScope,
            cache = entitlementStore,
            diagnostics = diagnostics,
        )
    }

    val radio: RadioAudioEngine by lazy {
        RadioAudioEngine(
            context = context,
            scope = applicationScope,
            // Route the platform's focus loss/gain into the chatter service's own
            // interruption hooks, which :core already implements.
            onInterruption = { began -> if (began) chatter.onInterruptionBegan() else chatter.onInterruptionEnded() },
        )
    }

    val speech: AndroidSpeechService by lazy {
        AndroidSpeechService(
            context = context,
            scope = applicationScope,
            radio = radio,
            configuration = { settingsRepository.settings.toSpeechConfiguration() },
            // Both lambdas reach `chatter`, which itself depends on `speech`. That is not a
            // cycle: neither runs during construction, and by the time either is invoked
            // `speech` is already built, so resolving `chatter` here is safe.
            micKey = { chatter.micKey(it) },
            chatterOwnsRadio = { chatter.isRunning.value },
        )
    }

    /**
     * The Infinite Flight link. It runs on a single-threaded scope so its token and task
     * bookkeeping keeps the same "one mutator" guarantee the iOS main actor gives.
     */
    val connect: IFConnectManager by lazy {
        IFConnectManager(
            scope = sessionScope,
            clock = clock,
            diagnostics = diagnostics,
        )
    }

    /**
     * The one flight session. A process singleton because it must outlive any Activity:
     * the foreground service, the notification actions and the UI all address it.
     */
    /** Mock Mode's scripted feed — the free, offline flight, and the tests' stand-in sim. */
    /**
     * On [sessionScope]: its ticks are pushed straight into the coordinator's state.
     *
     * The subscription is the whole point of the feed and was missing: `onState` is the
     * same closure shape `IFConnectManager.onState` has precisely so the session takes the
     * demo exactly as it takes the live link, and with nothing assigned to it the mock
     * flight synthesized a full gate-to-gate telemetry stream that reached nobody.
     */
    val mockFeed: MockSimulatorFeed by lazy {
        MockSimulatorFeed(scope = sessionScope, clock = clock).also { feed ->
            feed.onState = { state -> flightSessionCoordinator.ingestAircraftState(state) }
        }
    }

    val weatherService: AviationWeatherService by lazy {
        AviationWeatherService(http, clock = clock, diagnostics = diagnostics)
    }

    val atisService: ATISService by lazy {
        ATISService(http, clock = clock, diagnostics = diagnostics)
    }

    val precipitationOverlay: PrecipitationOverlayService by lazy {
        PrecipitationOverlayService(http, clock).also { it.configure(diagnostics) }
    }

    /**
     * The precipitation raster the route map draws.
     *
     * [precipitationOverlay] chooses the provider, builds the URL, fetches and backs off —
     * its own KDoc says the image "is fetched by the app" — and until now nothing was the
     * app. A whole provider ladder produced no pixels.
     */
    val radarRaster: RadarRasterLoader by lazy {
        RadarRasterLoader(overlays = precipitationOverlay, diagnostics = diagnostics)
    }

    /**
     * The weather half of the session — the aviation-weather fetch, the ride reports, ATIS,
     * and the overlay descriptor. Separate from the flight session because the ATC state
     * machine and the weather feed share almost nothing but the flight plan.
     */
    val weather: WeatherSessionController by lazy {
        WeatherSessionController(
            weatherService = weatherService,
            atisService = atisService,
            overlayService = precipitationOverlay,
            clock = clock,
            diagnostics = diagnostics,
            mock = mockFeed,
            settingsProvider = { settingsRepository.state.value },
            // The ride read-out is a controller call like any other, so it follows the
            // pilot's digit style and phraseology pack rather than a default engine.
            phraseologyProvider = { flightSessionCoordinator.phraseologyEngine },
        )
    }

    val surfaceProvider: AirportSurfaceProvider by lazy {
        AirportSurfaceProvider(http, AirportSurfaceCache(fileStore), clock = clock)
            .also { it.configure(diagnostics) }
    }

    /** The OpenStreetMap surface for both ends of the flight, and the routing graph on it. */
    val surface: SurfaceSessionController by lazy {
        SurfaceSessionController(
            surfaceProvider,
            clock = clock,
            diagnostics = diagnostics,
            // Overpass decode, normalize and two routing-graph builds. Seconds of
            // uninterrupted CPU at a large field, and every caller reaches it from the
            // ViewModel scope, which is the main thread.
            workContext = Dispatchers.Default,
        )
    }

    val phraseologyProfiles: PhraseologyProfileStore by lazy {
        PhraseologyProfileStore(fileStore)
    }

    /**
     * The crash/relaunch session snapshot. Ported and tested from the start and never
     * constructed, so a flight killed mid-cruise — swiped away, or reclaimed by the system
     * once no foreground service was running — was simply gone on reopening, transcript
     * and all, despite the store being built to restore any snapshot under six hours old.
     */
    val sessionStore: SessionStateStore by lazy { SessionStateStore(fileStore, clock) }

    /**
     * Fills a blank gate field from the airport's own stand data.
     *
     * `GateAssigner` — the stand picker, its operator matching, its size classes and its
     * tie-breaking — was ported with tests and called from nowhere, so the "Assign gates
     * automatically" switch in Settings wrote a boolean nobody read: a blank gate stayed
     * blank, and the taxi route had no stand to route to or from.
     */
    val autoGates: AutoGateController by lazy {
        AutoGateController(
            clock = clock,
            diagnostics = diagnostics,
            settingsProvider = { settingsRepository.state.value },
            planProvider = { flightSessionCoordinator.state.value.flightPlan },
            aircraftProvider = { flightSessionCoordinator.state.value.aircraftState },
            // Once the taxi is under way the departure gate is where the aircraft actually
            // is, and reassigning it then would re-route the pilot mid-push.
            taxiHasBegun = { surfaceRouting.state.value.kind != TaxiKind.NONE },
            surfaceProvider = { icao, reference -> surfaceRouting.surfaceModel(icao, reference) },
            referenceProvider = { icao ->
                val plan = flightSessionCoordinator.state.value.flightPlan
                when {
                    icao.equals(plan.departure, ignoreCase = true) -> plan.departureCoordinate
                    icao.equals(plan.destination, ignoreCase = true) -> plan.destinationCoordinate
                    else -> null
                } ?: AirportDatabase.coordinate(icao)
            },
            writeGate = { role, gate, stamp ->
                when (role) {
                    GateRole.DEPARTURE -> {
                        settingsRepository.setDepartureGate(gate)
                        settingsRepository.setAutoAssignedDepartureGate(stamp)
                    }
                    GateRole.ARRIVAL -> {
                        settingsRepository.setArrivalGate(gate)
                        settingsRepository.setAutoAssignedArrivalGate(stamp)
                    }
                }
                // The plan is what the taxi route and the clearances read, so a gate that
                // only reached the settings field would still leave the route with nothing
                // to route to.
                val plan = flightSessionCoordinator.state.value.flightPlan
                flightSessionCoordinator.applyFlightPlan(
                    when (role) {
                        GateRole.DEPARTURE -> plan.copy(departureGate = gate)
                        GateRole.ARRIVAL -> plan.copy(arrivalGate = gate)
                    },
                )
            },
        )
    }

    /**
     * Turns the live precipitation image into the cells the deviation flow routes around.
     *
     * `RadarImageSampler` is ported with its own tests and had no caller: outside Mock Mode
     * there was no cell data for any deviation to be computed from, the Diagnostics
     * "sampled cells" row always read zero, and the sampled-cell map layer never drew.
     */
    val precipitationSampler: PrecipitationSampler by lazy {
        PrecipitationSampler(
            overlays = precipitationOverlay,
            weather = weather,
            clock = clock,
            diagnostics = diagnostics,
        )
    }

    /**
     * The simulated weather-deviation flow: the storms on the route, the reroute drawn
     * around them, and the exchange with the controller that gets the aircraft past.
     *
     * `RouteWeatherConflictDetector`, `WeatherDeviationEngine`, `WeatherDeviationPhraseology`
     * and `WeatherHazard` are together some 4,800 lines of ported, tested logic that until
     * now was constructed nowhere: no hazards were ever built, no conflict was ever detected,
     * the mint line and its faint previews had no assignment anywhere in `:app`, and the ATC
     * screen's deviation slot was filled by an empty default.
     */
    val weatherDeviation: WeatherDeviationController by lazy {
        WeatherDeviationController(
            clock = clock,
            diagnostics = diagnostics,
            settingsProvider = { settingsRepository.state.value },
            // The weather calls follow the pilot's pack and digit style like every other
            // line, so the engine tracks the session's rather than a default.
            engineProvider = { flightSessionCoordinator.phraseologyEngine },
        )
    }

    val flightSessionCoordinator: FlightSessionCoordinator by lazy {
        FlightSessionCoordinator(
            scope = sessionScope,
            clock = clock,
            diagnostics = diagnostics,
            connect = connect,
            settingsProvider = { settingsRepository.settings },
            speak = { transmission -> speech.speak(transmission) },
            // What turns "taxi to runway 16L" into "taxi to runway 16L via A, C, hold
            // short of 27". The route engine has always been able to produce this; nothing
            // carried it into the clearance.
            taxiContextProvider = { surfaceRouting.taxiClearanceContext() },
            // Reaching `savedFlights`, which reaches back here, is not a cycle: neither
            // lambda runs during construction, and by the time either is invoked both are
            // built. Same arrangement as `speech` and `chatter` above.
            savedFlightBinding = { savedFlights.binding() },
            // The pilot's custom phraseology, if they have selected a profile. The whole
            // Profiles screen shipped — create, edit, import, share, select — and the
            // engine's `profile` was supplied by nothing, so none of it changed a word.
            profileProvider = { phraseologyProfiles.activeProfile },
            authorizeCrossing = { surfaceRouting.crossingReadbackReceived() },
            weatherDeviation = weatherDeviation,
            // Who answers a Ride Report or Destination Weather request. The whole
            // RideReportEngine was ported, tested and constructed nowhere, so Center never
            // answered either one.
            weatherAnswers = weather,
            // Mock Mode only: walk the scripted aircraft to the stand so the block-in the
            // arrival flow waits for actually happens.
            advanceMockToGate = { mockFeed.setPhase(FlightPhase.PARKED) },
            // The field's real runway ends, once its surface has been fetched — which,
            // at the departure airport by the time a takeoff clearance is due, is the
            // field the aircraft is sitting on.
            surfaceRunwaysProvider = { icao -> surface.runwayIdents(icao) },
            // What the departure taxi looks like right now, for the monitor-Tower hand-off
            // and the "line up and wait" that follows it. Read as one snapshot so all four
            // facts describe the same instant.
            groundHandoffSignals = {
                val routing = surfaceRouting.state.value
                GroundHandoffSignals(
                    isDepartureSurface = routing.kind == TaxiKind.DEPARTURE,
                    approachingRunwayHandoff = routing.approachingRunwayHandoff,
                    approachingRunwayLineup = routing.approachingRunwayLineup,
                    awaitingCrossingReadback = routing.awaitingCrossingReadback,
                    awaitingTaxiReadback = routing.awaitingTaxiReadback,
                )
            },
        ).also { coordinator ->
            // The taxi phrasing must follow the pilot's digit style and phraseology pack
            // like every other line, so it tracks the same engine rather than snapshotting
            // one at construction.
            coordinator.onEngineRebuilt = { engine -> surfaceRouting.updateEngine(engine) }
        }
    }

    /**
     * Taxi routing over the airport surface: the route itself, its runway crossings, the
     * hold-short points, and the phraseology that names them.
     *
     * `AirportSurfaceCoordinator` is 2,106 lines of ported, tested logic that until now
     * was constructed nowhere, so no route was ever computed: the taxi map drew the field
     * with no route on it and every taxi clearance stayed in its generic form.
     *
     * Its own KDoc says it is not thread-safe and must be driven from one dispatcher —
     * `sessionScope`, the same one the flight session is confined to. The `scope` it is
     * given is where its *asynchronous* work runs, and that is deliberately the Default
     * pool: routing is A* over the surface graph, and running that on the main thread is
     * the ANR this port already had to fix once.
     */
    val surfaceRouting: AirportSurfaceCoordinator by lazy {
        AirportSurfaceCoordinator(
            provider = surfaceProvider,
            scope = applicationScope,
            clock = clock,
            diagnostics = diagnostics,
        ).also { routing ->
            routing.configure(
                engine = flightSessionCoordinator.phraseologyEngine,
                emit = { transmission -> flightSessionCoordinator.post(transmission) },
                callsign = {
                    val plan = flightSessionCoordinator.state.value.flightPlan
                    flightSessionCoordinator.phraseologyEngine.callsign(
                        airline = plan.airline,
                        flightNumber = plan.flightNumber,
                        fallback = plan.callsign,
                    )
                },
            )
        }
    }

    /**
     * Which data source the flight runs on, and everything that starting one entails.
     *
     * Until this existed the app had no data source in either mode: the mock feed's
     * `onState` was unassigned, `connect(...)` and `startAutoDiscover(...)` had no call
     * sites anywhere, and `AppSettings.mockMode` defaulted to true with nothing acting on
     * it. A fresh launch produced a static shell — no aircraft state, no phase, no ATC
     * flow. The sequencing itself lives in `:core` because the order these engines are
     * started and reset in is a decision, not an Android detail.
     */
    val flightSource: FlightSourceController by lazy {
        FlightSourceController(
            coordinator = flightSessionCoordinator,
            connect = connect,
            mock = mockFeed,
            scope = sessionScope,
            diagnostics = diagnostics,
            settingsProvider = { settingsRepository.settings },
            persistMockMode = { on -> settingsRepository.setMockMode(on) },
            persistEndpoint = { host, port ->
                settingsRepository.setHost(host)
                settingsRepository.setPort(port)
            },
            // Everything a fresh session has to reset that the coordinator does not own.
            // The same set the Clear Flight path resets, for the same reason: silence
            // first, because a half-spoken clearance for a flight that no longer exists is
            // the most obviously wrong thing the app could do.
            resetSubsystems = {
                speech.stop()
                chatter.stop()
                surfaceRouting.clear()
                weather.clearSmootherAltitude()
            },
            refreshWeather = {
                weather.refresh()
                weather.refreshAtis()
                surface.refresh(flightSessionCoordinator.state.value.flightPlan)
            },
            // A mock snapshot must never be restored into a live flight, or the reverse:
            // the transcript would describe a flight that is not happening. Mock Mode never
            // restores at all — its feed always restarts at PREFLIGHT, so a restored
            // transcript at cruise would describe a flight the feed is not flying.
            restoreSession = {
                val snapshot = sessionStore.loadResumable()
                    ?.takeIf { !it.mockMode && !settingsRepository.state.value.mockMode }
                if (snapshot == null) {
                    false
                } else {
                    flightSessionCoordinator.restore(snapshot)
                    diagnostics.log(
                        DiagnosticCategory.SESSION,
                        message = "Restored the previous session after a relaunch",
                    )
                    true
                }
            },
            unbindSavedFlight = { savedFlightStore.setActive(null) },
            reviewBeforeFirstCall = {
                reviewLauncher.requestIfAppropriate(ReviewRequestManager.Trigger.BEFORE_FIRST_CALL)
            },
        )
    }

    /**
     * The computed route, reduced to the three fields a clearance needs.
     *
     * Written here rather than in `:core` so the ATC flow keeps knowing nothing about the
     * surface-routing subsystem: it asks for three strings and does not care where they
     * came from. Null whenever no route exists — no OpenStreetMap coverage, an Overpass
     * outage, or simply nothing computed yet — and the clearance then degrades to its
     * generic form rather than saying something wrong.
     */
    private fun AirportSurfaceCoordinator.taxiClearanceContext(): TaxiClearanceContext? {
        val route = state.value.route ?: return null
        return TaxiClearanceContext(
            taxiways = route.taxiwaysText,
            // The first crossing is the one the clearance names; later ones are issued as
            // the taxi reaches them, which is what the crossing state machine is for.
            crossingRunway = route.crossings.firstOrNull()?.runwayIdent,
            parkingTaxiway = if (route.isDeparture) "" else route.taxiwaySequence.lastOrNull().orEmpty(),
        )
    }

    /**
     * The bridge the foreground service reads. `by lazy` is already thread-safe, so this
     * needs no `@Volatile` — and could not carry one, since the annotation applies only to
     * a `var` with a real backing field.
     */
    val activeFlightController: ActiveFlightController by lazy {
        FlightSessionActiveFlightController(
            coordinator = flightSessionCoordinator,
            scope = sessionScope,
            clock = clock,
            onStopRequested = {
                connect.disconnect()
                // stopMock, not mockFeed.stop: the periodic weather refresh belongs to the
                // running feed, and a stopped flight must not keep fetching for it.
                flightSource.stopMock()
                chatter.stop()
                // Tearing down the transports is not enough on its own: without this the
                // session still looks active, so the service that Stop was meant to end
                // keeps running.
                flightSessionCoordinator.endSession()
                // Stopping is deliberate, so there is nothing to resume; leaving the
                // snapshot would restore the flight the pilot just ended.
                sessionStore.clear()
                diagnostics.log(DiagnosticCategory.SESSION, message = "Flight session stopped from the notification")
            },
        )
    }

    /** Load persisted settings into memory before the first screen reads one. */
    // region Android edges
    //
    // The only things in this file that genuinely need Android. Every decision behind them
    // lives in :core; these just carry the result out to the system.

    /**
     * The Activity currently on screen, held weakly.
     *
     * Play Billing's purchase flow needs one and nothing else in the app does. A strong
     * reference here would keep a destroyed Activity — and its whole view tree — alive for
     * the life of the process, which is exactly the leak this class would otherwise cause.
     */
    private var currentActivity: WeakReference<Activity>? = null

    fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    fun activityOrNull(): Activity? = currentActivity?.get()

    /**
     * Open a link in a Custom Tab — the Android counterpart of `SFSafariViewController`,
     * which is what iOS uses for SimBrief and the legal links. The site keeps its own
     * branding and session, and the app neither scrapes it nor injects into it.
     *
     * Falls back to whatever browser the user has when no Custom Tabs provider is
     * installed, and does nothing at all when there is no browser — a missing browser is
     * not a reason to crash mid-flight.
     */
    fun openLink(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        val host = activityOrNull() ?: context
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { intent.launchUrl(host, uri) }.isSuccess
        if (opened) return
        val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { host.startActivity(fallback) }
            .onFailure {
                diagnostics.log(
                    DiagnosticCategory.GENERAL,
                    level = DiagnosticLevel.WARNING,
                    message = "No browser available to open $url",
                )
            }
    }

    /** Hand text to the system share sheet — how a profile or a diagnostics dump leaves the app. */
    fun shareText(subject: String, text: String) {
        val host = activityOrNull() ?: context
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(share, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { host.startActivity(chooser) }
    }

    /**
     * Switch between the scripted mock flight and a live Connect session.
     *
     * Mock Mode is free and offline, so turning it on stops the network side entirely
     * rather than running both; turning it off stops the feed and lets discovery start.
     */
    fun setMockMode(enabled: Boolean) {
        flightSource.toggleMockMode(enabled, hasLiveAccess = entitlements.state.value.hasLiveAccess)
    }

    // endregion

    suspend fun warmUp() {
        settingsStore.load()
        entitlementStore.load()

        // Both of these need an explicit start, and neither used to get one — the effect
        // was an app that never spoke a word and a paywall whose buttons never enabled.
        //
        // They are started here rather than lazily on first use because both take real
        // time to become ready: TextToSpeech binds to a service and only then reports its
        // voices (the Settings picker is empty until it does), and the BillingClient must
        // connect and fetch ProductDetails before any purchase can be launched.
        speech.initialize()
        entitlements.start()

        // Parse the bundled coastlines off the main thread.
        //
        // CoastlineData caches, so this happens exactly once — but whoever triggers it
        // pays for it, and without this that is the Compose draw phase on the first frame
        // of the Weather tab, parsing five thousand points while a frame is due. Doing it
        // here costs nothing anyone is waiting on.
        applicationScope.launch { CoastlineData.lines() }

        // Mirror the Infinite Flight link into the session: the connection state every
        // screen shows, and the live-ATC staffing that decides whether the companion steps
        // aside for a human controller. Both were published by the manager and read by
        // nobody, so the ATC header never left "Not connected" and the standby guard never
        // engaged on a live flight.
        applicationScope.launch {
            connect.state.collect { link ->
                flightSessionCoordinator.applyConnectionState(link.connectionState)
                if (!settingsRepository.state.value.mockMode) {
                    flightSessionCoordinator.applyLiveATC(link.liveATC)
                }
            }
        }

        // Keep the active mode in sync with the subscription. Whenever Live access is lost
        // — expired, revoked, refunded — the app falls back to Mock Mode mid-flight rather
        // than continuing to serve a paid feature, and whenever it is gained the pilot is
        // promoted out of the demo without having to find the toggle.
        applicationScope.launch {
            entitlements.state
                .map { it.hasLiveAccess }
                .distinctUntilChanged()
                .collect { hasAccess ->
                    flightSessionCoordinator.setLiveAccess(hasAccess)
                    flightSource.applyEntitlement(hasAccess)
                }
        }

        // Bring up a data source. Everything above is arrangement; this is the app
        // starting to fly. A resumable session is restored inside the live path, which is
        // where iOS restores it — a mock flight always begins at the gate, because its
        // feed does.
        flightSource.startAtLaunch(hasLiveAccess = entitlements.state.value.hasLiveAccess)

        // Keep the snapshot current. Written when the transcript grows rather than on a
        // timer: transmissions are seconds apart at their densest, and the transcript is
        // the only part a relaunch cannot reconstruct from the next telemetry fix.
        applicationScope.launch {
            flightSessionCoordinator.state
                .map { it.transcript.size }
                .distinctUntilChanged()
                .collect { size ->
                    if (size == 0) return@collect
                    runCatching { sessionStore.save(flightSessionCoordinator.captureSnapshot()) }
                }
        }
    }

    /**
     * The subset of settings the speech service reads, snapshotted per call so a toggle
     * takes effect on the next transmission.
     */
    /**
     * Background radio chatter.
     *
     * On Android this is only ever a feature — the foreground service, not an audio
     * session, is what keeps a flight running in the background. See [AndroidChatterRadio].
     */
    val chatter: AmbientChatterService by lazy {
        AmbientChatterService(
            radio = AndroidChatterRadio(
                engine = radio,
                speech = speech,
                settings = { settingsRepository.state.value },
            ),
            scope = sessionScope,
        ).also { it.configure(settingsRepository.state.value) }
    }

    /** Push-to-talk recognition, on-device only. */
    val pushToTalk: PushToTalkRecognizer by lazy { PushToTalkRecognizer(context, diagnostics) }

    private fun AppSettings.toSpeechConfiguration() = AndroidSpeechService.SpeechConfiguration(
        voiceEnabled = voiceEnabled,
        radioEffectEnabled = transmissionStaticEnabled,
        speechRate = speechRate,
        speechPitch = speechPitch,
        voiceVolume = voiceVolume,
        defaultVoiceId = defaultVoiceID,
        pilotVoiceId = voicePilot,
        atisVoiceId = voiceATIS,
        controllerVoiceIds = mapOf(
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.GROUND to voiceGround,
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.CLEARANCE to voiceGround,
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.TOWER to voiceTower,
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.DEPARTURE to voiceDeparture,
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.CENTER to voiceCenter,
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.APPROACH to voiceApproach,
            // Ramp is a simulated local position, not ATC; it shares the Ground voice.
            com.h3consultingpartners.ifatccompanion.core.model.ATCFacility.RAMP to voiceGround,
        ),
        icaoPhraseology = phraseologyMode == PhraseologyMode.ICAO,
    )

    companion object {
        // lint flags a static field that can reach a Context. It cannot see that create()
        // only ever stores context.applicationContext, which lives exactly as long as the
        // process does, so there is no Activity or View to leak. Suppressed rather than
        // reshaped, because the singleton is the app's composition root.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AppGraph? = null

        fun create(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }

        /** The graph, or null before [create] has run (a receiver woken with no process). */
        fun instanceOrNull(): AppGraph? = instance

        fun require(): AppGraph =
            instance ?: error("AppGraph accessed before Application.onCreate")
    }
}
