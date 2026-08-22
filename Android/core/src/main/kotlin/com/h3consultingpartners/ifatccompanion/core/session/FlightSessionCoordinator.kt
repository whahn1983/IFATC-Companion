package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.airports.ProcedureParser
import com.h3consultingpartners.ifatccompanion.core.airports.RampProfile
import com.h3consultingpartners.ifatccompanion.core.airports.RampType
import com.h3consultingpartners.ifatccompanion.core.airports.RunwayDatabase
import com.h3consultingpartners.ifatccompanion.core.atc.ATCContext
import com.h3consultingpartners.ifatccompanion.core.atc.ApproachIntercept
import com.h3consultingpartners.ifatccompanion.core.atc.ATCStateMachine
import com.h3consultingpartners.ifatccompanion.core.atc.GoAroundPattern
import com.h3consultingpartners.ifatccompanion.core.atc.PhaseDetector
import com.h3consultingpartners.ifatccompanion.core.atc.PilotIntent
import com.h3consultingpartners.ifatccompanion.core.atc.PilotIntentParser
import com.h3consultingpartners.ifatccompanion.core.atc.PilotResponseEngine
import com.h3consultingpartners.ifatccompanion.core.atc.RunwayLineupDetector
import com.h3consultingpartners.ifatccompanion.core.atc.TaxiRoutePlanner
import com.h3consultingpartners.ifatccompanion.core.atis.ATISSession
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectConnectionState
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectManager
import com.h3consultingpartners.ifatccompanion.core.connect.LiveATCStatus
import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSector
import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSectorDatabase
import com.h3consultingpartners.ifatccompanion.core.enroute.CenterSectorTracker
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.geo.HeadingSolver
import com.h3consultingpartners.ifatccompanion.core.geo.WindEstimator
import com.h3consultingpartners.ifatccompanion.core.geo.validCoordinateOrNull
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.model.Waypoint
import com.h3consultingpartners.ifatccompanion.core.persistence.SavedFlightPolicy
import com.h3consultingpartners.ifatccompanion.core.persistence.SessionSnapshot
import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProcedure
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyProfile
import com.h3consultingpartners.ifatccompanion.core.phraseology.RampPhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import com.h3consultingpartners.ifatccompanion.core.weather.deviation.WeatherDeviationController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The flight session: it takes telemetry in, runs the ATC conversation, and publishes
 * one immutable [FlightSessionState] the whole app renders from.
 *
 * This is the Android counterpart of the iOS `AppModel`. That file is 8,541 lines, and
 * porting it as one class would have reproduced the problem rather than the behaviour —
 * so the rules that are easy to get subtly wrong live in their own tested objects
 * ([PilotActionAvailability], [AtcFlowOrder], [ReadbackGate], [PhaseDetector],
 * [ATCStateMachine]) and this coordinator is the wiring between them.
 *
 * It owns no Android type and no UI type. Everything it needs from the platform arrives
 * as a constructor parameter, which is what lets a whole simulated flight be driven in a
 * unit test.
 */
/**
 * What a computed taxi route contributes to a clearance: the taxiway sequence to say, the
 * runway to hold short of, and — on arrival — the taxiway leading to the ramp.
 *
 * Deliberately a plain triple of strings rather than the route itself, so `:core`'s ATC
 * flow stays independent of the surface-routing subsystem.
 */
data class TaxiClearanceContext(
    val taxiways: String,
    val crossingRunway: String?,
    val parkingTaxiway: String,
)

class FlightSessionCoordinator(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val connect: IFConnectManager? = null,
    /** Reads the current settings each time a decision needs one, so a toggle takes effect at once. */
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    /** Speaks a transmission. The app supplies text-to-speech; tests supply a recorder. */
    private val speak: (ATCTransmission) -> Unit = {},
    /**
     * The taxi route currently in force, if any, for the fields the phraseology engine
     * needs to say something specific.
     *
     * `buildContext` used to hardcode `taxiway = ""`, `crossingRunway = null` and
     * `parkingTaxiway = ""`, which is why every taxi clearance came out content-free —
     * "taxi to runway 16L" with no route, at every airport, forever. The route engine that
     * can fill them exists and is tested; it simply had no way to reach here.
     *
     * Defaulted so the engine stays independent of the surface subsystem and the tests
     * keep constructing this with no extra argument.
     */
    private val taxiContextProvider: () -> TaxiClearanceContext? = { null },
    /**
     * What the saved-flight library currently says about this session.
     *
     * Read through a lambda rather than held, because the answer changes when the pilot
     * saves, loads or deletes — none of which the session hears about. Defaulted so the
     * coordinator stays independent of the library and every existing caller and test keeps
     * constructing it unchanged.
     */
    private val savedFlightBinding: () -> SavedFlightBinding = { SavedFlightBinding() },
    /**
     * The en-route sector map. Defaulted to the shared instance the app loads once.
     *
     * Injectable because the shipped dataset is the whole world and the behaviour worth
     * testing is what happens at one boundary: a handful of known sectors makes a
     * Center-to-Center hand-off assertable in a way the global set does not.
     */
    private val sectorDatabase: CenterSectorDatabase = CenterSectorDatabase.shared,
    /**
     * Who answers a Ride Report or Destination Weather request, and what a reported ride
     * says about climbing into a given level.
     *
     * The flight session does not own the weather — deliberately, since the ATC state
     * machine and the weather feed share almost nothing but the flight plan. But the
     * controller still has to answer, so the session asks through this seam. Defaulted to
     * [WeatherAnswering.None] so the coordinator stays independent of the weather subsystem.
     */
    private val weatherAnswers: WeatherAnswering = WeatherAnswering.None,
    /**
     * Move the demo's scripted aircraft to the stand.
     *
     * Only Mock Mode has anything to do here: the demo has to be walked to the gate for the
     * block-in to play out, and the session does not own the feed. A no-op everywhere else.
     */
    private val advanceMockToGate: () -> Unit = {},
    /**
     * The runway-end idents the loaded airport surface has for a field.
     *
     * Picking the into-wind runway from a field's *real* runways is what the wind is
     * genuinely good for; guessing a runway name from the wind alone is what it is not.
     * Defaulted to nothing so the coordinator stays independent of the surface subsystem.
     */
    private val surfaceRunwaysProvider: (icao: String) -> List<String> = { emptyList() },
    /**
     * What the airport surface knows about the departure taxi right now.
     *
     * Drives the monitor-Tower hand-off and the "line up and wait" that follows it — the
     * whole of which was ported (`monitorTower`, `numberOneForTakeoff`,
     * `approachingRunwayHandoff`) and reachable from nothing, so `monitoringTower` was
     * never set on any flight. Defaulted to all-false so the coordinator stays independent
     * of the surface subsystem.
     */
    private val groundHandoffSignals: () -> GroundHandoffSignals = { GroundHandoffSignals() },
    /**
     * The pilot's active custom-phraseology profile, if they have selected one.
     *
     * Read per rebuild rather than held, because selecting a profile — or editing the one
     * already selected — has to change what the controller says on the next call. The whole
     * Phraseology Profiles screen shipped and worked, and the engine's `profile` was never
     * supplied by anything, so every template and custom airline call set in it was inert.
     */
    private val profileProvider: () -> PhraseologyProfile? = { null },
    /**
     * Authorize the runway crossing the pilot has just read back.
     *
     * The read-back and the authorization are one action, and the port had split them so
     * that each path dropped the other half: the Read Back button posted the words and
     * never authorized the crossing, and the taxi map's own button authorized it silently,
     * with nothing on the frequency and nothing in the transcript.
     */
    private val authorizeCrossing: () -> Unit = {},
    /**
     * The simulated weather-deviation flow — the storms on the route, the reroute drawn
     * around them, and the exchange with the controller that gets the aircraft past.
     *
     * Injected rather than constructed here because it is a whole subsystem of its own with
     * its own state, and because a session under test has no weather to route around.
     * Defaulted to null, which is exactly the app the port shipped: no hazards, no mint
     * line, and a response card that could never appear.
     */
    private val weatherDeviation: WeatherDeviationController? = null,
) {

    private val _state = MutableStateFlow(FlightSessionState())
    val state: StateFlow<FlightSessionState> = _state.asStateFlow()

    /**
     * Which Center sector is working the flight.
     *
     * The database, the polygon lookup, the boundary hysteresis and the simulated
     * frequencies were all ported and tested — and nothing ever fed them a position, so
     * `centerSectorName` stayed null for every flight and Center always identified itself
     * generically instead of as, say, "Fort Worth Center".
     */
    private val sectorTracker = CenterSectorTracker()
    private var sectorLoadRequested = false

    /**
     * Whether a go-around is being flown.
     *
     * While it is set the automatic flow is held: the missed-approach climb would otherwise
     * be read straight back through the cleared-approach call while the aircraft is still
     * in the pattern, so the pilot would be cleared to land on a runway they just abandoned.
     * Released by the pilot re-establishing with Approach.
     */
    private var goAroundInProgress = false

    /** The sector the engines were last rebuilt for, so an unchanged fix rebuilds nothing. */
    private var appliedCenterSector: CenterSector? = null

    /**
     * A confirmed sector crossing waiting for a free radio.
     *
     * Held rather than dropped: the pilot may owe a read-back, or a hand-off they have not
     * checked in on may be outstanding, and cutting across either with a second controller's
     * frequency change is how a pilot ends up on the wrong one. A second crossing while the
     * radio is busy folds into this rather than queueing — the origin stays the sector the
     * pilot was last *told* to contact, so what they hear is one hand-off, not two.
     */
    private var pendingCenterCrossing: CenterSectorTracker.Crossing? = null

    /**
     * Set from a Center-to-Center hand-off until the pilot checks in on the new frequency.
     *
     * It exists to stop the check-in advancing the conversation. A pilot calling up a new
     * enroute sector is announcing themselves, not asking for the next clearance — and
     * without this the cruise check-in is answered with the top-of-descent call, because
     * descent is the next Center state after cruise.
     */
    private var awaitingCenterSectorCheckIn = false

    /**
     * A sector id carried by a restored session, resolved once the map finishes loading.
     *
     * A restore runs at launch, before the sector database has finished parsing in the
     * background. Without this the tracker starts empty and the first fix after the load
     * looks like entry into a brand-new sector — so a reconnect mid-cruise re-announces a
     * hand-off the pilot already made.
     */
    private var pendingCenterSectorID: String? = null

    private val settings: AppSettings get() = settingsProvider()

    private var engine: PhraseologyEngine = buildEngine()
    private var stateMachine = ATCStateMachine(engine)
    private var pilotEngine = PilotResponseEngine(engine)
    private var rampEngine = RampPhraseologyEngine(engine)
    private val phaseDetector = PhaseDetector()

    /**
     * The deterministic taxi layout every field falls back to.
     *
     * A live OpenStreetMap route supersedes it the moment one resolves; until then this
     * is what keeps a clearance from reading "taxi to runway 27 via ." — which is what
     * it read at every field with no extract, and at every field before one landed.
     */
    private val taxiPlanner = TaxiRoutePlanner()
    private val lineupDetector = RunwayLineupDetector()
    private val intentParser = PilotIntentParser()

    private val readbackGate = ReadbackGate(
        scope = scope,
        isStandingBy = { _state.value.companionStandby },
        repeatCall = ::repeatPendingCall,
        onChanged = { closed -> _state.update { it.copy(awaitingReadback = closed) } },
    )

    /** Field elevation at the departure runway, captured at takeoff. */
    private var liftoffAltitudeMSL: Double = 0.0

    /**
     * Departure field elevation in feet MSL, captured from on-ground telemetry before
     * departure. Zero until a genuine on-ground snapshot arrives.
     */
    private var departureFieldElevationMSL: Double = 0.0

    init {
        connect?.onState = ::ingestAircraftState
        connect?.onFlightPlan = ::ingestFlightPlan
    }

    /** Rebuild the phraseology engine and everything derived from it after a settings change. */
    /**
     * The phraseology engine in force. Exposed because the surface-routing coordinator
     * builds its taxi phrasing from the same engine, and must therefore be handed the same
     * digit style and phraseology pack the pilot chose.
     */
    val phraseologyEngine: PhraseologyEngine get() = engine

    /**
     * Notified whenever the engine is rebuilt, so anything holding its own reference can
     * follow. Without it a settings change would leave the taxi phrasing on the previous
     * pack while every other line moved — the kind of split nobody notices until a
     * transcript reads half ICAO and half FAA.
     */
    var onEngineRebuilt: ((PhraseologyEngine) -> Unit)? = null

    fun applyEngineConfig() {
        engine = buildEngine()
        val restored = stateMachine.current
        stateMachine = ATCStateMachine(engine)
        stateMachine.restore(restored)
        pilotEngine = PilotResponseEngine(engine)
        rampEngine = RampPhraseologyEngine(engine)
        onEngineRebuilt?.invoke(engine)
        recomputeDerivedState()
    }

    private fun buildEngine() = PhraseologyEngine(
        digitStyle = settings.digitStyle,
        mode = settings.phraseologyMode,
        profile = profileProvider(),
    )

    // region Telemetry in

    /**
     * A new aircraft-state snapshot. This is the entry point the 1 Hz Connect poll and
     * the mock feed both use.
     */
    fun ingestAircraftState(aircraft: AircraftState) {
        // An all-null snapshot arrives during the reconnect handshake, when every state
        // read failed. There is nothing in one for any part of the app to act on.
        if (!aircraft.hasUsableTelemetry) return

        val previous = _state.value
        val jumped = telemetryJumped(previous.aircraftState, aircraft)
        // The wind the deviation vectors are crabbed for, and the declination they are
        // converted by. Folded in before anything reads a heading; skipped on a jump,
        // whose two fixes did not come from one continuous flight and would solve a wind
        // out of the discontinuity.
        if (!jumped) windEstimator.update(aircraft)
        val detection = phaseDetector.detect(
            state = aircraft,
            plan = previous.flightPlan,
            airports = AirportDatabase,
            previous = previous.phase,
        )

        captureDepartureFieldElevation(aircraft, previous)
        // Position-driven decisions stand down for the tick after a jump. The aircraft did
        // not fly the intervening ground track, so crossing a sector boundary or a weather
        // corridor in one step means the app was not watching — not that anything happened.
        // Replaying it produces a hand-off to a sector already left behind and a reroute
        // around weather already passed.
        if (!jumped) {
            updateCenterSector(aircraft)
            updateWeatherDeviation(aircraft, detection.phase)
        }

        _state.update {
            it.copy(
                aircraftState = aircraft,
                phase = detection.phase,
                phaseDebug = detection.debug,
            )
        }

        if (detection.phase != previous.phase) {
            diagnostics.log(
                DiagnosticCategory.ATC,
                message = "Phase ${previous.phase.title} → ${detection.phase.title}",
            )
        }

        advanceAutomaticFlow(aircraft, detection.phase)
        recomputeDerivedState()
    }

    /**
     * Whether this fix is further from the last one than the aircraft could have flown.
     *
     * Two things produce one. A socket that froze and then snapped forward — Infinite
     * Flight paused, the phone asleep, the link stalled — and the app returning from the
     * background, where the poll simply was not running. Either way the ground track
     * between the two fixes was never flown, so anything derived from crossing it is
     * fiction.
     *
     * The threshold is generous on purpose: three times the distance the reported
     * groundspeed could cover in the elapsed time, plus a nautical mile of slack for a
     * stationary aircraft and for clock jitter. Anything under that is ordinary motion.
     * Marked from the *reported* timestamps rather than wall time, so a slow tick and a
     * frozen socket are told apart.
     */
    private fun telemetryJumped(previous: AircraftState, current: AircraftState): Boolean {
        // Stamped before anything can return, so the clock keeps running through fixes
        // that carry no usable position. Recorded inside the guards it lagged a tick, and
        // the elapsed time for the fix that actually jumped read as zero.
        val previousMillis = lastTelemetryMillis
        lastTelemetryMillis = clock.nowMillis()

        val from = previous.coordinate?.takeIf { it.isValid } ?: return false
        val to = current.coordinate?.takeIf { it.isValid } ?: return false
        val elapsedSeconds = previousMillis
            ?.let { (clock.nowMillis() - it).coerceAtLeast(0L) / 1000.0 }
            ?: 0.0

        // A discontinuity is defined by time having passed without the motion being
        // reported. With no measurable elapsed time there is no basis for the judgement —
        // the first fix of a session, two fixes in the same tick — so it is not one.
        if (elapsedSeconds < MINIMUM_JUMP_ELAPSED_SECONDS) return false

        val speed = maxOf(previous.groundSpeed ?: 0.0, current.groundSpeed ?: 0.0)
        val plausibleNM = speed * (elapsedSeconds / 3600.0) * TELEMETRY_JUMP_FACTOR +
            TELEMETRY_JUMP_SLACK_NM
        val movedNM = Geo.distanceNM(from, to)
        if (movedNM <= plausibleNM) return false

        diagnostics.log(
            DiagnosticCategory.STATE,
            level = DiagnosticLevel.WARNING,
            message = "Telemetry jumped ${movedNM.roundToInt()} NM in " +
                "${elapsedSeconds.roundToInt()}s — resyncing",
        )
        return true
    }

    /** When the last usable fix arrived, for the jump test. */
    private var lastTelemetryMillis: Long? = null

    /**
     * The wind in force and the local declination, kept across ticks.
     *
     * Every piece of the arithmetic was ported into [HeadingSolver] and nothing ran it per
     * sample, so the weather-deviation vectors were assigned as raw true bearings — no
     * crab and no magnetic conversion. Approach intercepts and departure guidance already
     * applied the declination; the deviation legs, which are the ones the solver was
     * written for, applied neither.
     */
    private val windEstimator = WindEstimator(diagnostics)

    /**
     * The wind rows the Weather Diagnostics card prints.
     *
     * Exposed because those rows exist to be held up against Infinite Flight's own panel
     * when an assigned heading looks wrong, and that comparison only means anything if the
     * solved wind is solved *independently* of the one being steered by — so the panel
     * shows both, and the difference between them.
     */
    fun windDiagnostics(): WindEstimator = windEstimator

    /**
     * How the departure heading in the takeoff clearance was arrived at, or null before one
     * has been computed. Composed by [recordDepartureHeadingSummary]; the panel prints it
     * verbatim.
     */
    var departureHeadingSummary: String? = null
        private set

    /**
     * The departure field's elevation, captured from on-ground telemetry (MSL − AGL,
     * since there is no onboard elevation database), so the initial climb is a height
     * above the *field* rather than above sea level. At Denver a configured 5,000 ft
     * climb becomes 11,000 ft MSL instead of a sub-surface 5,000.
     *
     * Only ever taken from a snapshot that *reports* being on the ground. Falling back to
     * the detected phase looks equivalent but isn't: a half-read snapshot carries no
     * ground reference, so the phase detector holds the phase where it is — on the ground
     * for a departure — and this would then take the raw MSL, with no AGL to subtract, as
     * the field elevation. One such snapshot in the seconds after rotation would put the
     * field hundreds of feet up and raise every initial climb derived from it.
     */
    private fun captureDepartureFieldElevation(aircraft: AircraftState, previous: FlightSessionState) {
        if (previous.hasDeparted) return
        val reportedOnGround = aircraft.onGround ?: aircraft.altitudeAGL?.let { it < ON_GROUND_AGL_FT }
        if (reportedOnGround != true) return
        val msl = aircraft.altitudeMSL ?: return
        departureFieldElevationMSL = max(0.0, msl - (aircraft.altitudeAGL ?: 0.0))
    }

    /**
     * The configured initial climb is a height above the departure field, so it is added
     * to the field elevation and rounded up to the next thousand — a valid MSL altitude
     * at a high-elevation airport. At sea level the field is 0 and the value is unchanged.
     * Prefers the elevation captured on the ground before departure, falling back to the
     * current on-ground estimate when that capture has not run yet.
     */
    private fun elevationAwareInitialClimbFt(): Int {
        val climbAboveField =
            if (settings.initialClimbAltitudeFt > 0) settings.initialClimbAltitudeFt else DEFAULT_INITIAL_CLIMB_FT
        val aircraft = _state.value.aircraftState
        val fieldElevation = when {
            departureFieldElevationMSL > 0 -> departureFieldElevationMSL.roundToInt()
            aircraft.onGround == true && aircraft.altitudeMSL != null ->
                max(0.0, aircraft.altitudeMSL!! - (aircraft.altitudeAGL ?: 0.0)).roundToInt()
            else -> 0
        }
        return roundedUpToThousand(fieldElevation + climbAboveField)
    }

    fun ingestFlightPlan(plan: FlightPlan) {
        // A manual override wins over what Connect reports — but not over the pilot's own
        // next edit. The guard used to reject every incoming plan once the flag was set,
        // including the ones the pilot had just typed, so committing a callsign silently
        // discarded the gate typed after it. Connect never sets the flag (IFConnectManager
        // builds a plan with the default false), so rejecting only unflagged plans keeps
        // the simulator locked out exactly as before.
        if (_state.value.flightPlan.manualOverride && !plan.manualOverride) return
        _state.update { it.copy(flightPlan = plan) }
        recomputeDerivedState()
    }

    /**
     * Hand control of the flight plan back to Infinite Flight.
     *
     * Without this "Clear Overrides" could not work: it ingests an empty plan, which the
     * guard above refuses precisely because an override is latched, so the override
     * outlived the button meant to remove it — and with it every subsequent plan the
     * simulator published, for the rest of the flight.
     */
    fun clearManualOverride() {
        _state.update { it.copy(flightPlan = it.flightPlan.copy(manualOverride = false)) }
        recomputeDerivedState()
    }

    /**
     * Replace the plan outright, whatever the override flag says.
     *
     * This is the plan-sync path — the pilot's own saved fields, composed by
     * [FlightPlanComposer] — and it is the one caller that must never be refused. The
     * override guard on [ingestFlightPlan] exists to keep Infinite Flight from overwriting
     * a plan the pilot typed; a re-sync *is* what the pilot typed, so routing it through
     * that guard would mean a Mock Mode switch left the previous mode's route in place.
     */
    fun applyFlightPlan(plan: FlightPlan) {
        _state.update { it.copy(flightPlan = plan) }
        recomputeDerivedState()
    }

    /**
     * Apply a live-ATC staffing snapshot and log the two transitions worth logging.
     *
     * Ported from `AppModel.applyLiveATC(_:)` (IFATCCompanion/App/AppModel.swift:1410). The
     * two are deliberately separate: a human controller being *present* in the session says
     * nothing about which frequency the pilot is on, and only the second — being tuned to
     * that controller — is what makes the companion stand aside.
     */
    /**
     * Whether the pilot currently holds Live access.
     *
     * Carried on the session because every screen that has to lock a paid control reads
     * the session, not the billing client — and because a subscription that lapses
     * mid-flight has to take the lock with it rather than waiting for a relaunch.
     */
    fun setLiveAccess(hasLiveAccess: Boolean) {
        if (_state.value.hasLiveAccess == hasLiveAccess) return
        _state.update { it.copy(hasLiveAccess = hasLiveAccess) }
        recomputeDerivedState()
    }

    /**
     * Mirror the Infinite Flight link's state into the session.
     *
     * The session carries it rather than reading the manager directly because everything
     * that reads it — the ATC screen's header, the Live Update notification, the saved
     * flight's summary — reads one snapshot of the flight, and a second source would let
     * the two disagree by a frame.
     */
    fun applyConnectionState(connectionState: IFConnectConnectionState) {
        if (_state.value.connectionState == connectionState) return
        _state.update { it.copy(connectionState = connectionState) }
        recomputeDerivedState()
    }

    fun applyLiveATC(status: LiveATCStatus) {
        val previous = _state.value.liveATC
        _state.update { it.copy(liveATC = status) }
        if (status.humanControllerActive != previous.humanControllerActive) {
            diagnostics.log(
                DiagnosticCategory.ATC,
                message = if (status.humanControllerActive) {
                    val who = status.controllerName?.let { " ($it)" }.orEmpty()
                    "Human ATC online in session$who."
                } else {
                    "Human ATC no longer present in session."
                },
            )
        }
        if (status.companionShouldStandBy != previous.companionShouldStandBy) {
            diagnostics.log(
                DiagnosticCategory.ATC,
                message = if (status.companionShouldStandBy) {
                    val facility = status.tunedFacility?.title ?: status.tunedFrequencyName ?: "a controller"
                    "Tuned to human ATC ($facility) — companion standing by on this frequency."
                } else {
                    "Off the human-controlled frequency — companion resuming."
                },
            )
        }
        recomputeDerivedState()
    }

    // endregion

    // region The automatic flow

    /**
     * Drive the telemetry-triggered half of the conversation.
     *
     * Three things gate every automatic call, and all three are deliberate:
     *  - **The read-back gate.** A real controller waits for the pilot to read an
     *    instruction back before issuing the next one.
     *  - **Standby.** The companion never talks over a staffed human controller.
     *  - **Forward-only ordering.** The phase detector flickers near the ground, and
     *    without [AtcFlowOrder.isForward] the conversation would bounce back to an
     *    earlier call each time it did.
     */
    private fun advanceAutomaticFlow(aircraft: AircraftState, phase: FlightPhase) {
        val current = _state.value
        if (current.companionStandby) return
        if (readbackGate.isClosed && !settings.mockMode) return

        // The pre-departure ground sequence is pilot-driven end to end, so telemetry
        // never advances it. The exceptions are the monitor-Tower hand-off as the aircraft
        // nears the runway, and the takeoff clearance once it is actually lined up —
        // neither of which a real controller waits for a pilot prompt to issue.
        if (stateMachine.current.isManualGroundFlow) {
            maybeMonitorTowerHandoff()
            if (stateMachine.current == ATCState.LINE_UP_WAIT || !_state.value.monitoringTower) {
                maybeIssueTakeoffClearance(aircraft)
            } else {
                // The pilot is monitoring Tower — Ground already handed them off, so no
                // "ready" report or check-in is needed. Tower issues "line up and wait" as
                // they reach the hold-short, and the clearance follows once lined up.
                advanceWhileMonitoringTower(aircraft)
            }
            return
        }

        // After the pilot has called Ramp, walk the arrival in to the gate in stages so the
        // ramp calls never all fire at once: "monitor ramp to the gate" as the aircraft
        // slows toward a stop, then — a tick later — the block-in once it is actually
        // parked with the brake set. This runs even while manually tuned, since the pilot
        // drove the Ramp call themselves.
        if (current.awaitingGateArrival) {
            advanceGateArrival(aircraft)
            return
        }

        // A go-around has reset the conversation for another approach. Hold everything
        // automatic until the pilot re-establishes with Approach, so the climb away from
        // the runway is not immediately re-advanced back through the approach sequence.
        if (goAroundInProgress) {
            // The radio stays where the pilot left it — on Android `currentFacility` *is*
            // the tuned facility, so only the conversational cursor needs re-syncing.
            _state.update { it.copy(atcState = stateMachine.current) }
            return
        }

        val mapped = stateMachine.mappedState(phase)

        // Once the pilot has tuned a frequency by hand they are driving the radio, and a new
        // controller must not speak before they have arrived on its frequency. Mock Mode is
        // exempt: it has no live telemetry to drive a hand-off from, so the demo advances on
        // a button press.
        if (current.manualTuning && !settings.mockMode) {
            advanceSemiAutomatic(mapped, aircraft)
            _state.update { it.copy(atcState = stateMachine.current) }
            recomputeDerivedState()
            return
        }

        val previousState = stateMachine.current
        val target = adjustedAirborneTarget(mapped, aircraft)
        if (!AtcFlowOrder.isForward(target, previousState)) return
        advanceAndPost(target, automatic = true)

        // Once the approach is cleared and the aircraft is established, Approach hands the
        // pilot to Tower — instruction first, then the hand-off, the reverse of the usual
        // "contact … then instruction" order. So it is posted explicitly here rather than
        // through the generic facility-change hand-off, which the FINAL → LANDING step
        // then suppresses.
        if (previousState != ATCState.FINAL && stateMachine.current == ATCState.FINAL) {
            announceApproachToTowerHandoff()
        }
    }

    /**
     * The airborne flow while the pilot is tuning frequencies by hand.
     *
     * The controller's position-based calls still fire from telemetry — but a *change of
     * controller* only prompts "contact Center on 133.4" and then waits. The new controller
     * says nothing until the pilot tunes it and checks in.
     *
     * Without this the pilot heard the hand-off and Center's clearance back to back, before
     * they had touched the radio: the app told them to switch frequency and then talked to
     * them on the frequency they had not switched to yet. Same-controller continuations —
     * descend-via-STAR, cleared-approach, exit-the-runway — play on their own, because no
     * frequency change is involved.
     */
    private fun advanceSemiAutomatic(mapped: ATCState, aircraft: AircraftState) {
        val current = _state.value
        // Hold while the last instruction is unacknowledged, while waiting for a check-in on
        // a frequency the pilot was just handed to, or while a go-around is being flown.
        if (readbackGate.isClosed || current.pendingCheckInFacility != null || goAroundInProgress) return

        val previousState = stateMachine.current
        val target = adjustedAirborneTarget(mapped, aircraft)
        if (!AtcFlowOrder.isForward(target, previousState) || target == previousState) return

        val from = AtcFlowOrder.controller(previousState, current.currentFacility)
        val to = AtcFlowOrder.controller(target, current.currentFacility)

        if (to != from) {
            // Control passes to a new facility: prompt the hand-off and wait for the pilot to
            // switch frequency and check in before that controller speaks.
            val context = buildContext(previousState)
            post(
                engine.handoff(
                    cs = context.callsign,
                    from = from,
                    to = to,
                    frequency = frequencyFor(to, context),
                ),
                speakIt = true,
            )
            _state.update { it.copy(pendingCheckInFacility = to) }
            recomputeDerivedState()
            return
        }

        // The same controller keeps working the aircraft — issue the call now.
        advanceAndPost(target, automatic = true, announceHandoff = false)
        when {
            previousState != ATCState.FINAL && stateMachine.current == ATCState.FINAL -> {
                // The cleared-approach call hands the pilot to Tower; wait for them to tune
                // Tower and check in for the landing clearance.
                announceApproachToTowerHandoff()
                _state.update { it.copy(pendingCheckInFacility = ATCFacility.TOWER) }
            }
            stateMachine.current == ATCState.RUNWAY_EXIT -> {
                // The exit-the-runway call already tells the pilot to contact Ground; wait
                // for them to tune Ground and check in for the taxi-in.
                _state.update { it.copy(pendingCheckInFacility = ATCFacility.GROUND) }
            }
        }
        recomputeDerivedState()
    }

    /**
     * Refine the phase-derived target into the state the flight is actually at.
     *
     * The phase detector reports *physics* — climbing, approaching, on the ground. A
     * controller's flow has more steps than that: the same "approach" phase covers both
     * "descend, expect the ILS" and "cleared ILS approach", and the same "landing" phase
     * covers both "cleared to land" and "exit the runway, contact Ground". This ladder is
     * what turns one into the other, and each rung is a real procedural gate rather than a
     * timer.
     *
     * Ported from `AppModel.adjustedAirborneTarget(mapped:state:)`.
     */
    private fun adjustedAirborneTarget(mapped: ATCState, aircraft: AircraftState): ATCState {
        val altitude = aircraft.altitudeMSL ?: 0.0
        val ceiling = currentTraconCeilingFt.toDouble()
        val onGround = aircraft.onGround ?: false
        val runway = buildContext(stateMachine.current).runway
        val current = stateMachine.current

        // Hold Tower → Departure until the aircraft is through ~2,000 ft AGL. Handing off
        // the instant the wheels leave the ground clears the pilot "direct" to the first
        // filed fix while it is still the runway-end waypoint just ahead, and stacks the
        // departure call right on top of the takeoff clearance — so wait for the climb to
        // carry past it first. When Infinite Flight does not expose AGL, fall back to the
        // altitude gained since the takeoff clearance was issued.
        if (current == ATCState.TOWER_DEPARTURE &&
            (mapped == ATCState.INITIAL_CLIMB || mapped == ATCState.CLIMB)
        ) {
            val groundReference =
                if (departureFieldElevationMSL > 0) departureFieldElevationMSL else liftoffAltitudeMSL
            val agl = aircraft.altitudeAGL ?: max(0.0, altitude - groundReference)
            if (agl < DEPARTURE_HANDOFF_AGL_FT) return ATCState.TOWER_DEPARTURE
        }

        // Departure hands off to Center 1,000 ft below the TRACON ceiling (17,000 ft for a
        // FL180 ceiling) rather than right at it. That buffer gives the pilot time to check
        // in with Center and be cleared to the next altitude before the climb reaches the
        // ceiling, so it continues past FL180 without pausing.
        if (mapped == ATCState.CLIMB && altitude < ceiling - CENTER_HANDOFF_BUFFER_FT &&
            current in DEPARTURE_WORKED_STATES
        ) {
            return ATCState.INITIAL_CLIMB
        }

        // Top of descent: leaving cruise, Center issues the descend-via-STAR (or plain
        // descend) first, before any Approach hand-off.
        if (current in CRUISE_STATES && (mapped == ATCState.DESCENT || mapped == ATCState.APPROACH)) {
            return ATCState.DESCENT
        }

        // Descending through the TRACON ceiling, or arriving in the terminal area, Center
        // hands the aircraft to Approach.
        if (current == ATCState.DESCENT && (mapped == ATCState.DESCENT || mapped == ATCState.APPROACH)) {
            return if (mapped == ATCState.APPROACH || altitude < ceiling - TERMINAL_ENTRY_BUFFER_FT) {
                ATCState.APPROACH
            } else {
                ATCState.DESCENT
            }
        }

        // Approach clears the approach once the aircraft is established — APPR engaged, or
        // lined up on final and wings level — before the Tower hand-off. This must follow
        // the "descend, expect approach" call.
        if (current == ATCState.APPROACH && isEstablishedOnApproach(aircraft, runway)) {
            return ATCState.FINAL
        }
        // Cleared to land (Tower) on short final or at touchdown.
        if (current == ATCState.FINAL && (onGround || isOnShortFinal(aircraft))) {
            return ATCState.LANDING
        }
        // After touchdown, Tower instructs the pilot to exit the runway and contact Ground.
        if (current == ATCState.LANDING && onGround) {
            return ATCState.RUNWAY_EXIT
        }
        // Once clear of the runway / at taxi speed, switch to Ground and taxi in.
        if (current == ATCState.RUNWAY_EXIT) {
            val groundSpeed = aircraft.groundSpeed ?: 0.0
            return if (onGround && groundSpeed < RUNWAY_VACATED_GROUND_SPEED) {
                ATCState.GROUND_ARRIVAL
            } else {
                ATCState.RUNWAY_EXIT
            }
        }
        return mapped
    }

    /** TRACON ceiling in feet, where Departure hands off to Center. */
    private val currentTraconCeilingFt: Int
        get() = if (settings.traconCeilingFL > 0) settings.traconCeilingFL * 100 else DEFAULT_TRACON_CEILING_FT

    /**
     * Whether the aircraft is established on the approach: the autopilot approach mode
     * (APPR) is engaged, or it is lined up on final with the runway. Read from Infinite
     * Flight telemetry; the mock feed simulates APPR on the approach phase.
     */
    private fun isEstablishedOnApproach(aircraft: AircraftState, runway: String): Boolean {
        if (aircraft.onGround == true) return false
        if (aircraft.approachModeEngaged == true) return true
        if (!lineupDetector.isOnFinalApproach(aircraft, runway)) return false
        // Lined up with the runway and not still turning onto final (wings roughly level).
        val bank = aircraft.bankAngle
        if (bank != null && abs(bank) > WINGS_LEVEL_BANK_DEGREES) return false
        return true
    }

    /** Whether the aircraft is on short final: airborne, low, and descending. */
    private fun isOnShortFinal(aircraft: AircraftState): Boolean {
        if (aircraft.onGround == true) return false
        val agl = aircraft.altitudeAGL ?: aircraft.altitudeMSL ?: 0.0
        val verticalSpeed = aircraft.verticalSpeed ?: 0.0
        return agl < SHORT_FINAL_AGL_FT && verticalSpeed < SHORT_FINAL_DESCENT_FPM
    }

    /**
     * After the approach is cleared (aircraft established), Approach hands the pilot off to
     * Tower for the landing clearance.
     */
    private fun announceApproachToTowerHandoff() {
        val c = buildContext(ATCState.FINAL)
        val tx = engine.handoff(
            cs = c.callsign,
            from = ATCFacility.APPROACH,
            to = ATCFacility.TOWER,
            frequency = c.towerFrequency,
        )
        post(tx, speakIt = true)
        // This hand-off follows the cleared-approach call that just closed the gate.
        // Re-aim the gate at the hand-off so the pilot reads back "contacting Tower" (the
        // last message) and the controller does not nag "how do you read?".
        readbackGate.soften(tx)
    }

    /**
     * Tower clears the aircraft for takeoff once it is lined up on the runway, or already
     * rolling, or telemetry reports the takeoff phase. No pilot prompt is needed.
     */
    /**
     * As the aircraft nears the departure runway, Ground hands it to Tower to *monitor* —
     * "monitor Tower on 118.3", the red sign short of the runway.
     *
     * Fires once per departure taxi, the moment the surface flags the aircraft approaching
     * the hold-short. No check-in is required afterwards: the read-back tunes the radio to
     * Tower when auto-tune is on, and the takeoff clearance still plays once the aircraft
     * is lined up.
     *
     * Every one of the guards is load-bearing. The hand-off must not cut across a taxi or
     * crossing clearance the pilot has not answered — that is how a pilot ends up switching
     * frequency mid-instruction — and it must not fire on the arrival surface, which is
     * loaded at the same time.
     */
    private fun maybeMonitorTowerHandoff() {
        val current = _state.value
        if (current.monitoringTower || current.hasDeparted || current.companionStandby) return
        if (stateMachine.current != ATCState.GROUND_TAXI) return
        // `workingFacility`, not `currentFacility`: iOS keeps the tuned radio in a separate
        // `tunedFacility` and lets `currentFacility` follow the state machine, so its
        // `currentFacility == .ground` means "Ground is working this aircraft". On Android
        // `currentFacility` *is* the radio, which after the Ramp → Ground hand-off is still
        // on Ramp until the pilot tunes — so the same question is `workingFacility`.
        if (current.workingFacility != ATCFacility.GROUND) return
        if (readbackGate.isClosed) return

        val signals = groundHandoffSignals()
        if (!signals.isDepartureSurface || !signals.approachingRunwayHandoff) return
        if (signals.awaitingCrossingReadback || signals.awaitingTaxiReadback) return

        // Ground has just moved the aircraft to Tower, so any check-in still owed to Ground
        // is moot. Left standing it would keep `workingFacility` on Ground for the rest of
        // the taxi, and a pilot who then calls Tower up would be answered as though they
        // were calling Ground.
        _state.update { it.copy(monitoringTower = true, pendingCheckInFacility = null) }
        val context = buildContext(ATCState.LINE_UP_WAIT)
        post(engine.monitorTower(context.callsign, context.towerFrequency), speakIt = true)
        recomputeDerivedState()
    }

    /**
     * While the pilot is monitoring Tower, Tower speaks first.
     *
     * Nearing the runway it issues "line up and wait" so the aircraft can roll straight on.
     * That call is deliberately non-gating — the pilot is monitoring and need not read it
     * back for the flow to proceed — and the hand-off is suppressed, because the
     * monitor-Tower call already moved them there.
     */
    private fun advanceWhileMonitoringTower(aircraft: AircraftState) {
        val runway = buildContext(stateMachine.current).runway
        val onRunway = lineupDetector.isLinedUp(aircraft, runway) ||
            lineupDetector.isDepartingRoll(aircraft, runway) ||
            _state.value.phase == FlightPhase.TAKEOFF
        if (!onRunway && groundHandoffSignals().approachingRunwayLineup) {
            advanceAndPost(ATCState.LINE_UP_WAIT, automatic = false, announceHandoff = false)
        } else {
            // Already on the runway, or not yet within line-up range: let the takeoff
            // clearance fire once the aircraft is actually lined up.
            maybeIssueTakeoffClearance(aircraft)
        }
    }

    /**
     * Tower's takeoff clearance, once the aircraft is actually on the runway.
     *
     * Three cases, as iOS has them:
     *
     * - **Already rolling.** The aircraft has begun its take-off run, so the clearance is
     *   overdue; issue it at once.
     * - **Lined up and stopped.** Arm a short countdown and clear only when it expires and
     *   the aircraft is *still* lined up. A real controller does not key the mic the
     *   instant the nose swings onto the centreline; Android's did, so the clearance
     *   arrived mid-turn, before the aircraft had settled.
     * - **Neither.** Still manoeuvring onto the runway — disarm, so a pilot who lines up,
     *   thinks better of it and taxis clear is not cleared for take-off from the taxiway.
     *
     * The countdown is a clock reading rather than a timer because this runs off the 1 Hz
     * telemetry tick: every tick re-checks the conditions, which is exactly the re-check
     * iOS does when its timer fires, and it cannot fire against a stale picture.
     */
    private fun maybeIssueTakeoffClearance(aircraft: AircraftState) {
        if (stateMachine.current != ATCState.LINE_UP_WAIT && !_state.value.monitoringTower) return
        val runway = buildContext(stateMachine.current).runway
        val rolling = lineupDetector.isDepartingRoll(aircraft, runway) ||
            _state.value.phase == FlightPhase.TAKEOFF
        val linedUp = lineupDetector.isLinedUp(aircraft, runway)

        if (!rolling && !linedUp) {
            takeoffClearanceArmedAtMillis = null
            return
        }

        if (!rolling) {
            val stopped = (aircraft.onGround ?: true) &&
                (aircraft.groundSpeed ?: 0.0) < LINED_UP_STOPPED_GROUND_SPEED
            if (!stopped) {
                // Lined up and still moving: rolling into position, not holding on it.
                takeoffClearanceArmedAtMillis = null
                return
            }
            val armedAt = takeoffClearanceArmedAtMillis
            if (armedAt == null) {
                takeoffClearanceArmedAtMillis = clock.nowMillis()
                return
            }
            if (clock.nowMillis() - armedAt < TAKEOFF_CLEARANCE_DELAY_MILLIS) return
            if (_state.value.hasDeparted) {
                takeoffClearanceArmedAtMillis = null
                return
            }
        }

        takeoffClearanceArmedAtMillis = null
        // When Ground already handed the pilot to Tower to monitor, that call *was* the
        // hand-off, so the clearance must not re-announce a "contact Tower".
        advanceAndPost(
            ATCState.TOWER_DEPARTURE,
            automatic = true,
            announceHandoff = !_state.value.monitoringTower,
        )
    }

    /** When the aircraft first settled lined up and stopped, or null if it has not. */
    private var takeoffClearanceArmedAtMillis: Long? = null

    /**
     * Advance the state machine and post the resulting controller call, preceded by a
     * "contact …" hand-off whenever the controlling facility changes.
     */
    fun advanceAndPost(
        target: ATCState,
        automatic: Boolean = false,
        announceHandoff: Boolean = true,
        speakIt: Boolean = true,
        overrideTransmission: ATCTransmission? = null,
    ) {
        val current = _state.value

        // The arrival only completes once the aircraft is parked at the gate. Never
        // advance to PARKED otherwise, so a stop out on a taxiway cannot end the flight.
        if (target == ATCState.PARKED && !isParkedAtGate(current.aircraftState)) {
            syncStateMachine()
            return
        }

        val previous = stateMachine.current
        val context = buildContext(target)
        val stateTx = stateMachine.advance(target, context)
        if (stateTx == null && overrideTransmission == null) {
            syncStateMachine()
            return
        }
        val tx = overrideTransmission ?: stateTx!!

        val fromFacility = AtcFlowOrder.controller(previous, current.currentFacility)
        val toFacility = AtcFlowOrder.controller(target, current.currentFacility)

        // Announce a hand-off only between two established controllers — not the very
        // first contact, and not Clearance (which is the initial call-up) or Ramp (whose
        // hand-off is already issued at the end of the IFR clearance). The runway-exit
        // call already says to contact Ground, and the Approach → Tower hand-off is
        // issued when the approach is cleared, so neither is repeated here.
        val firstContact = previous == ATCState.NOT_CONNECTED || previous == ATCState.CONNECTED_IDLE
        val shouldAnnounce = announceHandoff &&
            !firstContact &&
            previous != ATCState.RUNWAY_EXIT &&
            previous != ATCState.FINAL &&
            fromFacility != toFacility &&
            // Telling a pilot to contact the frequency they are already on is noise. It
            // happens after the ramp controller's own hand-off to Ground: the pilot moves
            // the radio, asks Ground for the taxi clearance, and the state machine —
            // still seeing the previous state as a Ramp one — would announce the hand-off
            // a second time.
            current.currentFacility != toFacility &&
            toFacility != ATCFacility.CLEARANCE &&
            toFacility != ATCFacility.RAMP &&
            current.latestTransmission != null

        if (shouldAnnounce) {
            post(
                engine.handoff(
                    cs = context.callsign,
                    from = fromFacility,
                    to = toFacility,
                    frequency = frequencyFor(toFacility, context),
                ),
                speakIt,
            )
            _state.update { it.copy(pendingCheckInFacility = toFacility) }
        }

        // Capture the field altitude at takeoff so the climb-out hand-off can be held
        // until the aircraft is well above the runway.
        if (target == ATCState.TOWER_DEPARTURE) {
            liftoffAltitudeMSL = current.aircraftState.altitudeMSL ?: 0.0
            _state.update { it.copy(hasDeparted = true) }
        }

        updateAssignedAltitude(target, context)
        post(tx, speakIt)

        // An automatic call that carries a read-back instruction closes the gate: the
        // flow holds here until the pilot reads back. Pilot-driven advances never close
        // it — the pilot is already driving. The takeoff clearance holds the gate too (so
        // the Departure hand-off cannot stack on it) but must NOT arm the idle nag: a
        // controller does not radio-check a pilot it has just cleared for takeoff.
        if (automatic && target.expectsReadback && !settings.mockMode) {
            readbackGate.engage(tx, promptIfIdle = target != ATCState.TOWER_DEPARTURE)
        }

        syncStateMachine()
    }

    // endregion

    // region Pilot actions

    /** The pilot read back the last controller call. */
    fun readBack() {
        val current = _state.value
        val readback = current.latestTransmission?.readback

        // A pending simulated runway-crossing clearance: reading it back is what authorizes
        // the crossing, and never before. The crossing clearance carries its own read-back.
        if (groundHandoffSignals().awaitingCrossingReadback) {
            if (readback != null) {
                post(
                    ATCTransmission.create(
                        sender = ATCTransmission.Sender.PILOT,
                        facility = readback.facility,
                        displayText = readback.displayText,
                        spokenText = readback.spokenText,
                        timestampMillis = clock.nowMillis(),
                    ),
                    speakIt = shouldSpeakPilot(settings),
                )
            }
            authorizeCrossing()
            return
        }

        val tx = if (readback != null) {
            ATCTransmission.create(
                sender = ATCTransmission.Sender.PILOT,
                facility = readback.facility,
                displayText = readback.displayText,
                spokenText = readback.spokenText,
                timestampMillis = clock.nowMillis(),
            )
        } else {
            pilotEngine.readback(stateMachine.current, buildContext(stateMachine.current))
        }
        post(tx, speakIt = shouldSpeakPilot(settingsProvider()))

        // Reading a hand-off back tunes the radio to the new controller, when auto-tune
        // is on. This is the only place the radio moves without the pilot tapping.
        readback?.tuneTo?.let { facility ->
            if (settings.autoTuneOnHandoff) tuneTo(facility, manual = false)
        }
    }

    /**
     * "Say again" — and then the controller actually says it again.
     *
     * Re-posting the last controller call is the whole point of the button. Without it the
     * pilot asks for a repeat and the frequency stays silent, which is worse than not
     * offering the button at all: they now believe they missed it twice.
     *
     * A copy is posted rather than the original transmission, so the transcript shows two
     * calls and the repeat carries its own read-back for the gate to key on.
     */
    fun sayAgain() {
        if (_state.value.companionStandby) return
        postPilot { pilotEngine.sayAgain(it, _state.value.workingFacility) }
        val last = _state.value.lastControllerCall ?: return
        post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.ATC,
                facility = last.facility,
                displayText = last.displayText,
                spokenText = last.spokenText,
                timestampMillis = clock.nowMillis(),
                readback = last.readback,
            ),
            speakIt = true,
            allowRepeat = true,
        )
    }

    /**
     * "Unable" — and the controller answers with something the pilot can actually fly.
     *
     * A deterministic alternative rather than silence: the controller holds the higher of
     * the current assignment and the initial-climb altitude and asks the pilot to advise
     * when able. Silence after "unable" leaves the aircraft with a clearance it has just
     * refused and no replacement, which is the one state the app must never leave a pilot in.
     */
    fun unable() {
        if (_state.value.companionStandby) return
        val context = buildContext(stateMachine.current)
        postPilot { pilotEngine.unable(it, _state.value.workingFacility) }
        val facility = _state.value.workingFacility
        val target = maxOf(_state.value.assignedAltitude, context.initialClimbAltitude)
        val display = engine.formatAltDisplay(target)
        val spoken = engine.spokenAltitude(target)
        post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.ATC,
                facility = facility,
                displayText = "${context.callsign.display}, roger, maintain $display, advise able to comply.",
                spokenText = "${context.callsign.spoken}, roger, maintain $spoken, advise able to comply.",
                timestampMillis = clock.nowMillis(),
                readback = ATCTransmission.Readback(
                    displayText = "Maintain $display, ${context.callsign.display}.",
                    spokenText = "Maintain $spoken, ${context.callsign.spoken}.",
                    facility = facility,
                ),
            ),
            speakIt = true,
        )
    }

    fun checkIn() {
        // The guard belongs here and not only in performPilotAction: the UI calls this
        // directly, and so does the spoken-command path, so a staffed controller could
        // otherwise be talked over by a check-in the pilot pressed a button for.
        if (_state.value.companionStandby) return
        val current = _state.value
        val facility = current.workingFacility

        // Re-establishing with Approach on the missed-approach leg. Handled before the
        // generic check-in so it is not collapsed into a plain radar-contact re-check-in,
        // which would leave the aircraft in the pattern with no clearance to come back.
        if (goAroundInProgress && facility == ATCFacility.APPROACH) {
            resumeApproachAfterGoAround()
            return
        }

        // Monitoring Tower before departure. The hand-off already happened — Ground told
        // the pilot to monitor, not to check in — so a pilot who calls up anyway (typically
        // well before the runway) gets "you're number one for departure" and nothing else.
        // The state machine is deliberately left at the taxi state, so the takeoff clearance
        // still comes only once the aircraft is lined up on the runway.
        if (current.monitoringTower && !current.hasDeparted && facility == ATCFacility.TOWER) {
            val context = buildContext(ATCState.LINE_UP_WAIT)
            post(
                checkInCall(
                    c = context,
                    facility = ATCFacility.TOWER,
                    currentAltitude = checkInAltitude(),
                    targetAltitude = current.assignedAltitude,
                    onGround = current.aircraftState.onGround ?: true,
                ),
                speakIt = shouldSpeakPilot(settings),
            )
            post(engine.numberOneForTakeoff(context.callsign, context.runway), speakIt = true)
            _state.update { it.copy(pendingCheckInFacility = null) }
            recomputeDerivedState()
            return
        }

        // Calling up a new enroute sector after a Center-to-Center hand-off. The pilot is
        // announcing themselves on a new frequency, not asking for the next clearance, so
        // the conversation must not advance — falling through would answer a cruise check-in
        // with the top-of-descent call, because descent is the next Center state after
        // cruise.
        if (awaitingCenterSectorCheckIn && facility == ATCFacility.CENTER) {
            awaitingCenterSectorCheckIn = false
            val context = buildContext(stateMachine.current)
            post(
                checkInCall(
                    c = context,
                    facility = ATCFacility.CENTER,
                    currentAltitude = checkInAltitude(),
                    targetAltitude = current.assignedAltitude,
                    onGround = current.aircraftState.onGround ?: false,
                ),
                speakIt = shouldSpeakPilot(settings),
            )
            post(engine.radarContact(cs = context.callsign, facility = ATCFacility.CENTER), speakIt = true)
            _state.update { it.copy(pendingCheckInFacility = null) }
            recomputeDerivedState()
            return
        }

        // Checking in satisfies any hand-off the controller prompted: the new controller now
        // speaks for itself, so the semi-automatic flow resumes.
        _state.update { it.copy(pendingCheckInFacility = null) }

        // What this frequency has for the pilot next. Nothing ahead means a plain call-up,
        // which is answered with radar contact rather than with nothing — a check-in that
        // goes unanswered is the pilot talking to a frequency that does not exist.
        val target = AtcFlowOrder.nextStateWorkedBy(
            facility = facility,
            current = stateMachine.current,
            fallback = current.currentFacility,
        )
        if (target == null || target == stateMachine.current) {
            val context = buildContext(stateMachine.current)
            post(
                checkInCall(
                    c = context,
                    facility = facility,
                    currentAltitude = checkInAltitude(),
                    targetAltitude = current.assignedAltitude,
                    onGround = current.aircraftState.onGround ?: false,
                ),
                speakIt = shouldSpeakPilot(settings),
            )
            post(engine.radarContact(cs = context.callsign, facility = facility), speakIt = true)
            recomputeDerivedState()
            return
        }

        if (!target.isManualGroundFlow) _state.update { it.copy(hasDeparted = true) }
        val context = buildContext(target)
        // The pilot reports altitude against the assignment still in force — the previous
        // controller's. `advanceAndPost` updates it afterwards, so building the call first
        // is what keeps "with you at eight thousand" true when it is said.
        post(
            checkInCall(
                c = context,
                facility = facility,
                currentAltitude = checkInAltitude(),
                targetAltitude = current.assignedAltitude,
                onGround = current.aircraftState.onGround ?: false,
            ),
            speakIt = shouldSpeakPilot(settings),
        )
        // announceHandoff = false: the pilot moved the radio themselves, so the controller
        // answers directly rather than opening with a "contact …" they have already acted on.
        val before = _state.value.transcript.size
        advanceAndPost(target, announceHandoff = false)
        if (_state.value.transcript.size == before) {
            // The advance had nothing to say — the state machine has already made that
            // call, or the target is not ahead of where the conversation is. The pilot
            // still called up, so the frequency still answers: silence after a check-in
            // reads as a dead app, and the pilot has no way to tell it from a missed reply.
            post(engine.radarContact(cs = context.callsign, facility = facility), speakIt = true)
        }
        recomputeDerivedState()
    }

    /**
     * At a field with a ramp layer, Taxi on the Ramp frequency is a hand-off, not a
     * clearance.
     *
     * "Push complete, ready to taxi" / "proceed to the movement-area boundary, contact
     * Ground on 121.8 at spot 5" — then the radio moves to Ground and the pilot asks *them*
     * for the taxi clearance. Two steps, because that is how a ramp-controlled field
     * actually works and because the ramp controller has no authority over the movement
     * area they would otherwise be clearing the aircraft into.
     *
     * Deliberately no state advance: the conversation is still at the same point, only the
     * frequency has changed. Returns true when it handled the tap.
     */
    private fun handOffDepartureRampToGround(context: ATCContext, current: FlightSessionState): Boolean {
        if (current.hasDeparted) return false
        if (current.currentFacility != ATCFacility.RAMP) return false
        if (context.rampProfile.rampType == RampType.NONE) return false

        post(rampEngine.pushComplete(context.callsign), speakIt = shouldSpeakPilot(settingsProvider()))
        post(
            rampEngine.contactGround(
                cs = context.callsign,
                groundFrequency = context.groundFrequency,
                spot = context.rampSpot,
            ),
            speakIt = true,
        )
        tuneTo(ATCFacility.GROUND, manual = false)
        return true
    }

    /**
     * The pilot's check-in call, carrying the ATIS information code on the first Approach
     * check-in of an arrival — "…with you at seven thousand, information Bravo".
     *
     * Only Approach: on departure the code goes on the taxi request instead, and reporting
     * it twice on one leg is exactly what the ATIS session's one-shot bookkeeping prevents.
     */
    private fun checkInCall(
        c: ATCContext,
        facility: ATCFacility,
        currentAltitude: Int?,
        targetAltitude: Int,
        onGround: Boolean,
    ): ATCTransmission {
        val tx = pilotEngine.requestHandoff(
            c = c,
            facility = facility,
            currentAltitude = currentAltitude,
            targetAltitude = targetAltitude,
            onGround = onGround,
        )
        if (facility != ATCFacility.APPROACH) return tx
        return ATISSession.appendingATISInfo(tx, weatherAnswers.atisInfoWord(arriving = true))
    }

    /**
     * The altitude a pilot reports checking in: live MSL to the nearest hundred, or null
     * when there is no usable reading.
     *
     * Null and zero are not the same thing here. A null routes the phraseology to its
     * "checking in" branch, while a zero reports the aircraft level at sea level — which is
     * what this said before, on every check-in where telemetry had not arrived yet.
     */
    private fun checkInAltitude(): Int? {
        val msl = _state.value.aircraftState.altitudeMSL ?: return null
        if (msl <= 0) return null
        return (msl / 100).roundToInt() * 100
    }

    /**
     * The pilot goes around.
     *
     * Tower vectors the aircraft onto the crosswind leg at pattern altitude and hands it to
     * Approach; the automatic flow then holds until the pilot checks in there. The state
     * machine is deliberately left where it was — the radio stays on Tower until the pilot
     * reads back and switches, which is what the read-back's `tuneTo` does.
     */
    fun goAround() {
        if (_state.value.companionStandby) return
        val context = buildContext(ATCState.APPROACH)
        // A go-around supersedes any read-back still pending on the landing clearance:
        // the clearance it was answering no longer exists.
        readbackGate.reset()
        postPilot { pilotEngine.goAround(it) }

        // Pattern altitude is 3,000 ft above the field in MSL, rounded up to the next
        // thousand — the terminal fallback the context already computes, so a high field
        // does not get a pattern that flies into it.
        val patternAltitude = context.approachDefaultAltitude
        val runway = context.approachProcedure?.runway ?: context.runway
        val heading = GoAroundPattern.crosswindHeading(
            runwayHeading = RunwayDatabase.headingForRunway(runway)?.roundToInt()
                ?: GoAroundPattern.FALLBACK_RUNWAY_HEADING,
            leftTraffic = GoAroundPattern.LEFT_TRAFFIC,
        )
        post(
            engine.goAround(
                cs = context.callsign,
                runway = runway,
                leftTraffic = GoAroundPattern.LEFT_TRAFFIC,
                crosswindHeading = heading,
                patternAltitude = patternAltitude,
                approachFrequency = context.approachFrequency,
            ),
            speakIt = true,
        )
        goAroundInProgress = true
        _state.update { it.copy(assignedAltitude = patternAltitude) }
        recomputeDerivedState()
    }

    /**
     * The pilot re-establishes with Approach after a go-around.
     *
     * Approach holds the pattern altitude Tower assigned and clears the aircraft to continue
     * inbound, and the conversation is rewound to the approach — so the whole
     * cleared-approach → Tower → cleared-to-land sequence replays from here, driven by the
     * same established-on-final detection as the first time. That replay is the point: a
     * go-around that ended the flight's ATC would leave the pilot in a pattern with nobody
     * to talk to.
     */
    private fun resumeApproachAfterGoAround() {
        goAroundInProgress = false
        val current = _state.value
        val context = buildContext(ATCState.APPROACH)
        post(
            checkInCall(
                c = context,
                facility = ATCFacility.APPROACH,
                currentAltitude = checkInAltitude(),
                targetAltitude = current.assignedAltitude,
                onGround = current.aircraftState.onGround ?: false,
            ),
            speakIt = shouldSpeakPilot(settings),
        )
        post(
            engine.continueInbound(
                cs = context.callsign,
                altitude = current.assignedAltitude,
                procedure = context.approachProcedure,
                approach = context.approachName,
                runway = context.runway,
            ),
            speakIt = true,
        )
        stateMachine.restore(ATCState.APPROACH)
        _state.update {
            it.copy(
                atcState = ATCState.APPROACH,
                // iOS clears its separate `tunedFacility` here; on Android the radio and
                // the working facility are the same field, so pointing it at Approach is
                // both halves of that.
                currentFacility = ATCFacility.APPROACH,
                pendingCheckInFacility = null,
            )
        }
        recomputeDerivedState()
    }

    /**
     * Perform one of the pilot response-button actions.
     *
     * Every one of them goes through here rather than being wired straight from the
     * button, so the standby guard and the read-back gate apply uniformly — the UI does
     * not have to know that a staffed controller silences the app, or that a pilot
     * transmission opens the gate.
     */
    fun performPilotAction(action: PilotAction) {
        if (_state.value.companionStandby) return
        val context = buildContext(stateMachine.current)
        val current = _state.value

        val tx = when (action) {
            PilotAction.CLEARANCE -> pilotEngine.requestClearance(context)
            PilotAction.PUSHBACK -> pilotEngine.requestPushback(context)
            PilotAction.ENGINE_START -> pilotEngine.requestEngineStart(context)
            PilotAction.TAXI -> {
                // At a field with a ramp layer, Taxi on the Ramp frequency is not a taxi
                // request at all: the ramp controller reports the push complete and hands
                // the aircraft to Ground at the movement-area boundary, and the pilot then
                // asks Ground for the clearance. Android used to answer it in one step,
                // with Ramp itself issuing a Ground taxi clearance.
                if (handOffDepartureRampToGround(context, current)) return
                // "…request taxi, information Alpha". One-shot: the code is only reported
                // once per leg, so a second taxi request is bare.
                ATISSession.appendingATISInfo(
                    pilotEngine.requestTaxi(context),
                    weatherAnswers.atisInfoWord(arriving = false),
                )
            }
            PilotAction.READY -> pilotEngine.readyForDeparture(context)
            PilotAction.TAKEOFF -> pilotEngine.requestTakeoff(context)
            PilotAction.REQUEST_HIGHER -> {
                altitudeRequest(context, higher = true)
                return
            }
            PilotAction.REQUEST_LOWER -> {
                altitudeRequest(context, higher = false)
                return
            }
            // Both are inherently arrival actions, so the context is forced onto the
            // arrival side even if the conversational state has not caught up — otherwise
            // the runway, the approach and the wind would all come from the departure end.
            PilotAction.VECTORS -> {
                requestVectors(buildContext(stateMachine.current, arrivalOverride = true))
                return
            }
            PilotAction.APPROACH -> {
                requestApproach(buildContext(stateMachine.current, arrivalOverride = true))
                return
            }
            PilotAction.RIDE_REPORT -> {
                requestRideReport(context)
                return
            }
            PilotAction.DEST_WX -> {
                requestDestinationWeather(context)
                return
            }
            PilotAction.ACCEPT_SMOOTHER_ALTITUDE -> {
                acceptSmootherAltitude(context)
                return
            }
            PilotAction.GO_AROUND -> {
                // Not just the pilot's call: the go-around is a whole exchange — Tower's
                // pattern vector, the pattern altitude, the hold on the automatic flow, and
                // the replay when the pilot re-establishes. Posting only the pilot half is
                // what left the aircraft climbing away with nobody answering.
                goAround()
                return
            }
            PilotAction.CHECK_IN -> {
                checkIn()
                return
            }
            PilotAction.TO_GATE -> {
                contactRamp()
                return
            }
        }

        post(tx, speakIt = shouldSpeakPilot(settings))
        onPilotRequest(action)
    }

    /**
     * The controller's answer to a pilot request. The pilot-driven ground sequence
     * advances the state machine directly — that is what keeps it from skipping a phase —
     * while the airborne requests are answered by the controller's own call.
     */
    private fun onPilotRequest(action: PilotAction) {
        val target = when (action) {
            PilotAction.CLEARANCE -> ATCState.CLEARANCE
            PilotAction.PUSHBACK -> ATCState.PUSHBACK
            PilotAction.ENGINE_START -> ATCState.ENGINE_START
            PilotAction.TAXI -> ATCState.GROUND_TAXI
            PilotAction.READY -> ATCState.LINE_UP_WAIT
            PilotAction.TAKEOFF -> ATCState.TOWER_DEPARTURE
            else -> return
        }
        // Pilot-driven: never closes the read-back gate, because the pilot is already
        // driving the conversation.
        advanceAndPost(target, automatic = false)
    }

    // region Weather deviation

    /**
     * One tick of the weather-deviation flow, and whatever the controller says off its own
     * bat: the auto-issued advisory, a turn as the aircraft reaches a vertex of the reroute,
     * the auto-resume at the rejoin.
     */
    private fun updateWeatherDeviation(aircraft: AircraftState, phase: FlightPhase) {
        val flow = weatherDeviation ?: return
        emit(flow.update(weatherDeviationInputs(aircraft, phase)))
    }

    /** A tap on one of the weather response card's buttons. */
    /**
     * The pilot tapped the ATC screen's weather banner.
     *
     * iOS asks the working controller about the weather ahead — the deviation flow's own
     * "ask Center" call, which names the precipitation, its distance and its clock
     * position, and answers "no significant precipitation along your route at this time"
     * when there is nothing there. Android sent a *ride report* request instead: a
     * different question, answered with turbulence rather than with the weather the banner
     * is warning about.
     *
     * Falls back to the ride report when there is no deviation flow attached, because a
     * banner that does nothing when tapped is worse than one that answers the wrong
     * question.
     */
    fun contactAtcAboutWeather() {
        if (weatherDeviation == null) {
            performPilotAction(PilotAction.RIDE_REPORT)
            return
        }
        performWeatherDeviationAction(WeatherDeviationAction.ASK_CENTER)
    }

    fun performWeatherDeviationAction(action: WeatherDeviationAction) {
        val flow = weatherDeviation ?: return
        if (_state.value.companionStandby) return
        val current = _state.value
        emit(flow.perform(action, weatherDeviationInputs(current.aircraftState, current.phase)))
    }

    private fun weatherDeviationInputs(aircraft: AircraftState, phase: FlightPhase): WeatherDeviationController.Inputs {
        val current = _state.value
        return WeatherDeviationController.Inputs(
            plan = current.flightPlan,
            aircraft = aircraft,
            phase = phase,
            atcState = stateMachine.current,
            currentFacility = current.currentFacility,
            hasDeparted = current.hasDeparted,
            companionStandby = current.companionStandby,
            assignedAltitude = current.assignedAltitude,
            overlay = weatherAnswers.radarOverlay(),
            routeSigmets = weatherAnswers.routeSigmets(),
            // Mock Mode's cells are set synchronously, so they are always ready. A live
            // sample is ready once it has actually produced cells — freezing the locked
            // reroute set before then is what leaves the mint lines missing until a manual
            // refresh, because the first recompute of a flight routinely runs before the
            // first radar frame has landed.
            radarCellsReady = settings.mockMode || weatherAnswers.radarOverlay().sampledCells.isNotEmpty(),
            headings = windEstimator,
        )
    }

    /**
     * Put the flow's transmissions on the frequency.
     *
     * A controller-initiated call — one ATC makes on its own, with no pilot request waiting
     * on an answer — is held when it would only repeat a call the pilot has already
     * acknowledged; those are the ones that can come out verbatim-identical back to back. A
     * reply to a pilot request is always transmitted: a request left unanswered reads as a
     * dropped call.
     */
    private fun emit(emission: WeatherDeviationController.Emission) {
        if (emission.transmissions.isEmpty()) return
        emission.transmissions.forEach { tx ->
            val fromPilot = tx.sender == ATCTransmission.Sender.PILOT
            post(
                tx,
                speakIt = if (fromPilot) shouldSpeakPilot(settings) else true,
                allowRepeat = !emission.controllerInitiated,
            )
        }
        recomputeDerivedState()
    }

    // endregion

    // region Arrival ramp

    /**
     * The pilot calls Ramp.
     *
     * Pre-departure that means the pushback request; on arrival it is the taxi-in to the
     * gate. Tuning to Ramp itself never transmits — this is the To Gate button.
     *
     * The pre-departure branch is deliberately narrow. A late tap, after engine start or
     * once taxiing, must not rewind the flow back to a pushback the aircraft is long past;
     * from there the pilot uses Ground.
     */
    fun contactRamp() {
        val current = _state.value
        if (current.companionStandby) return
        if (current.isArrivalRamp) {
            arriveAtGate()
        } else if (current.isPreDeparture && stateMachine.current in RAMP_PUSHBACK_STATES) {
            performPilotAction(PilotAction.PUSHBACK)
        }
    }

    /**
     * Arrival Ramp hand-off: the aircraft is clear of the runway and taxiing in, so Ramp
     * routes it to the stand.
     *
     * This does **not** announce the block-in. The arrival is declared complete only once
     * the aircraft has actually stopped at the gate with the parking brake set, which the
     * staged flow above watches for — a parking brake set out on a taxiway must never end
     * the flight.
     */
    fun arriveAtGate() {
        val current = _state.value
        if (current.companionStandby || current.flightHasEnded || current.awaitingGateArrival) return

        _state.update {
            it.copy(
                manualTuning = true,
                hasDeparted = true,
                currentFacility = ATCFacility.RAMP,
                pendingCheckInFacility = null,
                atcState = ATCState.GROUND_ARRIVAL,
                awaitingGateArrival = true,
                gateMonitored = false,
            )
        }
        val context = buildContext(ATCState.GROUND_ARRIVAL)
        // A field with no ramp position of its own is worked by Ground throughout, so
        // there is no Ramp to call up and no routing for it to give.
        if (context.rampProfile.rampType != RampType.NONE) {
            post(rampEngine.arrivalInbound(context.callsign, context.gate), speakIt = shouldSpeakPilot(settings))
            post(
                rampEngine.proceedToGate(
                    cs = context.callsign,
                    gate = context.gate,
                    via = if (context.rampProfile.arrivalRampEntryPhrase.contains("inner")) {
                        "the inner alley"
                    } else {
                        "the ramp"
                    },
                ),
                speakIt = true,
            )
        }
        // In Mock Mode drive the scripted aircraft to the stand so the monitored block-in
        // actually plays out; the demo sets the parking brake in its parked state.
        if (settings.mockMode) advanceMockToGate()
        recomputeDerivedState()
    }

    /** One tick of the staged taxi-in: monitor, then block in. */
    private fun advanceGateArrival(aircraft: AircraftState) {
        val current = _state.value
        if (!current.gateMonitored && isSlowingAtGate(aircraft)) {
            _state.update { it.copy(gateMonitored = true) }
            post(rampEngine.monitorRampToGate(buildContext(ATCState.GROUND_ARRIVAL).callsign), speakIt = true)
        } else if (current.gateMonitored && isParkedAtGate(aircraft)) {
            completeGateArrival()
            return
        }
        // Keep the radio on Ramp while taxiing in — this is the "tuned to Ramp" the
        // completion gate checks. Once parked, control returns to the parked facility.
        _state.update { it.copy(atcState = stateMachine.current, currentFacility = ATCFacility.RAMP) }
        recomputeDerivedState()
    }

    private fun completeGateArrival() {
        _state.update { it.copy(awaitingGateArrival = false) }
        arrivalGatePosition = null
        advanceAndPost(ATCState.PARKED, announceHandoff = false)
        announceFlightComplete()
        recomputeDerivedState()
    }

    /**
     * The block-in line that ends the transcript: "United 598 parked at B44. Flight complete."
     *
     * Not a radio call — a `SYSTEM` line, which is why it is not spoken. Without it the
     * transcript simply stopped at the taxi-in clearance and nothing in the app ever said
     * the flight was over.
     *
     * Once per flight. `flightCompleteAnnounced` is cleared by [reset], so the next flight
     * gets its own; a second parking event on the same flight does not.
     */
    private fun announceFlightComplete() {
        if (flightCompleteAnnounced) return
        flightCompleteAnnounced = true
        val context = buildContext(ATCState.PARKED, arrivalOverride = true)
        val gate = context.gate.trim()
        val where = if (gate.isEmpty()) "parked at the gate" else "parked at $gate"
        post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.SYSTEM,
                facility = ATCFacility.RAMP,
                displayText = "${context.callsign.display} $where. Flight complete.",
                spokenText = "${context.callsign.spoken} $where. Flight complete.",
                timestampMillis = clock.nowMillis(),
            ),
            speakIt = false,
        )
    }

    private var flightCompleteAnnounced = false

    /** Slow enough that the stand is the next thing to happen, but not yet stopped. */
    private fun isSlowingAtGate(aircraft: AircraftState): Boolean =
        (aircraft.onGround ?: true) && (aircraft.groundSpeed ?: 0.0) < RAMP_SLOWING_GROUND_SPEED

    // endregion

    // region Airborne requests

    /**
     * Request higher or lower — and get an answer.
     *
     * Both used to post the pilot's half and stop, so the aircraft asked for a level and
     * heard nothing, and `assignedAltitude` never moved. Higher can be refused: a reported
     * ride of moderate or worse covering the target band is exactly what a controller says
     * "unable" to, and refusing it is more useful than granting a climb into known
     * turbulence. Lower is always granted at the pilot's discretion, as iOS does.
     */
    private fun altitudeRequest(context: ATCContext, higher: Boolean) {
        val target = nextAltitudeStep(higher)
        post(
            if (higher) {
                pilotEngine.requestHigher(context, target)
            } else {
                pilotEngine.requestLower(context, target)
            },
            speakIt = shouldSpeakPilot(settings),
        )

        val facility = _state.value.workingFacility
        if (higher && weatherAnswers.altitudeIsBlockedByRideReports(target)) {
            post(
                deny(context, facility, "unable higher, traffic and reported turbulence at that level"),
                speakIt = true,
            )
        } else {
            grantAltitude(context, facility, target, higher)
        }
        // The smoother-altitude hint is one-shot: it biased this request, and a stale hint
        // would silently re-target the next one at a level the ride report no longer names.
        weatherAnswers.clearSmootherAltitude()
        refreshSmootherAltitudeLabel()
    }

    /**
     * Accept the smoother level the last ride report suggested.
     *
     * A direct climb or descent to that exact level — the suggestion already sits in the
     * cruise band and is drawn from a smoother report, so no turbulence block applies.
     */
    private fun acceptSmootherAltitude(context: ATCContext) {
        val suggestion = weatherAnswers.smootherAltitude() ?: return
        val facility = _state.value.workingFacility
        post(
            if (suggestion.higher) {
                pilotEngine.requestHigher(context, suggestion.altitudeFt)
            } else {
                pilotEngine.requestLower(context, suggestion.altitudeFt)
            },
            speakIt = shouldSpeakPilot(settings),
        )
        grantAltitude(context, facility, suggestion.altitudeFt, suggestion.higher)
        weatherAnswers.clearSmootherAltitude()
        refreshSmootherAltitudeLabel()
    }

    /** The controller's climb/descend instruction, its read-back, and the new assignment. */
    private fun grantAltitude(context: ATCContext, facility: ATCFacility, target: Int, higher: Boolean) {
        val tx = if (higher) {
            engine.climbMaintain(context.callsign, target)
        } else {
            engine.descendPilotsDiscretion(context.callsign, target)
        }
        post(
            tx.copy(
                readback = altitudeReadback(
                    verb = if (higher) "Climb" else "Descend",
                    altitude = target,
                    callsign = context.callsign,
                    facility = facility,
                ),
            ),
            speakIt = true,
        )
        _state.update { it.copy(assignedAltitude = target) }
        recomputeDerivedState()
    }

    /**
     * Vectors to final — a real 30° intercept, not a repeat of the current heading.
     *
     * The heading is the safety-critical element, so it is what the read-back echoes rather
     * than a state-derived line.
     */
    private fun requestVectors(context: ATCContext) {
        post(pilotEngine.requestVectors(context), speakIt = shouldSpeakPilot(settings))

        val runway = context.approachProcedure?.runway ?: context.runway
        val heading = approachInterceptHeading(runway)
        // Name the approach as "<type> runway <rwy>" rather than a display name that
        // already embeds the runway, to avoid "… ILS RWY 01R runway 01R approach".
        val typeDisplay = context.approachProcedure?.approachTypeDisplay
            ?: context.approachName.ifEmpty { "ILS" }
        val typeSpoken = context.approachProcedure?.approachTypeSpoken
            ?: context.approachName.ifEmpty { "I L S" }
        val headingDisplay = formatHeading(heading)
        val headingSpoken = Phonetic.heading(heading, icao = engine.icao)
        post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.ATC,
                facility = ATCFacility.APPROACH,
                displayText = "${context.callsign.display}, fly heading $headingDisplay, " +
                    "vectors for the $typeDisplay runway $runway approach.",
                spokenText = "${context.callsign.spoken}, fly heading $headingSpoken, " +
                    "vectors for the $typeSpoken runway ${Phonetic.runway(runway, engine.icao)} approach.",
                timestampMillis = clock.nowMillis(),
                readback = ATCTransmission.Readback(
                    displayText = "Heading $headingDisplay, ${context.callsign.display}.",
                    spokenText = "Heading $headingSpoken, ${context.callsign.spoken}.",
                    facility = ATCFacility.APPROACH,
                ),
            ),
            speakIt = true,
        )
    }

    /**
     * The heading to fly for approach vectors: a 30° intercept to the landing runway's
     * final approach course, turning toward the extended centreline from whichever side
     * the aircraft is on.
     *
     * Falls back to the current heading when the runway or the position is unknown — a
     * pilot practising with no telemetry still gets a heading, just not a computed one.
     */
    private fun approachInterceptHeading(runway: String): Int {
        val aircraft = _state.value.aircraftState
        val fallback = ApproachIntercept.normalizedHeading(aircraft.heading ?: FALLBACK_VECTOR_HEADING)
        val finalCourse = RunwayDatabase.headingForRunway(runway) ?: return fallback
        val position = validCoordinateOrNull(aircraft.latitude, aircraft.longitude) ?: return fallback
        val airport = AirportDatabase.coordinate(_state.value.flightPlan.destination) ?: return fallback
        return ApproachIntercept.heading(
            finalCourse = finalCourse,
            aircraft = position,
            runwayReference = airport,
            // The corroborated running estimate, not this tick's raw sample: one torn
            // pair of headings would otherwise swing the intercept by its whole error.
            variationDegreesEast = windEstimator.variationDegreesEast ?: 0.0,
        )
    }

    /** Cleared for the approach, with the read-back the Read Back button then echoes. */
    private fun requestApproach(context: ATCContext) {
        post(pilotEngine.requestApproach(context), speakIt = shouldSpeakPilot(settings))
        val procedure = context.approachProcedure
        val tx = if (procedure != null) {
            engine.clearedApproach(context.callsign, procedure, context.runway)
        } else {
            engine.clearedApproach(context.callsign, context.plan.approach, context.runway)
        }
        post(
            tx.copy(
                readback = pilotEngine.readback(ATCState.FINAL, context)
                    .asReadback(facility = ATCFacility.APPROACH),
            ),
            speakIt = true,
        )
    }

    /**
     * Center reads back the ride along the route, and offers a smoother level when the
     * reports support one.
     *
     * The answer is not auto-acknowledged. The pilot answers on their own terms — by
     * accepting the suggested level, or by tapping Read Back for the courtesy "Roger"
     * attached here, so that button acknowledges *this* report rather than re-deriving a
     * stale state read-back.
     */
    private fun requestRideReport(context: ATCContext) {
        val facility = _state.value.workingFacility
        post(pilotEngine.requestRideReports(context), speakIt = shouldSpeakPilot(settings))
        scope.launch {
            val report = weatherAnswers.rideReport(context.callsign) ?: return@launch
            post(
                report.copy(readback = pilotEngine.roger(context, facility).asReadback(facility)),
                speakIt = true,
            )
            refreshSmootherAltitudeLabel()
        }
    }

    private fun requestDestinationWeather(context: ATCContext) {
        val facility = _state.value.workingFacility
        val destination = context.plan.destination
        post(
            pilotEngine.requestWeather(context, destination.ifEmpty { "destination" }),
            speakIt = shouldSpeakPilot(settings),
        )
        scope.launch {
            val wx = weatherAnswers.destinationWeather(context.callsign, destination) ?: return@launch
            post(
                wx.copy(readback = pilotEngine.roger(context, facility).asReadback(facility)),
                speakIt = true,
            )
        }
    }

    /**
     * Publish the smoother level as a button label, or clear it.
     *
     * The label is what makes the accept button appear at all, and what it reads — "Climb
     * FL390" / "Descend FL330" — so it has to follow the suggestion rather than be
     * snapshotted once.
     */
    private fun refreshSmootherAltitudeLabel() {
        val suggestion = weatherAnswers.smootherAltitude()
        val label = suggestion?.let {
            "${if (it.higher) "Climb" else "Descend"} ${engine.formatAltDisplay(it.altitudeFt)}"
        }
        if (_state.value.smootherAltitudeLabel == label) return
        _state.update { it.copy(smootherAltitudeLabel = label) }
        recomputeDerivedState()
    }

    private fun deny(context: ATCContext, facility: ATCFacility, reason: String): ATCTransmission =
        ATCTransmission.create(
            sender = ATCTransmission.Sender.ATC,
            facility = facility,
            displayText = "${context.callsign.display}, $reason.",
            spokenText = "${context.callsign.spoken}, $reason.",
            timestampMillis = clock.nowMillis(),
        )

    private fun altitudeReadback(
        verb: String,
        altitude: Int,
        callsign: PhraseologyEngine.Callsign,
        facility: ATCFacility,
    ) = ATCTransmission.Readback(
        displayText = "$verb and maintain ${engine.formatAltDisplay(altitude)}, ${callsign.display}.",
        spokenText = "$verb and maintain ${engine.spokenAltitude(altitude)}, ${callsign.spoken}.",
        facility = facility,
    )

    /**
     * The level a "request higher" / "request lower" asks for.
     *
     * Measured from the higher of the current assignment and the aircraft's actual
     * altitude, so a pilot who has drifted above their assignment is not offered a "climb"
     * to a level they are already at. A smoother level the last ride report named wins when
     * it lies in the requested direction — it is already bounded to the cruise band.
     */
    private fun nextAltitudeStep(higher: Boolean): Int {
        val current = max(
            _state.value.assignedAltitude,
            _state.value.aircraftState.altitudeMSL?.toInt() ?: 0,
        )
        weatherAnswers.smootherAltitude()?.altitudeFt?.let { suggested ->
            if ((higher && suggested > current) || (!higher && suggested < current)) return suggested
        }
        val base = when {
            current > 0 -> current
            _state.value.flightPlan.cruiseAltitude > 0 -> _state.value.flightPlan.cruiseAltitude
            else -> DEFAULT_CRUISE_ALTITUDE_FT
        }
        val target = if (higher) base + ALTITUDE_REQUEST_STEP else base - ALTITUDE_REQUEST_STEP
        return max(MINIMUM_REQUESTABLE_ALTITUDE_FT, target)
    }

    private fun formatHeading(degrees: Int): String = degrees.toString().padStart(3, '0')

    // endregion

    /**
     * Manually tune the radio. Switching frequency does **not** check in or advance the
     * conversation on its own: it only moves the radio. From here the pilot either taps
     * Check In to call the controller up, or makes a specific request. This is how the
     * pilot drives the flight forward without calls auto-playing back to back.
     *
     * Tuning is always allowed — even while standing by for a human controller. Moving
     * the radio is how the pilot leaves a staffed frequency to lift the guard, and it
     * never transmits.
     */
    fun tuneTo(facility: ATCFacility, manual: Boolean = true) {
        _state.update {
            it.copy(
                currentFacility = facility,
                manualTuning = it.manualTuning || manual,
                pendingCheckInFacility = if (it.pendingCheckInFacility == facility) {
                    null
                } else {
                    it.pendingCheckInFacility
                },
            )
        }
        recomputeDerivedState()
    }

    private inline fun postPilot(build: (ATCContext) -> ATCTransmission) {
        post(build(buildContext(stateMachine.current)), speakIt = shouldSpeakPilot(settings))
    }

    // endregion

    // region Transcript

    /** Append a transmission to the transcript and speak it. */
    /**
     * Mark the flight as deliberately over. Everything watching the session — the
     * foreground service above all — keys on this rather than trying to infer an ending
     * from the ATC state, which only ever reaches a terminal value when the flight
     * happens to finish at the arrival gate.
     *
     * Deliberately does not clear the transcript: the pilot may still want to read or
     * export it, and the next controller exchange revives the session anyway.
     */
    // region Session snapshots

    /**
     * Capture everything a reconnect or a relaunch needs to resume this conversation.
     *
     * SessionSnapshot, SessionStateStore and SavedFlightStore were all ported and tested,
     * and nothing ever produced a snapshot — so a flight killed mid-cruise was simply
     * gone, and "saved flights" saved nothing. This is the missing producer.
     *
     * The state machine's own cursor is captured separately from [FlightSessionState
     * .atcState]: the two differ deliberately (the conversational position the UI shows
     * versus the gate-to-gate cursor the flow drives off), and restoring only one of them
     * would resume the flight in a state the machine disagrees with.
     */
    fun captureSnapshot(): SessionSnapshot {
        val current = _state.value
        val atisReceipt = weatherAnswers.atisReceipt()
        return SessionSnapshot(
            atcState = current.atcState,
            stateMachineCurrent = stateMachine.current,
            currentFacility = current.currentFacility,
            phase = current.phase,
            assignedAltitude = current.assignedAltitude,
            hasDeparted = current.hasDeparted,
            arrivalAnnounced = current.flightHasEnded,
            goAroundInProgress = goAroundInProgress,
            awaitingGateArrival = current.awaitingGateArrival,
            manualTuning = current.manualTuning,
            transcript = current.transcript,
            departure = current.flightPlan.departure,
            destination = current.flightPlan.destination,
            mockMode = current.mockMode,
            savedAtMillis = clock.nowMillis(),
            centerSectorID = sectorTracker.current?.id,
            awaitingCenterSectorCheckIn = awaitingCenterSectorCheckIn,
            monitoringTower = current.monitoringTower,
            gateMonitored = current.gateMonitored,
            weatherDeviation = weatherDeviation?.state?.value?.context,
            // Not the broadcast — that is re-fetched — but what the pilot did with it.
            reportedDepartureInfo = atisReceipt.reportedDeparture,
            reportedArrivalInfo = atisReceipt.reportedArrival,
            departureInfoAppended = atisReceipt.departureReported,
            arrivalInfoAppended = atisReceipt.arrivalReported,
            departureATISDismissed = atisReceipt.departureDismissed,
            arrivalATISDismissed = atisReceipt.arrivalDismissed,
            atcCommunicationStarted = current.atcCommunicationStarted,
            flightPlan = current.flightPlan,
            tunedFacility = current.currentFacility,
            pendingCheckInFacility = current.pendingCheckInFacility,
            awaitingReadback = current.awaitingReadback,
            pendingReadbackTx = readbackGate.pending,
            readbackPrompts = readbackGate.promptCount,
            arrivalGateLatitude = arrivalGatePosition?.latitude,
            arrivalGateLongitude = arrivalGatePosition?.longitude,
            departureFieldElevationMSL = departureFieldElevationMSL,
            liftoffAltitudeMSL = liftoffAltitudeMSL,
        )
    }

    /**
     * Resume from a snapshot.
     *
     * Deliberately does not restore telemetry — position, altitude and phase debug come
     * from the next live fix a moment later, and a stale position would drive one wrong
     * round of phase detection before being corrected. What is restored is everything the
     * simulator cannot tell us again: what was said, where the conversation had got to,
     * and what the pilot still owes a read-back for.
     */
    fun restore(snapshot: SessionSnapshot) {
        stateMachine.restore(snapshot.stateMachineCurrent)
        readbackGate.adopt(
            transmission = snapshot.pendingReadbackTx,
            prompts = snapshot.readbackPrompts ?: 0,
            closed = snapshot.awaitingReadback == true,
        )
        arrivalGatePosition = validCoordinateOrNull(
            snapshot.arrivalGateLatitude,
            snapshot.arrivalGateLongitude,
        )
        goAroundInProgress = snapshot.goAroundInProgress ?: false
        awaitingCenterSectorCheckIn = snapshot.awaitingCenterSectorCheckIn ?: false
        // Resolved on the first fix after the sector map finishes loading — a restore runs at
        // launch, before the background parse is done. Adopting it is what stops a reconnect
        // mid-cruise re-announcing a hand-off the pilot already made.
        pendingCenterSectorID = snapshot.centerSectorID
        snapshot.departureFieldElevationMSL?.let { departureFieldElevationMSL = it }
        snapshot.liftoffAltitudeMSL?.let { liftoffAltitudeMSL = it }

        _state.update {
            it.copy(
                atcState = snapshot.atcState,
                currentFacility = snapshot.currentFacility,
                pendingCheckInFacility = snapshot.pendingCheckInFacility,
                phase = snapshot.phase,
                assignedAltitude = snapshot.assignedAltitude,
                hasDeparted = snapshot.hasDeparted,
                manualTuning = snapshot.manualTuning,
                monitoringTower = snapshot.monitoringTower ?: false,
                awaitingGateArrival = snapshot.awaitingGateArrival,
                gateMonitored = snapshot.gateMonitored ?: false,
                awaitingReadback = snapshot.awaitingReadback ?: false,
                transcript = snapshot.transcript,
                latestTransmission = snapshot.transcript.lastOrNull { tx -> !tx.isATISLine },
                flightPlan = snapshot.flightPlan ?: it.flightPlan,
                // A resumed session is by definition not an ended one.
                sessionEnded = false,
            )
        }
        // An in-progress diversion has to come back with the flight, or the deviation card
        // and its "clear of weather" button vanish when the link drops mid-diversion and the
        // pilot is left flying an approved reroute the app has forgotten about.
        snapshot.weatherDeviation?.let { weatherDeviation?.restore(it) }
        // The information code the pilot copied, and whether they have already given it.
        // Without this a relaunch mid-taxi silently drops the code and puts the ATIS
        // button back, so the pilot reports a letter they have not listened to — or
        // reports the same one twice.
        weatherAnswers.restoreAtisReceipt(
            AtisReceipt(
                reportedDeparture = snapshot.reportedDepartureInfo,
                reportedArrival = snapshot.reportedArrivalInfo,
                departureReported = snapshot.departureInfoAppended ?: false,
                arrivalReported = snapshot.arrivalInfoAppended ?: false,
                departureDismissed = snapshot.departureATISDismissed ?: false,
                arrivalDismissed = snapshot.arrivalATISDismissed ?: false,
            ),
        )
        recomputeDerivedState()
        diagnostics.log(
            DiagnosticCategory.SESSION,
            message = "Resumed session with ${snapshot.transcript.size} transmissions",
        )
    }

    // endregion

    fun endSession() {
        _state.update { it.copy(sessionEnded = true) }
    }

    /**
     * Re-read the saved-flight library.
     *
     * Saving, loading or deleting changes what [FlightSessionState.hasUnsavedFlight] and
     * [FlightSessionState.savedFlightRetiredByClearing] should say, and the session has no
     * way to hear about any of them. Without this the Save button stays disabled after the
     * pilot binds a slot, and the "will be lost" warning keeps appearing for a flight that
     * is now safely in the list.
     */
    fun refreshSavedFlightState() = recomputeDerivedState()

    /**
     * Put the flight down and start from a clean session.
     *
     * Everything that describes *this* flight goes: the conversation, the clearances, the
     * read-back gate, the ground references, the phase and the last fix. The flight plan
     * stays, because a pilot starting again is usually flying the same route — which is
     * what iOS promises in as many words ("Your settings and flight plan are kept").
     *
     * The last fix goes with the rest deliberately. Distance-based decisions — arrival-ATIS
     * range, the weather corridor, which airport surface to pre-cache — would otherwise be
     * measured from the previous aircraft's position until the next telemetry tick lands.
     *
     * This resets only what the coordinator owns. Weather, ATIS, the airport surface, the
     * chatter and the speech queue live in their own engines, and the app resets those
     * alongside this — the split iOS does not have, because there it is all one object.
     */
    fun resetForNewFlight() {
        // reset() lands on NOT_CONNECTED, which is a *link* state, not a conversational
        // one. A session that still has a live link is idle, not disconnected, so the two
        // cursors would disagree and the ATC screen would offer nothing at all.
        stateMachine.reset()
        stateMachine.restore(ATCState.CONNECTED_IDLE)
        // reset(), not clear(): clear() is the pilot-answered path and returns early when
        // the gate is already open, leaving a pending transmission behind.
        readbackGate.reset()
        sectorTracker.reset()
        sectorLoadRequested = false
        goAroundInProgress = false
        awaitingCenterSectorCheckIn = false
        pendingCenterCrossing = null
        pendingCenterSectorID = null
        applyCenterSector(null)
        arrivalGatePosition = null
        departureFieldElevationMSL = 0.0
        liftoffAltitudeMSL = 0.0
        flightCompleteAnnounced = false
        takeoffClearanceArmedAtMillis = null
        windEstimator.reset()
        departureHeadingSummary = null

        val plan = _state.value.flightPlan
        _state.value = FlightSessionState(
            flightPlan = plan,
            atcState = ATCState.CONNECTED_IDLE,
            currentFacility = ATCFacility.CLEARANCE,
        )
        weatherDeviation?.reset()
        recomputeDerivedState()
        diagnostics.log(DiagnosticCategory.SESSION, message = "Started a new flight")
    }

    fun post(tx: ATCTransmission, speakIt: Boolean = true, allowRepeat: Boolean = false) {
        // A controller call that would only repeat the last one, and which the pilot has
        // already acknowledged, adds nothing. A call that went unanswered is never held —
        // re-issuing it is how an unheard instruction gets through.
        //
        // `allowRepeat` is for the one case where repeating is the entire point: the pilot
        // asked the controller to say again. Without it the guard silently swallows the
        // repeat and "say again" does nothing at all.
        if (!allowRepeat &&
            tx.sender == ATCTransmission.Sender.ATC &&
            ATCTransmission.isAcknowledgedRepeat(tx, _state.value.transcript)
        ) {
            return
        }

        _state.update {
            it.copy(
                transcript = it.transcript + tx,
                // ATIS is a one-way broadcast: it is never the call a read-back answers.
                latestTransmission = if (tx.isATISLine) it.latestTransmission else tx,
                // Traffic again means the flight is live again, so a session ended earlier
                // in this process does not keep the next one from starting the service.
                sessionEnded = false,
            )
        }

        // Any pilot transmission is an acknowledgement, so it opens the gate.
        if (tx.sender == ATCTransmission.Sender.PILOT) readbackGate.clear()

        if (speakIt) speak(tx)
        recomputeDerivedState()
    }

    private fun repeatPendingCall(tx: ATCTransmission) {
        val callsign = engine.callsign(
            airline = _state.value.flightPlan.airline,
            flightNumber = _state.value.flightPlan.flightNumber,
            fallback = _state.value.flightPlan.callsign,
        )
        val (display, spoken) = ReadbackGate.repeatText(tx, callsign.display)
        post(
            ATCTransmission.create(
                sender = ATCTransmission.Sender.ATC,
                facility = tx.facility,
                displayText = display,
                spokenText = spoken,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }

    // endregion

    // region Derived state

    private fun syncStateMachine() {
        _state.update {
            it.copy(
                atcState = stateMachine.current,
                currentFacility = it.pendingCheckInFacility?.let { _ -> it.currentFacility }
                    ?: AtcFlowOrder.controller(stateMachine.current, it.currentFacility),
            )
        }
        recomputeDerivedState()
    }

    /**
     * Recompute everything the UI reads that is a function of the rest of the state:
     * which buttons apply, which frequencies are worth showing, and whether the companion
     * should be standing by.
     */
    private fun recomputeDerivedState() {
        _state.update { current ->
            val standby = computeStandby(current)
            val inputs = PilotActionAvailability.Inputs(
                workingFacility = current.workingFacility,
                atcState = stateMachine.current,
                phase = current.phase,
                aircraftState = current.aircraftState,
                isPreDeparture = !current.hasDeparted,
                hasDeparted = current.hasDeparted,
                companionStandby = standby,
                monitoringTower = current.monitoringTower,
                pushbackOnGround = buildContext(stateMachine.current).pushbackFacility ==
                    ATCFacility.GROUND,
                hasSmootherAltitudeSuggestion = current.smootherAltitudeLabel != null,
            )
            // The three saved-flight answers. Declared on the state since the port began and
            // computed by nothing until now, so every one of them read `false` for the life
            // of the app: Save was permanently disabled and no confirmation ever warned that
            // a flight was about to be thrown away.
            val binding = savedFlightBinding()
            val complete = SavedFlightPolicy.flightIsComplete(
                atcStateIsParked = current.flightHasEnded,
                arrivalAnnounced = current.flightHasEnded,
            )
            current.copy(
                companionStandby = standby,
                availableActions = PilotActionAvailability.availableActions(inputs),
                availableWeatherDeviationActions =
                    weatherDeviation?.state?.value?.actions?.toSet() ?: emptySet(),
                relevantFacilities = AtcFlowOrder.relevantFacilities(
                    currentFacility = current.currentFacility,
                    pendingCheckInFacility = current.pendingCheckInFacility,
                    currentState = stateMachine.current,
                ),
                mockMode = settings.mockMode,
                canSaveCurrentFlight = SavedFlightPolicy.canSaveCurrentFlight(
                    mockMode = settings.mockMode,
                    flightIsComplete = complete,
                    transcriptIsEmpty = current.transcript.isEmpty(),
                    hasDeparted = current.hasDeparted,
                    departure = current.flightPlan.departure,
                    destination = current.flightPlan.destination,
                ),
                hasUnsavedFlight = SavedFlightPolicy.hasUnsavedFlight(
                    mockMode = settings.mockMode,
                    flightIsComplete = complete,
                    transcriptIsEmpty = current.transcript.isEmpty(),
                    hasDeparted = current.hasDeparted,
                    autoSaveFlights = settings.autoSaveFlights,
                    activeFlightStillInLibrary = binding.activeFlightStillInLibrary,
                ),
                savedFlightRetiredByClearing = SavedFlightPolicy.retiredByClearing(
                    flightIsComplete = complete,
                    activeFlightName = binding.activeFlightName,
                ),
            )
        }
    }

    /**
     * Whether the companion should defer to a human controller right now. The guard is
     * per-frequency and location-aware: in live mode it applies only while the pilot's
     * tuned frequency is a staffed human controller, so tuning to UNICOM, ATIS or an
     * unstaffed field lifts it. In mock mode it follows the demo toggle.
     */
    private fun computeStandby(current: FlightSessionState): Boolean =
        if (settings.mockMode) {
            current.simulateStaffedATC && current.currentFacility.isFAAATC
        } else {
            current.liveATC.companionShouldStandBy
        }

    private fun updateAssignedAltitude(target: ATCState, context: ATCContext) {
        val altitude = when (target) {
            // The clearance and the takeoff clearance both assign the initial climb, so the
            // pilot has an altitude to fly from the moment they are cleared — not only once
            // Departure is working them.
            ATCState.CLEARANCE, ATCState.TOWER_DEPARTURE -> context.initialClimbAltitude

            ATCState.INITIAL_CLIMB, ATCState.DEPARTURE ->
                if (context.traconCeiling > 0) {
                    context.traconCeiling
                } else {
                    max(context.assignedAltitude, context.initialClimbAltitude)
                }

            ATCState.CLIMB, ATCState.CRUISE -> context.cruiseAltitude
            ATCState.DESCENT -> ATCStateMachine.descentTargetAltitude(context)
            ATCState.APPROACH -> if (context.approachInterceptAltitude > 0) {
                context.approachInterceptAltitude
            } else {
                context.approachDefaultAltitude
            }

            else -> return
        }
        if (altitude > 0) _state.update { it.copy(assignedAltitude = altitude) }
    }

    /**
     * The arrival is only complete once the aircraft is **stopped with the parking brake
     * set, on the Ramp frequency, and within [GATE_ARRIVAL_RADIUS_METERS] of the assigned
     * stand**.
     *
     * All three matter. A parking-brake stop out on an active taxiway would otherwise end
     * the flight; so would a stop before the pilot has contacted Ramp at all. The radius
     * is generous enough to absorb the offset between the OpenStreetMap stand and the
     * Infinite Flight scenery, and tight enough to exclude a stop on a taxiway. When no
     * stand position is known — the surface extract has not landed, or the field is not in
     * OpenStreetMap — the position test is skipped rather than blocking the arrival
     * forever.
     *
     * Mock Mode bypasses the frequency test, because the demo drives the whole flight
     * without the pilot necessarily tuning Ramp.
     */
    private fun isParkedAtGate(aircraft: AircraftState): Boolean {
        val stopped = (aircraft.onGround ?: true) &&
            (aircraft.groundSpeed ?: 0.0) < PARKED_GROUND_SPEED
        // When the sim exposes the parking brake it must be set; when it does not, being
        // stopped is the best signal available.
        val parked = aircraft.parkingBrakeSet?.let { stopped && it } ?: stopped
        if (!parked) return false

        if (!settings.mockMode && _state.value.currentFacility != ATCFacility.RAMP) return false

        val gate = arrivalGatePosition
        val position = aircraft.coordinate
        if (gate != null && position != null) {
            return Geo.distanceNM(position, gate) * METERS_PER_NM <= GATE_ARRIVAL_RADIUS_METERS
        }
        return true
    }

    /**
     * Where the assigned arrival stand is, once the surface extract has resolved it. Null
     * until then, and null at a field with no OpenStreetMap coverage.
     */
    var arrivalGatePosition: Coordinate? = null

    /**
     * The frequency a facility is reached on, for the tune buttons. Public because the
     * facility list is UI, but the numbers are the engine's — the buttons must show what a
     * call will actually say.
     */
    /**
     * Feed every airborne fix to the sector tracker and publish the sector's radio name.
     *
     * Fed for the whole airborne phase rather than only the enroute leg, so the name is
     * already correct at the moment Departure hands over rather than a fix or two later.
     *
     * The database is ~550 KB of JSON, so it is loaded off-thread and only once, on the
     * first airborne fix that needs it — not at construction, which would make every test
     * and every preflight pay for it. Until it reports ready the tracker returns nothing
     * and the generic "Center" fallback holds, which is the correct degraded state.
     */
    private fun updateCenterSector(aircraft: AircraftState) {
        if (!settingsProvider().centerSectorHandoffs) return
        // onGround is nullable — treat "unknown" as on the ground rather than guessing a
        // position is airborne, which would feed the tracker taxiway fixes.
        if (aircraft.onGround != false) return
        val coordinate = aircraft.coordinate ?: return

        val database = sectorDatabase
        if (!database.isReady) {
            if (!sectorLoadRequested) {
                sectorLoadRequested = true
                database.prepare(scope)
            }
            return
        }

        // A restored session names the sector the pilot was working; adopt it as soon as the
        // map can resolve it, so the next crossing is measured from there rather than read as
        // a first entry.
        pendingCenterSectorID?.let { restored ->
            database.sector(restored)?.let { sector ->
                pendingCenterSectorID = null
                sectorTracker.adopt(sector)
                applyCenterSector(sector)
            }
        }

        // Every fix goes to the tracker whoever is working the flight: it needs an unbroken
        // track to tell a flown boundary crossing from a position jump.
        val crossing = sectorTracker.update(
            coordinate = coordinate,
            atMillis = clock.nowMillis(),
            database = database,
        )
        applyCenterSector(sectorTracker.current)

        if (AtcFlowOrder.controller(stateMachine.current, _state.value.currentFacility) !=
            ATCFacility.CENTER
        ) {
            // Departure and Approach own the radio at either end of the enroute leg. The
            // sector still moves under the aircraft, but nobody says so — and a crossing made
            // under another controller is never announced late: whoever hands the flight to
            // Center names the sector it is in at that moment.
            pendingCenterCrossing = null
            return
        }

        if (crossing != null) {
            pendingCenterCrossing = CenterSectorTracker.Crossing(
                from = pendingCenterCrossing?.from ?: crossing.from,
                to = crossing.to,
            )
        }
        val pending = pendingCenterCrossing ?: return
        // Wait for the radio: the last instruction read back, no hand-off outstanding, and no
        // go-around being flown.
        if (readbackGate.isClosed && !settings.mockMode) return
        if (_state.value.pendingCheckInFacility != null) return
        if (goAroundInProgress) return
        pendingCenterCrossing = null
        announceCenterSectorHandoff(pending)
    }

    /**
     * Publish the working sector and make every engine that names a controller say it.
     *
     * The state machine keeps its own engine copy on purpose: rebuilding that here would
     * reset the gate-to-gate cursor mid-flight, and none of its calls name the facility.
     */
    private fun applyCenterSector(sector: CenterSector?) {
        if (sector?.id == appliedCenterSector?.id) return
        appliedCenterSector = sector
        engine = engine.copy(centerSectorName = sector?.radioName)
        pilotEngine = PilotResponseEngine(engine)
        onEngineRebuilt?.invoke(engine)
        val name = sector?.radioName
        if (name != _state.value.centerSectorName) {
            _state.update { it.copy(centerSectorName = name) }
        }
    }

    /**
     * The sector being left hands the flight to the next one.
     *
     * A plain frequency hand-off — "United 598, contact Memphis Center on 133.425" —
     * attributed to Center, with the read-back that tunes the new sector. Deliberately no
     * state advance: the same facility keeps working the aircraft, only the sector changes.
     */
    private fun announceCenterSectorHandoff(crossing: CenterSectorTracker.Crossing) {
        val context = buildContext(stateMachine.current)
        post(
            engine.handoff(
                cs = context.callsign,
                from = ATCFacility.CENTER,
                to = ATCFacility.CENTER,
                frequency = crossing.to.frequency,
            ),
            speakIt = true,
        )
        awaitingCenterSectorCheckIn = true
        diagnostics.log(
            DiagnosticCategory.ATC,
            message = "Center sector hand-off: ${crossing.from.id} → ${crossing.to.id}",
        )
    }

    fun frequencyForFacility(facility: ATCFacility): Double =
        frequencyFor(facility, buildContext(stateMachine.current))

    /**
     * Handle a spoken pilot transmission from push-to-talk.
     *
     * The parse is the engine's job, not the UI's: recognised speech is matched to an
     * intent and routed to the same handler the corresponding button would have called, so
     * a spoken read-back and a tapped one are indistinguishable downstream — same gate,
     * same standby guard, same transcript. Returns the intent's title for the UI to echo,
     * or null when nothing in the text matched, in which case the app says so rather than
     * guessing at a clearance.
     */
    fun handleSpokenPilotText(text: String): String? {
        if (text.isBlank()) return null
        val intent = intentParser.parse(text)
        val action = intent.pilotAction
        // Bracketed so every pilot transmission this dispatches goes into the transcript
        // without being synthesized: the pilot has just said it into the microphone, and
        // hearing the app recite it back at them over the radio is the one thing
        // push-to-talk must not do.
        pilotInputViaVoice = true
        try {
            return when {
                intent == PilotIntent.READBACK -> { readBack(); intent.title }
                intent == PilotIntent.SAY_AGAIN -> { sayAgain(); intent.title }
                intent == PilotIntent.UNABLE -> { unable(); intent.title }
                intent == PilotIntent.CHECK_IN -> { checkIn(); intent.title }
                intent == PilotIntent.WILCO -> { wilco(); intent.title }
                action != null -> { performPilotAction(action); intent.title }
                else -> null
            }
        } finally {
            pilotInputViaVoice = false
        }
    }

    /**
     * True while [handleSpokenPilotText] is dispatching, i.e. while the transmission being
     * posted is one the pilot has just spoken themselves.
     */
    private var pilotInputViaVoice = false

    /** Whether a pilot transmission posted right now should also be spoken aloud. */
    private fun shouldSpeakPilot(settings: AppSettings): Boolean =
        settings.speakPilot && !pilotInputViaVoice

    /**
     * "Wilco" — will comply.
     *
     * It acknowledges the instruction rather than reciting it, which is the whole
     * difference between it and a read-back, and it is what the pilot actually said. The
     * app answered it with the full read-back instead, so a pilot who said "wilco" heard
     * their own aircraft recite an instruction they had not read back.
     *
     * A wilco is still an acknowledgement, so it opens the read-back gate like any other
     * pilot transmission, and it still tunes the radio when the call it answers was a
     * hand-off — a pilot who says "wilco" to "contact Tower on 118.3" means they are
     * going there.
     *
     * A runway-crossing clearance is the one instruction a wilco may not answer: on
     * Android reading it back is what authorizes the crossing, and the read-back has to
     * be the words. That case goes to [readBack].
     */
    fun wilco() {
        if (_state.value.companionStandby) return
        if (groundHandoffSignals().awaitingCrossingReadback) {
            readBack()
            return
        }
        val settings = settingsProvider()
        val context = buildContext(stateMachine.current)
        // Read before posting: post() makes the pilot's own line the latest transmission,
        // and a pilot line carries no read-back to tune from.
        val tuneTo = _state.value.latestTransmission?.readback?.tuneTo
        post(
            pilotEngine.wilco(context, _state.value.workingFacility),
            speakIt = shouldSpeakPilot(settings),
        )
        if (tuneTo != null && settings.autoTuneOnHandoff) tuneTo(tuneTo, manual = false)
    }

    private fun frequencyFor(facility: ATCFacility, c: ATCContext): Double = when (facility) {
        ATCFacility.RAMP -> c.rampFrequency
        ATCFacility.CLEARANCE, ATCFacility.GROUND -> c.groundFrequency
        ATCFacility.TOWER -> c.towerFrequency
        ATCFacility.DEPARTURE -> c.departureFrequency
        ATCFacility.CENTER -> c.centerFrequency
        ATCFacility.APPROACH -> c.approachFrequency
    }

    /** Mock-mode demo toggle that exercises the step-aside behaviour. */
    fun setSimulateStaffedATC(simulate: Boolean) {
        _state.update { it.copy(simulateStaffedATC = simulate) }
        // iOS derives the staffing snapshot from this toggle through a publisher
        // (AppModel.swift:1389-1394); the same derivation happens inline here, so the
        // Diagnostics demo produces a real LiveATCStatus rather than only a boolean.
        if (settings.mockMode) {
            applyLiveATC(if (simulate) mockStaffedStatus() else LiveATCStatus.none)
        } else {
            recomputeDerivedState()
        }
    }

    /**
     * A simulated staffing snapshot for the Diagnostics demo toggle in Mock Mode: pretend
     * the pilot is tuned to a staffed controller matching the facility they are on.
     *
     * Ported from `AppModel.mockStaffedStatus()` (IFATCCompanion/App/AppModel.swift:1399).
     */
    fun mockStaffedStatus(): LiveATCStatus {
        val facility = _state.value.currentFacility
        return LiveATCStatus(
            multiplayerOnline = true,
            serverName = "Expert",
            humanControllerActive = true,
            controllerName = "Demo Controller",
            tunedFrequencyName = if (facility.isFAAATC) facility.title else null,
        )
    }

    // endregion

    /**
     * Assemble the context the phraseology engine composes from: the callsign, the plan,
     * the runway in use, the frequencies and the procedures.
     */
    fun buildContext(state: ATCState, arrivalOverride: Boolean? = null): ATCContext {
        val current = _state.value
        val plan = current.flightPlan
        val s = settings
        // Requesting an approach or vectors is inherently an arrival action, so callers can
        // force the arrival side even when the conversational state has not caught up.
        val arriving = arrivalOverride ?: (current.hasDeparted || state in ARRIVAL_STATES)
        val icao = if (arriving) plan.destination else plan.departure

        // The wind every takeoff and landing clearance reads out. With no report the
        // fallback is a plausible fixed wind rather than "wind zero zero zero at zero",
        // which is the one wind a controller never says.
        val metar = weatherAnswers.metar(arriving)
        val windDirection = metar?.windDirection ?: DEFAULT_WIND_DIRECTION
        val windSpeed = metar?.windSpeed ?: DEFAULT_WIND_SPEED

        val sid = ProcedureParser.parseSID(plan.sid, icao)
        val star = ProcedureParser.parseSTAR(plan.star, icao)
        val approach = ProcedureParser.parseApproach(plan.approach)

        val resolvedRunway = resolveRunway(plan, arriving, windDirection, windSpeed, approach)
        val runway = resolvedRunway.ident

        // A live OpenStreetMap route supersedes this the moment one resolves; until then the
        // deterministic layout is what keeps the clearance from reading "taxi to runway 27
        // via ." at a field the app has no extract for.
        val taxi = taxiContextProvider() ?: taxiPlanner.plan(icao, runway, arriving).let {
            TaxiClearanceContext(
                taxiways = it.taxiwaysText,
                crossingRunway = it.crossingRunway,
                parkingTaxiway = it.parkingTaxiway,
            )
        }

        val departure = departureGuidance(plan, sid, resolvedRunway)
        val rampProfile = RampProfile.profile(icao)

        return ATCContext(
            callsign = engine.callsign(
                airline = plan.airline,
                flightNumber = plan.flightNumber,
                fallback = plan.callsign,
            ),
            plan = plan,
            assignedAltitude = current.assignedAltitude,
            cruiseAltitude = if (plan.cruiseAltitude > 0) plan.cruiseAltitude else DEFAULT_FILED_CRUISE_FT,
            initialClimbAltitude = elevationAwareInitialClimbFt(),
            windDirection = windDirection,
            windSpeed = windSpeed,
            squawk = deterministicSquawk(plan),
            runway = runway,
            runwayIsKnown = resolvedRunway.isKnown,
            taxiway = taxi.taxiways,
            crossingRunway = taxi.crossingRunway,
            parkingTaxiway = taxi.parkingTaxiway,
            approachName = approach?.displayName ?: plan.approach.ifEmpty { "the ILS" },
            departureFrequency = DEFAULT_DEPARTURE_FREQUENCY,
            // The sector actually under the aircraft, once the sector map has resolved
            // one. This is the number the Center tune button shows and every "contact
            // Center on …" speaks; only the sector-to-sector crossing call used to read
            // the real frequency, so a Departure→Center hand-off always named the
            // fallback whatever sector the flight was in.
            centerFrequency = appliedCenterSector?.frequency ?: DEFAULT_CENTER_FREQUENCY,
            approachFrequency = DEFAULT_APPROACH_FREQUENCY,
            towerFrequency = DEFAULT_TOWER_FREQUENCY,
            groundFrequency = DEFAULT_GROUND_FREQUENCY,
            rampProfile = rampProfile,
            rampFrequency = rampProfile.rampFrequency,
            // Only spoken when the profile supplies one; otherwise the ramp call falls back
            // to "push approved, advise ready to taxi".
            pushDirection = rampProfile.defaultPushDirections.firstOrNull().orEmpty(),
            rampSpot = if (rampProfile.usesSpots) rampProfile.defaultSpotNames.firstOrNull().orEmpty() else "",
            gate = if (arriving) plan.arrivalGate else plan.departureGate,
            departureHeading = departure.heading,
            firstFixName = departure.firstFixName,
            traconCeiling = s.traconCeilingFL * 100,
            approachInterceptAltitude = plan.approachInterceptAltitude,
            approachDefaultAltitude = roundedUpToThousand(liveFieldElevationMSL() + APPROACH_DEFAULT_AGL_FT),
            sidProcedure = sid,
            starProcedure = star,
            approachProcedure = approach,
        )
    }

    /**
     * The beacon code assigned in the IFR clearance.
     *
     * Derived from the flight number so every flight gets its own, and formatted in **octal**
     * so it is always a legal squawk — no digit above 7. Every clearance the Android app
     * issued assigned a fixed 4271, which is also a code iOS never produces.
     *
     * Codes with a meaning of their own are stepped over rather than assigned: a controller
     * that hands a pilot 7700 has told them to declare an emergency, and 1200 is the VFR
     * conspicuity code, not a discrete assignment.
     */
    private fun deterministicSquawk(plan: FlightPlan): String {
        val digits = plan.flightNumber.filter { it.isDigit() }
        val number = digits.toIntOrNull() ?: SQUAWK_FALLBACK_SEED
        var code = (abs(number) * 7 + 1) % 4096
        // Bounded: at most one step per reserved code, and they are far apart in octal.
        while (code.toString(8).padStart(4, '0') in RESERVED_SQUAWKS) code = (code + 1) % 4096
        return code.toString(8).padStart(4, '0')
    }

    /** A runway in use, and whether anything actually knows it is the runway in use. */
    private data class ResolvedRunway(val ident: String, val isKnown: Boolean)

    /**
     * The runway in use for the current end of the flight.
     *
     * In precedence: on arrival a parsed approach's runway, then the plan's arrival runway;
     * on departure the plan's departure runway. Then a manual override, then the field's
     * real active runway for the live wind, then the into-wind pick among the runways the
     * loaded airport surface actually has.
     *
     * Only when none of those knows anything does the wind alone name one — and that is
     * returned as *not known*, so no caller reads it back as a heading or measures a
     * departure vector against it.
     */
    private fun resolveRunway(
        plan: FlightPlan,
        arriving: Boolean,
        windDirection: Int,
        windSpeed: Int,
        approach: PhraseologyProcedure?,
    ): ResolvedRunway {
        if (arriving) {
            approach?.runway?.takeIf { it.isNotEmpty() }?.let { return ResolvedRunway(it, true) }
            plan.arrivalRunway.takeIf { it.isNotEmpty() }?.let { return ResolvedRunway(it, true) }
        } else {
            plan.departureRunway.takeIf { it.isNotEmpty() }?.let { return ResolvedRunway(it, true) }
        }
        plan.runway.takeIf { it.isNotEmpty() }?.let { return ResolvedRunway(it, true) }

        val icao = if (arriving) plan.destination else plan.departure
        RunwayDatabase.activeRunway(icao, windDirection, windSpeed)
            ?.let { return ResolvedRunway(it, true) }
        // Every field whose surface the taxi map has already fetched — which, at the
        // departure airport by the time a takeoff clearance is due, is the field the
        // aircraft is sitting on. Picking the into-wind runway from a field's *real*
        // runways is what the wind is genuinely good for.
        RunwayDatabase.activeRunwayAmong(surfaceRunwaysProvider(icao), windDirection, windSpeed)
            ?.let { return ResolvedRunway(it, true) }

        // Last resort, and only a name: the wind direction rounded to the nearest ten, which
        // at least sounds like a runway at a field nothing knows anything about.
        val direction = if (windDirection == 0) DEFAULT_WIND_DIRECTION else windDirection
        var number = (direction / 10.0).roundToInt()
        if (number <= 0) number = 36
        if (number > 36) number -= 36
        return ResolvedRunway(number.toString().padStart(2, '0'), false)
    }

    /** The heading off the runway and the fix the departure climb names. */
    private data class DepartureGuidance(val heading: Int, val firstFixName: String)

    /**
     * The initial departure heading and the "resume own navigation, direct …" fix.
     *
     * Neither was ever computed, so every takeoff clearance said "fly runway heading" and
     * every departure climb said the bare "resume own navigation" — the four-argument
     * clearance overload and its runway-alignment test were unreachable.
     *
     * The heading is measured, in order of preference, from the departure runway marker the
     * flight plan carries (the point the leg is actually flown from, and what the aircraft's
     * own FMS measures against), then the live on-ground position, then the field reference.
     * These are not interchangeable: holding short at a hub puts the aircraft a mile from
     * the field reference, and a mile against a fix eight miles out is ~10° — enough to flip
     * the "within 10° of runway heading" test on its own.
     *
     * With no located fix the heading is left unknown, so the clearance says "fly runway
     * heading". It is deliberately *not* the bearing toward the destination, which for a
     * northbound departure to a southern field would point 180° the wrong way.
     */
    private fun departureGuidance(
        plan: FlightPlan,
        sid: PhraseologyProcedure?,
        runway: ResolvedRunway,
    ): DepartureGuidance {
        val aircraft = _state.value.aircraftState
        val fieldPosition = AirportDatabase.coordinate(plan.departure)
        val onRunwayPosition =
            if (aircraft.onGround == true) validCoordinateOrNull(aircraft.latitude, aircraft.longitude) else null
        // A plan can carry a runway marker at the null island; that must not outrank real
        // telemetry, so it has to be valid to win.
        val runwayMarker = plan.departureRunwayCoordinate?.let { validCoordinateOrNull(it.latitude, it.longitude) }
        val origin = runwayMarker ?: onRunwayPosition ?: fieldPosition

        // Once airborne, the next fix *ahead* of the aircraft rather than the runway-end fix
        // already passed; on the ground, simply the first filed fix.
        val position = validCoordinateOrNull(aircraft.latitude, aircraft.longitude)
        val directFix = if (_state.value.hasDeparted && position != null) {
            plan.nextUnpassedWaypoint(position, fieldPosition)
        } else {
            plan.waypoints.firstOrNull()
        }

        val interceptFix = plan.initialDepartureFix(sid?.fixes.orEmpty(), origin)
        val intercept = interceptFix?.coordinate
        val trueCourse = if (origin != null && intercept != null &&
            Geo.distanceNM(origin, intercept) >= MINIMUM_DEPARTURE_FIX_DISTANCE_NM
        ) {
            Geo.bearing(origin, intercept)
        } else {
            null
        }
        // The bearing is a *true* course (great-circle geometry) while the pilot flies a
        // magnetic heading bug — and `clearedForTakeoff` compares this number against the
        // runway's magnetic heading to decide whether to say "fly runway heading" at all,
        // so leaving it in the true frame gets both the assignment and that decision
        // wrong by the local declination. Taken through the estimator rather than
        // [HeadingSolver] directly so the pair lands in the Diagnostics row; on the ground
        // it holds no solved wind and no usable TAS, so this stays the variation conversion
        // it has always been, and once airborne it crabs, which is what iOS does here too.
        val heading = trueCourse?.let { windEstimator.assignedHeading(it) } ?: 0

        recordDepartureHeadingSummary(
            runway = runway,
            runwayMarker = runwayMarker,
            onRunwayPosition = onRunwayPosition,
            fieldPosition = fieldPosition,
            interceptFix = interceptFix,
            trueCourse = trueCourse,
            heading = heading,
        )

        if (!runway.isKnown && heading == 0) {
            // Nothing named the runway and nothing located the fix, so there is no vector to
            // give and no runway heading worth measuring against. Left at zero deliberately.
            return DepartureGuidance(heading = 0, firstFixName = directFix?.name.orEmpty())
        }
        return DepartureGuidance(heading = heading, firstFixName = directFix?.name.orEmpty())
    }

    /**
     * Record how the departure heading was arrived at, for the Diagnostics row.
     *
     * Every ingredient here has been the culprit at least once — the origin the bearing was
     * measured from, the fix it targeted, the true→magnetic step, and the runway the "fly
     * runway heading" test measures against (which is a guess from the wind whenever nothing
     * named a runway). None of it is visible anywhere else, so a clearance that came out
     * "fly runway heading" when it should have carried a turn could only be argued about.
     */
    private fun recordDepartureHeadingSummary(
        runway: ResolvedRunway,
        runwayMarker: Coordinate?,
        onRunwayPosition: Coordinate?,
        fieldPosition: Coordinate?,
        interceptFix: Waypoint?,
        trueCourse: Double?,
        heading: Int,
    ) {
        val origin = when {
            runwayMarker != null -> "runway marker"
            onRunwayPosition != null -> "aircraft"
            fieldPosition != null -> "field"
            else -> "none"
        }
        val fix = when {
            interceptFix == null -> "none"
            interceptFix.coordinate == null -> "${interceptFix.name} (unlocated)"
            else -> interceptFix.name
        }
        val vector = if (trueCourse != null) {
            String.format(Locale.US, "true %03.0f° → %03d°", trueCourse, heading)
        } else {
            "no bearing → runway heading"
        }
        departureHeadingSummary = listOf(
            "rwy ${runway.ident}" + if (runway.isKnown) "" else " (wind guess)",
            "from $origin",
            "fix $fix",
            vector,
        ).joinToString(" · ")
    }

    /**
     * Field elevation under the aircraft right now, in feet MSL, from the difference
     * between its reported MSL and AGL. Zero when the sim exposes neither.
     */
    private fun liveFieldElevationMSL(): Int {
        val aircraft = _state.value.aircraftState
        val msl = aircraft.altitudeMSL ?: return 0
        val agl = aircraft.altitudeAGL ?: return 0
        return max(0.0, msl - agl).roundToInt()
    }

    companion object {
        /** Knots below which a stopped aircraft counts as parked. */
        const val PARKED_GROUND_SPEED = 1.0

        /** Below this the aircraft is on the ramp lead-in rather than still taxiing. */
        const val RAMP_SLOWING_GROUND_SPEED = 8.0

        /**
         * The only states a Ramp call before departure may act on. Past them the aircraft
         * is already moving under Ground, and rewinding to a pushback would be wrong.
         */
        val RAMP_PUSHBACK_STATES = setOf(
            ATCState.NOT_CONNECTED,
            ATCState.CONNECTED_IDLE,
            ATCState.CLEARANCE,
            ATCState.PUSHBACK,
        )

        /**
         * How close to the gate the aircraft must be — with the parking brake set — before
         * the arrival is declared complete. Generous enough to absorb the offset between
         * the OpenStreetMap stand and the Infinite Flight scenery, tight enough to exclude
         * a parking-brake stop out on an active taxiway.
         */
        const val GATE_ARRIVAL_RADIUS_METERS = 80.0

        const val METERS_PER_NM = 1852.0

        /** The wind a clearance reads when no report is available. Never zero at zero. */
        const val DEFAULT_WIND_DIRECTION = 270
        const val DEFAULT_WIND_SPEED = 8

        /** The cruise level a plan with none filed is treated as having. */
        const val DEFAULT_FILED_CRUISE_FT = 37_000

        /** Terminal approach fallback: this far above the destination field. */
        const val APPROACH_DEFAULT_AGL_FT = 3_000

        /** Closer than this to the origin, a fix gives no meaningful bearing. */
        const val MINIMUM_DEPARTURE_FIX_DISTANCE_NM = 0.5

        /** States that are inherently on the arrival side of the flight. */
        val ARRIVAL_STATES = setOf(
            ATCState.DESCENT,
            ATCState.APPROACH,
            ATCState.FINAL,
            ATCState.LANDING,
            ATCState.RUNWAY_EXIT,
            ATCState.GROUND_ARRIVAL,
        )

        /** Below this AGL a snapshot with no explicit on-ground flag still counts as on the ground. */
        const val ON_GROUND_AGL_FT = 10.0

        /** The configured initial climb, when Settings supplies none. */
        const val DEFAULT_INITIAL_CLIMB_FT = 5000

        /** TRACON ceiling when Settings supplies no flight level. */
        const val DEFAULT_TRACON_CEILING_FT = 18000

        /**
         * Height above the departure field before Tower hands off to Departure. Handing off
         * at the wheels would clear the pilot direct to a fix still just ahead, and stack
         * the departure call on top of the takeoff clearance.
         */
        const val DEPARTURE_HANDOFF_AGL_FT = 2000.0

        /**
         * Departure hands off to Center this far below the TRACON ceiling, so the pilot has
         * time to check in and be cleared higher before the climb reaches it.
         */
        const val CENTER_HANDOFF_BUFFER_FT = 1000.0

        /** How far below the ceiling a descent counts as entering the terminal area. */
        const val TERMINAL_ENTRY_BUFFER_FT = 200.0

        /** Below this ground speed, an aircraft on the ground has vacated the runway. */
        const val RUNWAY_VACATED_GROUND_SPEED = 40.0

        /** Beyond this bank the aircraft is still turning onto final, not established. */
        const val WINGS_LEVEL_BANK_DEGREES = 6.0

        /** Short final: below this height above the field and descending. */
        const val SHORT_FINAL_AGL_FT = 1500.0
        const val SHORT_FINAL_DESCENT_FPM = -100.0

        /** The states in which Departure is working the flight. */
        private val DEPARTURE_WORKED_STATES = setOf(
            ATCState.TOWER_DEPARTURE,
            ATCState.INITIAL_CLIMB,
            ATCState.DEPARTURE,
        )

        /** The states from which the next call is the top-of-descent clearance. */
        private val CRUISE_STATES = setOf(
            ATCState.CRUISE,
            ATCState.CENTER,
            ATCState.TOP_OF_DESCENT,
        )

        /** Round up to the next whole thousand feet, so a callout is a valid MSL altitude. */
        internal fun roundedUpToThousand(feet: Int): Int =
            if (feet <= 0) 0 else (ceil(feet / 1000.0) * 1000).toInt()

        /** Feet a "request higher" / "request lower" moves the assigned altitude by. */
        const val ALTITUDE_REQUEST_STEP = 2_000

        /** The level the ladder measures from when neither an assignment nor a plan says. */
        const val DEFAULT_CRUISE_ALTITUDE_FT = 35_000

        /** No request goes below this, whatever the ladder arithmetic produces. */
        const val MINIMUM_REQUESTABLE_ALTITUDE_FT = 4_000

        /** The heading vectors fall back to with no telemetry at all. */
        const val FALLBACK_VECTOR_HEADING = 270.0

        /**
         * How much further than the reported groundspeed allows a fix may move before it
         * is treated as a discontinuity rather than as flying. iOS uses the same factor
         * and slack.
         */
        const val TELEMETRY_JUMP_FACTOR = 3.0
        const val TELEMETRY_JUMP_SLACK_NM = 1.0

        /** Below this the elapsed time is not a usable denominator, so no jump is called. */
        const val MINIMUM_JUMP_ELAPSED_SECONDS = 1.0

        /**
         * How long an aircraft holds on the runway before Tower clears it for take-off.
         * iOS uses the same five seconds.
         */
        const val TAKEOFF_CLEARANCE_DELAY_MILLIS = 5_000L

        /** Below this, an aircraft lined up on the runway is holding rather than rolling. */
        const val LINED_UP_STOPPED_GROUND_SPEED = 5.0

        /** The seed a plan with no numeric flight number falls back to. iOS uses the same. */
        const val SQUAWK_FALLBACK_SEED = 4271

        /**
         * Beacon codes that carry a meaning of their own and are never assigned as a
         * discrete code: the three emergencies, the military intercept code, and the VFR
         * and SVFR conspicuity codes.
         */
        val RESERVED_SQUAWKS = setOf("7500", "7600", "7700", "7777", "1200", "1255", "0000")

        // Simulated frequencies, matching the iOS defaults. Real per-facility
        // frequencies are not published as open data for every field.
        const val DEFAULT_GROUND_FREQUENCY = 121.8
        const val DEFAULT_TOWER_FREQUENCY = 118.3
        const val DEFAULT_DEPARTURE_FREQUENCY = 124.3

        /** Only reached before the sector map has placed the aircraft in a sector. */
        const val DEFAULT_CENTER_FREQUENCY = 132.45
        const val DEFAULT_APPROACH_FREQUENCY = 119.7
    }
}
