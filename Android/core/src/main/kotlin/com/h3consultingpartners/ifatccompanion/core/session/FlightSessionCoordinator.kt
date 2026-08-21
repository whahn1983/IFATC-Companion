package com.h3consultingpartners.ifatccompanion.core.session

import com.h3consultingpartners.ifatccompanion.core.airports.AirportDatabase
import com.h3consultingpartners.ifatccompanion.core.airports.ProcedureParser
import com.h3consultingpartners.ifatccompanion.core.airports.RampProfile
import com.h3consultingpartners.ifatccompanion.core.airports.RunwayDatabase
import com.h3consultingpartners.ifatccompanion.core.atc.ATCContext
import com.h3consultingpartners.ifatccompanion.core.atc.ATCStateMachine
import com.h3consultingpartners.ifatccompanion.core.atc.PhaseDetector
import com.h3consultingpartners.ifatccompanion.core.atc.PilotResponseEngine
import com.h3consultingpartners.ifatccompanion.core.atc.RunwayLineupDetector
import com.h3consultingpartners.ifatccompanion.core.connect.IFConnectManager
import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import com.h3consultingpartners.ifatccompanion.core.geo.Geo
import com.h3consultingpartners.ifatccompanion.core.model.ATCFacility
import com.h3consultingpartners.ifatccompanion.core.model.ATCState
import com.h3consultingpartners.ifatccompanion.core.model.ATCTransmission
import com.h3consultingpartners.ifatccompanion.core.model.AircraftState
import com.h3consultingpartners.ifatccompanion.core.model.FlightPhase
import com.h3consultingpartners.ifatccompanion.core.model.FlightPlan
import com.h3consultingpartners.ifatccompanion.core.atc.PilotIntent
import com.h3consultingpartners.ifatccompanion.core.atc.PilotIntentParser
import com.h3consultingpartners.ifatccompanion.core.phraseology.PhraseologyEngine
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import com.h3consultingpartners.ifatccompanion.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

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
class FlightSessionCoordinator(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.system,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.noop,
    private val connect: IFConnectManager? = null,
    /** Reads the current settings each time a decision needs one, so a toggle takes effect at once. */
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    /** Speaks a transmission. The app supplies text-to-speech; tests supply a recorder. */
    private val speak: (ATCTransmission) -> Unit = {},
) {

    private val _state = MutableStateFlow(FlightSessionState())
    val state: StateFlow<FlightSessionState> = _state.asStateFlow()

    private val settings: AppSettings get() = settingsProvider()

    private var engine: PhraseologyEngine = buildEngine()
    private var stateMachine = ATCStateMachine(engine)
    private var pilotEngine = PilotResponseEngine(engine)
    private val phaseDetector = PhaseDetector()
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
    fun applyEngineConfig() {
        engine = buildEngine()
        val restored = stateMachine.current
        stateMachine = ATCStateMachine(engine)
        stateMachine.restore(restored)
        pilotEngine = PilotResponseEngine(engine)
        recomputeDerivedState()
    }

    private fun buildEngine() = PhraseologyEngine(
        digitStyle = settings.digitStyle,
        mode = settings.phraseologyMode,
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
        val detection = phaseDetector.detect(
            state = aircraft,
            plan = previous.flightPlan,
            airports = AirportDatabase,
            previous = previous.phase,
        )

        captureDepartureFieldElevation(aircraft, previous)

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
        // A manual override always wins over what Connect reports.
        if (_state.value.flightPlan.manualOverride) return
        _state.update { it.copy(flightPlan = plan) }
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
        // never advances it. The one exception is the takeoff clearance, which fires
        // once the aircraft is actually lined up on the runway.
        if (stateMachine.current.isManualGroundFlow) {
            maybeIssueTakeoffClearance(aircraft)
            return
        }

        val mapped = stateMachine.mappedState(phase)
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
    private fun maybeIssueTakeoffClearance(aircraft: AircraftState) {
        if (stateMachine.current != ATCState.LINE_UP_WAIT && !_state.value.monitoringTower) return
        val runway = buildContext(stateMachine.current).runway
        val ready = lineupDetector.isLinedUp(aircraft, runway) ||
            lineupDetector.isDepartingRoll(aircraft, runway) ||
            _state.value.phase == FlightPhase.TAKEOFF
        if (!ready) return
        // When Ground already handed the pilot to Tower to monitor, that call *was* the
        // hand-off, so the clearance must not re-announce a "contact Tower".
        advanceAndPost(
            ATCState.TOWER_DEPARTURE,
            automatic = true,
            announceHandoff = !_state.value.monitoringTower,
        )
    }

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
        post(tx, speakIt = settingsProvider().speakPilot)

        // Reading a hand-off back tunes the radio to the new controller, when auto-tune
        // is on. This is the only place the radio moves without the pilot tapping.
        readback?.tuneTo?.let { facility ->
            if (settings.autoTuneOnHandoff) tuneTo(facility, manual = false)
        }
    }

    fun sayAgain() = postPilot { pilotEngine.sayAgain(it, _state.value.workingFacility) }

    fun unable() = postPilot { pilotEngine.unable(it, _state.value.workingFacility) }

    fun checkIn() {
        val current = _state.value
        val facility = current.workingFacility
        val tx = pilotEngine.requestHandoff(
            c = buildContext(stateMachine.current),
            facility = facility,
            currentAltitude = current.aircraftState.altitudeMSL?.roundToInt(),
            targetAltitude = current.assignedAltitude,
            onGround = current.aircraftState.onGround ?: false,
        )
        post(tx, speakIt = settings.speakPilot)
        _state.update { it.copy(pendingCheckInFacility = null) }
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
            PilotAction.TAXI -> pilotEngine.requestTaxi(context)
            PilotAction.READY -> pilotEngine.readyForDeparture(context)
            PilotAction.TAKEOFF -> pilotEngine.requestTakeoff(context)
            PilotAction.REQUEST_HIGHER -> pilotEngine.requestHigher(
                context,
                nextAltitudeStep(current.assignedAltitude, higher = true),
            )
            PilotAction.REQUEST_LOWER -> pilotEngine.requestLower(
                context,
                nextAltitudeStep(current.assignedAltitude, higher = false),
            )
            PilotAction.VECTORS -> pilotEngine.requestVectors(context)
            PilotAction.APPROACH -> pilotEngine.requestApproach(context)
            PilotAction.RIDE_REPORT -> pilotEngine.requestRideReports(context)
            PilotAction.DEST_WX -> pilotEngine.requestWeather(context, context.plan.destination)
            PilotAction.GO_AROUND -> pilotEngine.goAround(context)
            PilotAction.CHECK_IN -> {
                checkIn()
                return
            }
            PilotAction.TO_GATE, PilotAction.ACCEPT_SMOOTHER_ALTITUDE -> {
                // Both belong to subsystems that are wired in separately — the arrival
                // ramp flow and the ride-report suggestion. Until those land, the button
                // is not offered, so reaching here would be a bug rather than a no-op.
                return
            }
        }

        post(tx, speakIt = settings.speakPilot)
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

    /**
     * The altitude a "request higher" / "request lower" asks for: the next flight level
     * in the correct hemispheric direction, a thousand feet at a time as the iOS requests
     * do.
     */
    private fun nextAltitudeStep(current: Int, higher: Boolean): Int {
        val base = if (current > 0) current else _state.value.flightPlan.cruiseAltitude
        val step = if (higher) ALTITUDE_REQUEST_STEP else -ALTITUDE_REQUEST_STEP
        return max(ALTITUDE_REQUEST_STEP, base + step)
    }

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
        post(build(buildContext(stateMachine.current)), speakIt = settings.speakPilot)
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
    fun endSession() {
        _state.update { it.copy(sessionEnded = true) }
    }

    fun post(tx: ATCTransmission, speakIt: Boolean = true) {
        // A controller call that would only repeat the last one, and which the pilot has
        // already acknowledged, adds nothing. A call that went unanswered is never held —
        // re-issuing it is how an unheard instruction gets through.
        if (tx.sender == ATCTransmission.Sender.ATC &&
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
            current.copy(
                companionStandby = standby,
                availableActions = PilotActionAvailability.availableActions(inputs),
                relevantFacilities = AtcFlowOrder.relevantFacilities(
                    currentFacility = current.currentFacility,
                    pendingCheckInFacility = current.pendingCheckInFacility,
                    currentState = stateMachine.current,
                ),
                mockMode = settings.mockMode,
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
        return when {
            intent == PilotIntent.READBACK -> { readBack(); intent.title }
            intent == PilotIntent.SAY_AGAIN -> { sayAgain(); intent.title }
            intent == PilotIntent.UNABLE -> { unable(); intent.title }
            intent == PilotIntent.CHECK_IN -> { checkIn(); intent.title }
            // "Wilco" acknowledges the instruction it answers, which is a read-back.
            intent == PilotIntent.WILCO -> { readBack(); intent.title }
            action != null -> { performPilotAction(action); intent.title }
            else -> null
        }
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
        recomputeDerivedState()
    }

    // endregion

    /**
     * Assemble the context the phraseology engine composes from: the callsign, the plan,
     * the runway in use, the frequencies and the procedures.
     */
    fun buildContext(state: ATCState): ATCContext {
        val current = _state.value
        val plan = current.flightPlan
        val s = settings
        val arriving = current.hasDeparted
        val icao = if (arriving) plan.destination else plan.departure
        val runway = resolveRunway(plan, arriving)

        return ATCContext(
            callsign = engine.callsign(
                airline = plan.airline,
                flightNumber = plan.flightNumber,
                fallback = plan.callsign,
            ),
            plan = plan,
            assignedAltitude = current.assignedAltitude,
            cruiseAltitude = plan.cruiseAltitude,
            initialClimbAltitude = elevationAwareInitialClimbFt(),
            windDirection = 0,
            windSpeed = 0,
            squawk = DEFAULT_SQUAWK,
            runway = runway,
            taxiway = "",
            crossingRunway = null,
            parkingTaxiway = "",
            approachName = plan.approach,
            departureFrequency = DEFAULT_DEPARTURE_FREQUENCY,
            centerFrequency = DEFAULT_CENTER_FREQUENCY,
            approachFrequency = DEFAULT_APPROACH_FREQUENCY,
            towerFrequency = DEFAULT_TOWER_FREQUENCY,
            groundFrequency = DEFAULT_GROUND_FREQUENCY,
            rampProfile = RampProfile.profile(icao),
            rampFrequency = RampProfile.profile(icao).rampFrequency,
            gate = if (arriving) plan.arrivalGate else plan.departureGate,
            traconCeiling = s.traconCeilingFL * 100,
            approachInterceptAltitude = plan.approachInterceptAltitude,
            sidProcedure = ProcedureParser.parseSID(plan.sid, icao),
            starProcedure = ProcedureParser.parseSTAR(plan.star, icao),
            approachProcedure = ProcedureParser.parseApproach(plan.approach),
        )
    }

    /**
     * The runway in use for the current end of the flight. A runway the pilot filed or
     * typed wins; otherwise the field's inventory is consulted, and only then is one
     * derived from the wind.
     */
    private fun resolveRunway(plan: FlightPlan, arriving: Boolean): String {
        val filed = if (arriving) {
            plan.arrivalRunway.ifEmpty { plan.runway }
        } else {
            plan.departureRunway.ifEmpty { plan.runway }
        }
        if (filed.isNotEmpty()) return filed
        val icao = if (arriving) plan.destination else plan.departure
        return RunwayDatabase.runways(icao).firstOrNull().orEmpty()
    }

    companion object {
        /** Knots below which a stopped aircraft counts as parked. */
        const val PARKED_GROUND_SPEED = 1.0

        /**
         * How close to the gate the aircraft must be — with the parking brake set — before
         * the arrival is declared complete. Generous enough to absorb the offset between
         * the OpenStreetMap stand and the Infinite Flight scenery, tight enough to exclude
         * a parking-brake stop out on an active taxiway.
         */
        const val GATE_ARRIVAL_RADIUS_METERS = 80.0

        const val METERS_PER_NM = 1852.0

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

        const val DEFAULT_SQUAWK = "4271"

        // Simulated frequencies, matching the iOS defaults. Real per-facility
        // frequencies are not published as open data for every field.
        const val DEFAULT_GROUND_FREQUENCY = 121.9
        const val DEFAULT_TOWER_FREQUENCY = 118.3
        const val DEFAULT_DEPARTURE_FREQUENCY = 124.35
        const val DEFAULT_CENTER_FREQUENCY = 133.4
        const val DEFAULT_APPROACH_FREQUENCY = 119.7
    }
}
